#!/usr/bin/env node
/**
 * Write a repo's `design-map.json` from its discovery manifest — the I/O around `design-map.mjs`.
 *
 *     npx @yschimke/compose-design-map [--previews <path>] [--out design-map.json]
 *                                      [--variants design-map-variants.json] [--prefix catalog]
 *                                      [--check] [--strict] [--allow-stated-absence]
 *                                      [--base-breakpoint <dp>]
 *
 * Run `./gradlew :<module>:composePreviewDiscover` first.
 *
 * `--out` is the design map; `--variants` the sidecar of unresolved variant declarations. Both are
 * generated outputs; `--check` (for CI) exits non-zero if either committed file has drifted.
 *
 * Unmapped components are reported, not fatal. `--strict` fails on any absence: a missing
 * `reference`, a `noReference`, or an ambiguous mode. `--allow-stated-absence` relaxes `--strict`
 * to accept `noReference` components, for catalogs that also publish components the kit never drew.
 */
import fs from "node:fs";
import path from "node:path";

import { projectDesignMap } from "./design-map.mjs";

function arg(name, def = undefined) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && i + 1 < process.argv.length && !process.argv[i + 1].startsWith("--")
    ? process.argv[i + 1]
    : def;
}

const PREVIEWS = arg("previews", "build/compose-previews/previews.json");
const OUT = arg("out", "design-map.json");
const VARIANTS_OUT = arg("variants", "design-map-variants.json");
const PREFIX = arg("prefix", "catalog");
const CHECK = process.argv.includes("--check");
const STRICT = process.argv.includes("--strict");
const ALLOW_STATED_ABSENCE = process.argv.includes("--allow-stated-absence");
/**
 * The screen width (dp) whose capture is the base of a breakpoint fan-out, carrying the design
 * reference; other sizes fold under it. Defaults to the narrowest.
 */
const BASE_BREAKPOINT = Number(arg("base-breakpoint", ""));

if (!fs.existsSync(PREVIEWS)) {
  console.error(
    `No discovery manifest at ${PREVIEWS}.\n` +
      `Run \`./gradlew :<module>:composePreviewDiscover\` first, or pass --previews <path>.`,
  );
  process.exit(2);
}

const manifest = JSON.parse(fs.readFileSync(PREVIEWS, "utf8"));
const { map, variants, diagnostics } = projectDesignMap(manifest.previews ?? [], {
  prefix: PREFIX,
  ...(Number.isFinite(BASE_BREAKPOINT) && BASE_BREAKPOINT > 0
    ? { baseBreakpointDp: BASE_BREAKPOINT }
    : {}),
});

// Gate BEFORE writing, not after. A run that fails should leave the committed map intact rather
// than replacing it with one CI would then report as merely stale — and an author who dropped a
// whole group's references wants the list, not one name at a time.
if (STRICT) {
  const missing = [
    ...diagnostics.unmapped.map((id) => `${id} — no reference, and no reason given`),
    // A stated absence fails plain --strict but not `--strict --allow-stated-absence`.
    ...(ALLOW_STATED_ABSENCE
      ? []
      : diagnostics.statedAbsent.map((s) => `${s.componentId} — ${s.reason}`)),
    // An ambiguous mode is the third way a component ends up outside the map, and the quietest:
    // the reference is there, but nothing says which capture it pairs with, so the component is
    // simply absent. Under --strict that is as much a gap as a missing reference.
    ...diagnostics.ambiguousMode.map(
      (a) =>
        `${a.componentIds.join(", ") || a.subject} — captures ${a.modes
          .map((m) => m || "(unnamed)")
          .join(", ")}, none of them Light, so none pairs with the reference`,
    ),
  ];
  if (missing.length) {
    console.error(
      `::error::--strict: ${missing.length} component(s) reach no design reference — no ` +
        `@CatalogComponent(reference = …), or none their captures can pair with:`,
    );
    for (const line of missing) console.error(`  - ${line}`);
    console.error(
      `A catalog that reproduces a kit has nothing to compare these against — remove them, ` +
        `or drop --strict to publish them unmapped` +
        (ALLOW_STATED_ABSENCE
          ? `.`
          : `, or pass --allow-stated-absence to accept the ones a noReference explains.`),
    );
    process.exit(1);
  }
}

