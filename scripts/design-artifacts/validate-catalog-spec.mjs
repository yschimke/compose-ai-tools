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

import { readFile, writeFile } from "node:fs/promises";
import { parseArgs } from "node:util";

import {
  discoverComponentIds,
  discoverPreviews,
  hasCatalogAnnotations,
  validateSpec,
} from "./catalog-spec.mjs";
import { deferralPlan } from "./catalog-priority.mjs";
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
    // Whether the publish this spec feeds has a live path (a carried live bundle or a buildable
    // source) the serve host can re-render deferred entries from. `--no-live-bundle` rejects every
    // `priority: "deferred"` here instead of at the end of the render (issue #2950). Omitted stays
    // lenient — the pre-flight can't see how the publish will be invoked. Two flags rather than one
    // negatable boolean because `parseArgs` has no `--no-` negation: an unknown `--no-live-bundle`
    // would throw instead of meaning "false".
    "live-bundle": { type: "boolean", default: false },
    "no-live-bundle": { type: "boolean", default: false },
    // Write the `--preview` / `-PcomposePreview.filter` patterns that render just this catalog's
    // required entries to <path> (comma-separated, no trailing newline). Empty file when the spec
    // defers no entry, which means "render everything" — so a caller can pass the contents straight
    // through unconditionally.
    "render-filter-out": { type: "string" },
    json: { type: "boolean", default: false },
    help: { type: "boolean", default: false },
  },
});

if (values.help) {
  console.log(
    "usage: validate-catalog-spec --spec <catalog.spec.json> [--module-dir <dir> | --src <dir>…] " +
      "[--preview-annotation <Name>…] [--no-scan] [--live-bundle|--no-live-bundle] " +
      "[--render-filter-out <file>] [--json]",
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
let knownComponentIds = null;
let pngLessPreviews = [];
let annotatedInventory = undefined;
let scannedDirs = [];
if (!values["no-scan"]) {
  scannedDirs = resolveSourceDirs({ srcDirs, spec, specPath: values.spec });
  if (scannedDirs.length > 0) {
    const sources = await collectKotlinSources(scannedDirs);
    const { previews, pngLess } = discoverPreviews(sources, {
      extraAnnotations: values["preview-annotation"] ?? [],
    });
    knownPreviews = previews;
    pngLessPreviews = pngLess;
    // The annotated componentIds travel with the preview names: `display.hero` names a componentId,
    // and for a cover-sheet spec (no `groups`) the annotations are the only place those ids exist.
    // Scanning the module but passing only `knownPreviews` would enable hero validation against
    // half the candidate set and reject every annotation-declared hero.
    knownComponentIds = discoverComponentIds(sources);
    // Only meaningful when a module was scanned; leaves `annotatedInventory` undefined (lenient) on
    // the structural-only path so a no-groups spec isn't wrongly rejected without source access.
    annotatedInventory = hasCatalogAnnotations(sources);
  }
}

// `--live-bundle` / `--no-live-bundle` are mutually exclusive; leaving both off keeps the check
// lenient (undefined), which is what every caller that doesn't know the publish flags wants.
if (values["live-bundle"] && values["no-live-bundle"]) {
  fail("--live-bundle and --no-live-bundle are mutually exclusive");
}
const liveBundle = values["live-bundle"] ? true : values["no-live-bundle"] ? false : undefined;

const { errors, warnings } = validateSpec(spec, {
  ...(knownPreviews ? { knownPreviews, pngLessPreviews } : {}),
  ...(knownComponentIds ? { knownComponentIds } : {}),
  ...(annotatedInventory !== undefined ? { annotatedInventory } : {}),
  ...(liveBundle !== undefined ? { liveBundle } : {}),
});

// The render filter this spec's priorities imply, for the caller's render step. Written even when
// validation fails so a caller that inspects the file isn't left with a stale one from a prior run;
// empty content means "render everything", the behaviour of every spec that defers nothing.
const plan = deferralPlan(spec);
if (values["render-filter-out"]) {
  await writeFile(values["render-filter-out"], plan.renderFilter.join(","), "utf8");
}

if (values.json) {
  console.log(
    JSON.stringify(
      {
        spec: values.spec,
        scannedDirs,
        discoveredPreviews: knownPreviews ? knownPreviews.length : null,
        pngLessPreviews,
        priority: plan,
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
      `Scanned ${scannedDirs.length} source dir(s); discovered ${knownPreviews.length} @Preview function(s)` +
        (pngLessPreviews.length > 0
          ? `, ${pngLessPreviews.length} of them PNG-less (not catalogable): ${pngLessPreviews.join(", ")}.`
          : "."),
    );
  } else {
    console.log(
      "No module scanned — structural checks only. Pass --module-dir/--src (or set spec.module) to resolve preview names.",
    );
  }
  if (plan.defersAnything) {
    console.log(
      `Render priority: ${plan.entries} deferred entry/entries, ${plan.variants} deferred ` +
        `variant(s), deferred mode(s): ${plan.modes.length > 0 ? plan.modes.join(", ") : "none"}.` +
        (plan.renderFilter.length > 0
          ? ` Render filter: ${plan.renderFilter.length} required @Preview function(s).`
          : " No @Preview function is wholly deferred, so the render set is unchanged."),
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
