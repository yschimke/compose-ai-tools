#!/usr/bin/env node
// Check a module's declared render assertions against the data products its render produced.
//
// The catalog says what to render and the visual diff says whether a render changed. Neither says
// whether a render is still TRUE to a property the project declared — which is how `:glimmer-catalog`
// typed every Glimmer role at weight 400 for its whole life while `failOnFallback` and the visual
// diff both stayed green (issue #5467). This reads the sidecars a render already writes
// (`previews/<id>.fonts.json`, `previews/<id>.semantics.json`) and asserts over them.
//
//   node check-render-assertions.mjs --assertions render-assertions.json --bundle build/previews.zip
//   node check-render-assertions.mjs --assertions render-assertions.mjs  --previews-dir build/previews
//
// `--assertions` takes a `.json` document or a `.mjs` module exporting `assertions`. The module
// form exists because the declarative vocabulary is closed: without it, a catalog stating a new
// property about its OWN design system needs a change in this repository, which is the wrong
// direction across a layer boundary. The module is imported from the catalog's checkout — the same
// checkout whose Gradle build and Kotlin this pipeline already runs, so it is not a new posture.
//
// Exit 0 when every assertion holds; 1 on a failure, an unreadable sidecar, or bad args.

import { readFile, readdir } from "node:fs/promises";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { parseArgs } from "node:util";

import { zipOffset } from "./zip-offset.mjs";
import {
  SUPPORTED,
  asAssertionsDocument,
  mergeProducts,
  productsFromEntries,
  runAssertions,
} from "./render-assertions.mjs";

const { values } = parseArgs({
  options: {
    assertions: { type: "string" },
    // A preview bundle (plain zip or polyglot PNG+ZIP), repeatable for a multi-module catalog.
    bundle: { type: "string", multiple: true, default: [] },
    // An unpacked directory of `<id>.fonts.json` / `<id>.semantics.json`, repeatable.
    "previews-dir": { type: "string", multiple: true, default: [] },
    json: { type: "boolean", default: false },
    help: { type: "boolean", default: false },
  },
});

if (values.help) {
  console.log(
    [
      "Usage: check-render-assertions.mjs --assertions <file> (--bundle <zip> | --previews-dir <dir>)...",
      "",
      "--assertions takes a .json document, or a .mjs module exporting `assertions` whose entries",
      "may carry a check(data) function returning null when it holds or a string naming what was",
      "observed. A check that throws fails; it is never skipped.",
      "",
      "Products and paths this version can assert over:",
      ...Object.entries(SUPPORTED).map(([p, paths]) => `  ${p}: ${paths.join(", ")}`),
    ].join("\n"),
  );
  process.exit(0);
}

if (!values.assertions) fail("--assertions is required");
if (values.bundle.length === 0 && values["previews-dir"].length === 0)
  fail(
    "pass at least one --bundle or --previews-dir; with no render data there is nothing to check",
  );

// A `.mjs` is imported and a `.json` parsed; both normalise to one `{assertions}` document, so the
// two forms meet before anything is evaluated rather than running down separate paths.
let doc;
try {
  const path = resolve(values.assertions);
  doc = /\.m?js$/.test(path)
    ? asAssertionsDocument(await import(pathToFileURL(path).href))
    : asAssertionsDocument(JSON.parse(await readFile(path, "utf8")));
} catch (e) {
  fail(`could not read ${values.assertions}: ${e.message}`);
}

// Sidecars from every source are merged into one `{product: {preview: data}}` before evaluating,
// so a multi-module catalog asserts across its whole render rather than once per bundle — an
// `appliesTo` naming a preview from another module would otherwise report a bogus no-data.
const indexed = [];
const unreadable = [];

// `fflate` is imported only when a bundle is actually passed. Reading an unpacked `--previews-dir`
// needs no zip library, and requiring one would make the simplest invocation depend on an
// `npm ci` this check does not otherwise need.
const { unzipSync } = values.bundle.length > 0 ? await import("fflate") : {};

for (const path of values.bundle) {
  let entries;
  try {
    const bytes = new Uint8Array(await readFile(path));
    entries = unzipSync(bytes.slice(zipOffset(bytes)));
  } catch (e) {
    fail(`could not read bundle ${path}: ${e.message}`);
  }
  absorb(productsFromEntries(entries), path);
}

for (const dir of values["previews-dir"]) {
  let names;
  try {
    names = await readdir(dir);
  } catch (e) {
    fail(`could not read ${dir}: ${e.message}`);
  }
  // Re-keyed under `previews/` so an unpacked directory and a bundle go through one indexer.
  const entries = {};
  for (const name of names)
    if (name.endsWith(".fonts.json") || name.endsWith(".semantics.json"))
      entries[`previews/${name}`] = await readFile(join(dir, name), "utf8");
  absorb(productsFromEntries(entries), dir);
}

function absorb({ products, unreadable: bad }, source) {
  indexed.push({ source, products });
  for (const message of bad) unreadable.push(`${source} — ${message}`);
}

const { products, collisions } = mergeProducts(indexed);
const { ok, results, report } = runAssertions(doc, products);
// An unreadable sidecar is a failure, not a warning: it is indistinguishable from an assertion
// that had nothing to check, and that is the whole failure mode being guarded against.
const passed = ok && unreadable.length === 0 && collisions.length === 0;

if (values.json) {
  console.log(JSON.stringify({ ok: passed, results, unreadable, collisions }, null, 2));
} else {
  const counts = Object.fromEntries(
    Object.entries(products).map(([p, byPreview]) => [p, Object.keys(byPreview).length]),
  );
  console.log(
    `Read ${counts["fonts-used"]} fonts-used and ${counts["compose-semantics"]} ` +
      `compose-semantics sidecar(s).`,
  );
  if (report) console.log(report);
  for (const message of unreadable) reportFinding("error", "Unreadable render sidecar", message);
  for (const message of collisions) reportFinding("error", "Duplicate preview id", message);
  // The report is already on stdout; re-emitting each FAIL line is only useful as a workflow
  // annotation, so it is skipped outside Actions rather than printing every failure twice.
  if (process.env.GITHUB_ACTIONS === "true")
    for (const line of report.split("\n"))
      if (line.startsWith("FAIL")) reportFinding("error", "Render assertion failed", line);
  console.log(
    passed
      ? `OK — ${results.length} assertion(s) hold.`
      : `FAILED — ${results.length} assertion(s) checked, see above.`,
  );
}

process.exit(passed ? 0 : 1);

function fail(msg) {
  reportFinding("error", "Render assertions check failed", msg);
  process.exit(1);
}

function reportFinding(level, title, message) {
  const sink = level === "error" ? console.error : console.log;
  if (process.env.GITHUB_ACTIONS === "true") {
    const escape = (value) =>
      String(value).replaceAll("%", "%25").replaceAll("\r", "%0D").replaceAll("\n", "%0A");
    sink(`::${level} title=${escape(title)}::${escape(message)}`);
  } else {
    sink(`  ${level}: ${message}`);
  }
}