const serialize = (value) => `${JSON.stringify(value, null, 2)}\n`;
const mapText = serialize(map);
// A component with no variant renders needs no sidecar at all. Writing an empty one would put a
// file in the repo whose only content is the assertion that it has nothing to say.
const wantsVariants = variants.components.length > 0;
const variantsText = serialize(variants);

let drifted = false;
function reconcile(file, text, wanted) {
  const exists = fs.existsSync(file);
  if (CHECK) {
    const current = exists ? fs.readFileSync(file, "utf8") : null;
    const expected = wanted ? text : null;
    if (current !== expected) {
      drifted = true;
      const what = !wanted && exists ? "is stale and should be removed" : "is out of date";
      console.error(`::error::${file} ${what} — regenerate with \`compose-design-map\`.`);
    }
    return;
  }
  if (!wanted) {
    if (exists) {
      fs.rmSync(file);
      console.log(`Removed ${file} (no variant renders declare an axis).`);
    }
    return;
  }
  fs.mkdirSync(path.dirname(path.resolve(file)), { recursive: true });
  fs.writeFileSync(file, text);
}

reconcile(OUT, mapText, true);
reconcile(VARIANTS_OUT, variantsText, wantsVariants);

if (!CHECK) {
  console.log(
    `Wrote ${OUT}: ${map.components.length} mapped component(s), ` +
      `${diagnostics.withSet} naming their component set.`,
  );
  if (wantsVariants) {
    console.log(
      `Wrote ${VARIANTS_OUT}: ${diagnostics.variantRenders} variant render(s) across ` +
        `${variants.components.length} component(s), awaiting a kit resolver.`,
    );
  }
}

if (diagnostics.statedAbsent.length) {
  console.log(
    `\n${diagnostics.statedAbsent.length} component(s) have no reference for a stated reason — ` +
      `the kit has nothing live to point at, which is a fact about the kit rather than a gap in ` +
      `this catalog:`,
  );
  for (const s of diagnostics.statedAbsent) {
    console.log(`  - ${s.componentId} — ${s.reason}`);
  }
}

if (diagnostics.statedAbsentCells?.length) {
  console.log(
    `\n${diagnostics.statedAbsentCells.length} folded cell(s) have no design-kit node for a ` +
      `stated reason — these are valid renders, not unresolved variant declarations:`,
  );
  for (const cell of diagnostics.statedAbsentCells) {
    console.log(`  - ${cell.componentId} — ${cell.reason}`);
  }
}

if (diagnostics.unplacedDeclarations?.length) {
  console.log(
    `\n${diagnostics.unplacedDeclarations.length} variant(s) name a kit axis or value that could ` +
      `not be placed: the annotation carries one kitAxis/kitValue and the variant turns more than ` +
      `one knob, so which knob the axis names is undeclared. Split the cell, or drop the kit ` +
      `names and let the resolver match on the knob's own spelling:`,
  );
  for (const miss of diagnostics.unplacedDeclarations) {
    const named = [
      miss.kitAxis ? `kitAxis = "${miss.kitAxis}"` : null,
      miss.kitValue ? `kitValue = "${miss.kitValue}"` : null,
    ]
      .filter(Boolean)
      .join(", ");
    console.log(`  - ${miss.previewId} — ${named} against ${miss.seeds.join(", ")}`);
  }
}

if (diagnostics.ambiguousMode?.length) {
  console.log(
    `\n${diagnostics.ambiguousMode.length} composable(s) publish several capture modes with no ` +
      `Light among them, so which one the reference pairs with is undeclared, and they were ` +
      `skipped. Rendering a single mode makes it the one that pairs; naming one of them "Light" ` +
      `picks it explicitly:`,
  );
  for (const a of diagnostics.ambiguousMode) {
    const who = a.componentIds.join(", ") || a.subject;
    console.log(`  - ${who} — ${a.modes.map((m) => m || "(unnamed)").join(", ")}`);
  }
}

if (diagnostics.unmapped.length) {
  console.log(
    `\n${diagnostics.unmapped.length} component(s) carry neither ` +
      `@CatalogComponent(reference = …) nor a noReference explaining why, and were skipped:`,
  );
  for (const id of diagnostics.unmapped) console.log(`  - ${id}`);
}

if (drifted) process.exit(1);
