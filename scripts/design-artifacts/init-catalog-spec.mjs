#!/usr/bin/env node
// Scaffold a starter catalog.spec.json from the @Preview functions a module
// declares — one flat "Components" group, every discovered preview a component
// with an empty caption to fill in. A starting point to edit and regroup, not a
// finished spec; run validate-catalog-spec.mjs after editing.
//
//   node init-catalog-spec.mjs --module :app --system meshcore-mobile --title "MeshCore Mobile"
//   node init-catalog-spec.mjs --src app/src --system demo --title Demo --out catalog.spec.json
//
// Refuses to overwrite an existing --out unless --force.

import { writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { parseArgs } from "node:util";

import { discoverPreviews, buildSkeletonSpec } from "./catalog-spec.mjs";
import { moduleToDir, collectKotlinSources } from "./catalog-spec-io.mjs";

const SCHEMA_REF =
  "https://raw.githubusercontent.com/yschimke/compose-ai-tools/main/scripts/design-artifacts/catalog.spec.schema.json";

const { values } = parseArgs({
  options: {
    // Gradle module path (`:app`, `samples:design-catalog-m3`) — its dir is derived.
    module: { type: "string" },
    // Explicit source roots to scan (repeatable). Use when there's no clean
    // Gradle-path→dir mapping.
    src: { type: "string", multiple: true },
    system: { type: "string" },
    title: { type: "string" },
    out: { type: "string", default: "catalog.spec.json" },
    modes: { type: "string", default: "light,dark" },
    "preview-annotation": { type: "string", multiple: true },
    force: { type: "boolean", default: false },
    help: { type: "boolean", default: false },
  },
});

if (values.help || (!values.module && !(values.src && values.src.length))) {
  console.log(
    "usage: init-catalog-spec --module <:gradlePath> | --src <dir>… [--system <slug>] [--title <name>] [--out catalog.spec.json] [--modes light,dark] [--preview-annotation <Name>…] [--force]",
  );
  process.exit(values.help ? 0 : 1);
}

if (existsSync(values.out) && !values.force) {
  console.error(`init-catalog-spec: ${values.out} already exists — pass --force to overwrite.`);
  process.exit(1);
}

const dirs = values.src && values.src.length ? values.src : [moduleToDir(values.module)];
const sources = await collectKotlinSources(dirs);
if (sources.length === 0) {
  console.error(
    `init-catalog-spec: no .kt files under ${dirs.join(", ")}. Pass --src <dir> pointing at the module's source root.`,
  );
  process.exit(1);
}

const { previews: discovered, annotations, pngLess } = discoverPreviews(sources, {
  extraAnnotations: values["preview-annotation"] ?? [],
});
// PNG-less previews (@AnimatedPreview / @FocusedPreview(gif = true) / LONG-GIF-only
// @ScrollingPreview) render no static sticker, so the catalog export drops them and
// the completeness gate reports them missing — scaffolding them in would produce a
// spec that fails validation. See catalog-spec.mjs `rendersStaticPng`.
const skipped = new Set(pngLess);
const previews = discovered.filter((p) => !skipped.has(p));
if (previews.length === 0) {
  console.error(
    skipped.size > 0
      ? `init-catalog-spec: every @Preview function under ${dirs.join(", ")} renders only an ` +
          `animated GIF / scroll data product (${[...skipped].join(", ")}) — none can be a catalog ` +
          `entry. Add a static @Preview sibling for the components you want catalogued.`
      : `init-catalog-spec: found no @Preview functions under ${dirs.join(", ")}. ` +
          `If your previews use an imported multipreview annotation, pass it via --preview-annotation.`,
  );
  process.exit(1);
}

const spec = buildSkeletonSpec({
  system: values.system,
  title: values.title,
  module: values.module,
  modes: values.modes.split(",").map((s) => s.trim()).filter(Boolean),
  previews,
  schema: SCHEMA_REF,
});

await writeFile(values.out, JSON.stringify(spec, null, 2) + "\n", "utf8");

console.error(
  `Wrote ${values.out}: ${previews.length} component(s) in one "Components" group ` +
    `(recognised preview annotations: ${annotations.join(", ")}).\n` +
    (skipped.size > 0
      ? `Skipped ${skipped.size} PNG-less preview(s) — animated GIF / scroll data products the ` +
        `catalog export can't join: ${[...skipped].join(", ")}.\n`
      : "") +
    `Next: group/caption them, add variants, then \`node validate-catalog-spec.mjs --spec ${values.out}\`.`,
);
