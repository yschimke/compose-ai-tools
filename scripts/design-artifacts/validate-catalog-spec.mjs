#!/usr/bin/env node
// Validate a catalog.spec.json — structurally, and (when a module is resolvable)
// by resolving every `preview` against the @Preview functions actually declared
// in the module's Kotlin source. A fast, build-free pre-flight for the ~90-minute
// Design Artifacts render: catches a mistyped/renamed `preview` here instead of as
// a late "missing" entry that trips the completeness gate.
//
//   node validate-catalog-spec.mjs --spec catalog.spec.json
//   node validate-catalog-spec.mjs --spec catalog.spec.json --module-dir app
//   node validate-catalog-spec.mjs --spec catalog.spec.json --src app/src --src lib/src
//
// Exit 0 when there are no errors (warnings don't fail); 1 on errors or bad args.

import { readFile } from "node:fs/promises";
import { parseArgs } from "node:util";

import { discoverPreviews, validateSpec } from "./catalog-spec.mjs";
import { resolveSourceDirs, collectKotlinSources } from "./catalog-spec-io.mjs";

const { values } = parseArgs({
  options: {
    spec: { type: "string", default: "catalog.spec.json" },
    // Explicit source roots to scan (repeatable). Overrides module derivation.
    src: { type: "string", multiple: true },
    // The module's directory, when it can't be derived from spec.module.
    "module-dir": { type: "string" },
    // Extra multipreview annotation simple-names declared in another (imported)
    // module, which a source-only scan can't see meta-annotated (repeatable).
    "preview-annotation": { type: "string", multiple: true },
    // Skip Kotlin discovery; run structural checks only.
    "no-scan": { type: "boolean", default: false },
    json: { type: "boolean", default: false },
    help: { type: "boolean", default: false },
  },
});

if (values.help) {
  console.log(
    "usage: validate-catalog-spec --spec <catalog.spec.json> [--module-dir <dir> | --src <dir>…] [--preview-annotation <Name>…] [--no-scan] [--json]",
  );
  process.exit(0);
}

let spec;
try {
  spec = JSON.parse(await readFile(values.spec, "utf8"));
} catch (err) {
  fail(`could not read/parse ${values.spec}: ${err.message}`);
}

const srcDirs = values["module-dir"] ? [values["module-dir"], ...(values.src ?? [])] : values.src;

let knownPreviews = null;
let scannedDirs = [];
if (!values["no-scan"]) {
  scannedDirs = resolveSourceDirs({ srcDirs, spec, specPath: values.spec });
  if (scannedDirs.length > 0) {
    const sources = await collectKotlinSources(scannedDirs);
    const { previews } = discoverPreviews(sources, {
      extraAnnotations: values["preview-annotation"] ?? [],
    });
    knownPreviews = previews;
  }
}

const { errors, warnings } = validateSpec(spec, knownPreviews ? { knownPreviews } : {});

if (values.json) {
  console.log(
    JSON.stringify(
      {
        spec: values.spec,
        scannedDirs,
        discoveredPreviews: knownPreviews ? knownPreviews.length : null,
        errors,
        warnings,
        ok: errors.length === 0,
      },
      null,
      2,
    ),
  );
} else {
  if (knownPreviews) {
    console.log(
      `Scanned ${scannedDirs.length} source dir(s); discovered ${knownPreviews.length} @Preview function(s).`,
    );
  } else {
    console.log(
      "No module scanned — structural checks only. Pass --module-dir/--src (or set spec.module) to resolve preview names.",
    );
  }
  for (const w of warnings) console.log(`  warning: ${w}`);
  for (const e of errors) console.log(`  error:   ${e}`);
  console.log(
    errors.length === 0
      ? `OK — ${warnings.length} warning(s), 0 errors.`
      : `FAILED — ${errors.length} error(s), ${warnings.length} warning(s).`,
  );
}

process.exit(errors.length === 0 ? 0 : 1);

function fail(msg) {
  console.error(`validate-catalog-spec: ${msg}`);
  process.exit(1);
}
