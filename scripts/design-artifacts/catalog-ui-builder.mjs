/**
 * Publish the render bundle's generated **builder catalog** (`ui-builder.json`) beside
 * `catalog.json` and `components.json`.
 *
 * The Gradle plugin's discovery task generates `ui-builder.json` from three inputs the catalog
 * repository owns — the discovered component record, the `catalog.spec.json` cover sheet, and the
 * authored `ui-builder.policy.json` — and packs it into every bundle. It is the file a UI builder
 * reads to know what platform this catalog is, how its screens are framed and written, which shelf
 * each component sits on and how the canvas may draw it: knowledge that used to be written in
 * Kotlin inside the preview server, per catalog, by hand
 * (https://github.com/yschimke/compose-preview-server/blob/main/docs/design/UI_BUILDER_CATALOG_CONTRACT.md).
 *
 * So it is copied out to the branch root and declared on the manifest as `uiBuilderFile`, exactly
 * as `catalog-component-record.mjs` does for `components.json` and for the same reason: a consumer
 * fetches the one file it wants rather than a 15-50 MB polyglot, and a catalog that publishes no
 * live bundle still carries it somewhere a reader can reach.
 *
 * **Most catalogs publish nothing here, and that is the design.** A catalog that authors no
 * `ui-builder.policy.json` produces no `ui-builder.json`, this writes nothing and the manifest says
 * nothing — which is what makes the contract cost zero for every catalog that has not adopted it.
 *
 * Primary bundle only, matching the record: a multi-module catalog's additional bundles each carry
 * their own, and the primary's is the one the catalog's previews were discovered from.
 */
import { mkdir, writeFile } from "node:fs/promises";
import { dirname, join, resolve, sep } from "node:path";

import { TEMPLATE_DIR } from "./ui-builder-policy.mjs";

/** The bundle entry and the published branch path — one name, by construction. */
export const UI_BUILDER_FILE = "ui-builder.json";

/**
 * Whether `bytes` are a builder catalog a consumer will read: a JSON object carrying a string
 * `schema`, a `catalog` object with a non-empty `id`, and a `statusSemantics` object.
 *
 * Structural only, and deliberately not a version check. This file is published once and read by
 * builders of several vintages that the publisher cannot upgrade, so refusing an unknown future
 * `schema` here would report a newer plugin's catalog as a broken bundle — the same rule
 * `parseComponentRecord` follows, and the same rule the readers themselves follow.
 */
export function parseUiBuilderCatalog(bytes) {
  if (!bytes) return null;
  let parsed;
  try {
    parsed = JSON.parse(Buffer.from(bytes).toString("utf8"));
  } catch {
    return null;
  }
  if (
    !parsed ||
    typeof parsed !== "object" ||
    Array.isArray(parsed) ||
    typeof parsed.schema !== "string" ||
    !parsed.catalog ||
    typeof parsed.catalog !== "object" ||
    typeof parsed.catalog.id !== "string" ||
    parsed.catalog.id.length === 0 ||
    !parsed.statusSemantics ||
    typeof parsed.statusSemantics !== "object" ||
    // `typeof [] === "object"`, so the checks above admit an array. The root already refuses one
    // for that reason and these two did not, which let a hand-made bundle carrying
    // `statusSemantics: []` be published and stamped on `catalog.json` as a usable catalog.
    Array.isArray(parsed.statusSemantics) ||
    Array.isArray(parsed.catalog)
  ) {
    return null;
  }
  return parsed;
}

/**
 * Copy the builder catalog out of the bundle `entries` into `<outPath>/ui-builder.json`.
 *
 * Returns what the caller stamps on the manifest and logs — including `diagnostics`, the count of
 * things the generator noticed, because a shelf drawn from data has to be able to say why a
 * component is missing from it or why all of it is placeholders. Returns `null` when the bundle
 * carries no readable builder catalog, in which case nothing is written and the manifest says
 * nothing.
 */
export async function publishUiBuilderCatalog(entries, outPath) {
  const catalog = parseUiBuilderCatalog(entries?.[UI_BUILDER_FILE]);
  if (!catalog) return null;
  const target = join(outPath, UI_BUILDER_FILE);
  await mkdir(dirname(target), { recursive: true });
  await writeFile(target, Buffer.from(entries[UI_BUILDER_FILE]));

  // The template DESIGNS the catalog advertises, carried out with the file that names them.
  //
  // `statusSemantics.templates` holds branch-relative paths like `ui-builder/designs/wear-list.json`
  // and the publish flow snapshots `out/` wholesale, so a path that is not written here is a 404 in
  // the New design chooser — advertised by the catalog, missing from the branch, discovered by
  // whoever clicks it. Copied when the bundle carries the file; reported when it does not, because
  // a catalog naming a template it does not ship is a mistake somebody has to be told about rather
  // than a reason to refuse the whole catalog.
  const templates = Array.isArray(catalog.statusSemantics.templates)
    ? catalog.statusSemantics.templates.filter((path) => typeof path === "string" && path.length > 0)
    : [];
  const publishedTemplates = [];
  const missingTemplates = [];
  const unsafeTemplates = [];
  // Where the bytes are allowed to land. A `templates` entry is a path out of a bundle, and a
  // bundle is not a trusted document — `ui-builder/../../catalog.json` joined to `outPath` writes
  // outside the catalog output, or over another generated artifact. The Gradle side now refuses
  // anything outside `ui-builder/`, but this publisher also reads bundles built before that and
  // bundles handed to it directly, so the containment has to be checked where the write happens
  // rather than trusted from upstream.
  // `<outPath>/ui-builder`, not `<outPath>`. The comment above already said a template must not
  // land "over another generated artifact" and then the check only kept it inside the publication
  // root — so a bundle naming `catalog.json` as a template wrote straight over the manifest
  // `generate-design-catalog.mjs` had just produced, with `components.json` and `ui-builder.json`
  // exposed the same way. Half the sentence was implemented. The designs live in one directory and
  // nothing else in this tree is a design.
  //
  // `TEMPLATE_DIR` is imported rather than spelt again: this rule now exists in three places — the
  // pre-flight, the Gradle lookup and here — and every place that restates it is a place it can
  // drift.
  const root = resolve(outPath, TEMPLATE_DIR);
  for (const path of templates) {
    const templateTarget = resolve(outPath, path);
    // Strictly BENEATH. The directory itself is not a design, so an entry naming it resolves to
    // something no publisher can write and no builder can open.
    if (!templateTarget.startsWith(root + sep)) {
      unsafeTemplates.push(path);
      continue;
    }
    const bytes = entries?.[path];
    if (!bytes) {
      missingTemplates.push(path);
      continue;
    }
    await mkdir(dirname(templateTarget), { recursive: true });
    await writeFile(templateTarget, Buffer.from(bytes));
    publishedTemplates.push(path);
  }

  return {
    path: UI_BUILDER_FILE,
    schema: catalog.schema,
    catalogId: catalog.catalog.id,
    platform: catalog.statusSemantics.platform ?? catalog.catalog.platform ?? null,
    components: Object.keys(catalog.statusSemantics.components ?? {}).length,
    builtins: Object.keys(catalog.statusSemantics.builtins ?? {}).length,
    diagnostics: Array.isArray(catalog.diagnostics) ? catalog.diagnostics.length : 0,
    templates: publishedTemplates,
    missingTemplates,
    unsafeTemplates,
  };
}
