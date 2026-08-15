/**
 * Write a repo's `design-map.json` from its discovery manifest — the I/O around `design-map.mjs`.
 *
 *     node emit-design-map.mjs [--previews <path>] [--out design-map.json]
 *                              [--variants design-map-variants.json] [--prefix catalog]
 *                              [--check] [--strict]
 *
 * Run `./gradlew :<module>:composePreviewDiscover` first so the manifest exists.
 *
 * ## Two files out
 *
 * `--out` is the design map design-parity reads. `--variants` is the sidecar of **unresolved**
 * variant declarations: which other previews are the same component with knobs turned, and which
 * knobs. Turning those into design nodes needs a design kit's published vocabulary, which this repo
 * does not hold — see the module KDoc in `design-map.mjs` for why the split falls here. A repo with
 * no resolver still gets a valid map of base references from this alone.
 *
 * Both are **outputs**: regenerate rather than edit. `--check` is the CI posture — it regenerates
 * in memory and exits non-zero if either committed file has drifted, without writing.
 *
 * ## Failure posture
 *
 * An unmapped component is reported, never fatal: a catalog is allowed to contain components nobody
 * has mapped yet, and failing the build over one would make adding a component a breaking change.
 * `--strict` turns that report into a non-zero exit for a repo that wants its coverage gated.
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
});

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
      console.error(`::error::${file} ${what} — regenerate with \`node emit-design-map.mjs\`.`);
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

if (diagnostics.unmapped.length) {
  console.log(
    `\n${diagnostics.unmapped.length} component(s) carry neither ` +
      `@CatalogComponent(reference = …) nor a noReference explaining why, and were skipped:`,
  );
  for (const id of diagnostics.unmapped) console.log(`  - ${id}`);
}

if (drifted) process.exit(1);
if (STRICT && diagnostics.unmapped.length) {
  console.error(`::error::--strict: ${diagnostics.unmapped.length} component(s) are unmapped.`);
  process.exit(1);
}
