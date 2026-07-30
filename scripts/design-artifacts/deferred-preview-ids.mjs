/**
 * The **preview ids** a catalog's render may skip because `modePriority` defers their mode
 * (issue #2966).
 *
 * `renderFilterPatterns` (issue #2959) can drop a wholly-deferred `@Preview` *function* from the
 * render, but the palette fan-out `modePriority` thins lives inside one function — a `@CatalogModes`
 * member, or one of several `@Preview` annotations — where every combination is its own discovered
 * preview with a distinct `id` and the same `functionName`. Only an id-level filter reaches those, so
 * the render savings for the mode axis (measured at −59% of renders on a nine-theme catalog, vs −37%
 * for deferring variants) needs exact ids, which only discovery knows. Hence the shape of this
 * module: the caller runs `compose-preview list --json` (which runs `composePreviewDiscover`) and
 * feeds the result in.
 *
 * Two shapes it deliberately can't thin, both because the ids don't exist at this point:
 *  - a mode axis supplied by a **`@PreviewParameter` provider** — discovery emits ONE preview for the
 *    parameterized function and the renderer expands the rows into `<stem>_<label>` outputs later, so
 *    there is no per-row id to exclude (such a catalog still gets the entry-level filter);
 *  - an **Android/Robolectric** module — its `composePreviewRender` is a `Test` task reading the
 *    manifest directly, and honours neither this filter nor `--preview`. The semantics-capture saving
 *    (CLI-driven) does land there; the raster saving does not, until that path grows the same filter.
 *
 * Two invariants make this safe to hand to a render:
 *
 *  1. **Never leave a function with nothing to render.** A function whose every id resolves to a
 *     deferred mode keeps all of them. That mirrors "the primary sticker is never deferrable by mode"
 *     on the publish side — a component with no baked pixels at all is a spec misconfiguration the
 *     completeness gate is meant to catch, not something to silently turn into a skipped render.
 *  2. **Only functions the spec actually references.** An id belonging to no spec entry is left
 *     alone: the sheet may not catalogue it, but something else in the module (the wireframe pass, a
 *     token sheet) may still need its pixels.
 *
 * Exclusion polarity throughout: the caller names what must NOT render, so a spec that has drifted
 * ahead of the code renders too much (wasted time) rather than too little (a missing sticker).
 *
 * Pure and dependency-free apart from its sibling `catalog-priority.mjs` (node built-ins only, no
 * `@design-parity/*`) so it unit-tests without an `npm ci`. The `--spec`/`--previews` CLI wrapper at
 * the bottom only runs when this file is executed directly.
 */

import { readFileSync, writeFileSync } from "node:fs";
import { parseArgs } from "node:util";
import { DEFERRED, modeOfPreviewId, modePriority } from "./catalog-priority.mjs";

/** Every `@Preview` function name a spec references, from components and their variants. */
export function specPreviewFunctions(spec) {
  const out = new Set();
  for (const group of spec?.groups ?? []) {
    for (const component of group?.components ?? []) {
      if (typeof component?.preview === "string") out.add(component.preview);
      for (const variant of component?.variants ?? []) {
        if (typeof variant?.preview === "string") out.add(variant.preview);
      }
    }
  }
  return out;
}

/**
 * The ids whose render this spec can skip.
 *
 * @param {object} spec parsed `catalog.spec.json`.
 * @param {Array<{id: string, functionName?: string}>} previews discovered previews (from
 *   `compose-preview list --json`, or a `previews.json` manifest — both carry `id` + `functionName`).
 * @returns {{ ids: string[], keptByGuard: string[] }} `ids` sorted and unique; `keptByGuard` names
 *   the functions whose every id was mode-deferred and which were therefore left alone, so the caller
 *   can log the (spec-level) misconfiguration instead of hiding it behind a smaller render.
 */
export function deferredPreviewIds(spec, previews) {
  const referenced = specPreviewFunctions(spec);
  const modes = spec?.modes ?? [];
  const byFunction = new Map();
  for (const preview of previews ?? []) {
    const id = preview?.id;
    if (typeof id !== "string" || id.length === 0) continue;
    const fn = preview.functionName ?? id;
    if (!referenced.has(fn)) continue;
    const list = byFunction.get(fn) ?? [];
    list.push(id);
    byFunction.set(fn, list);
  }

  const ids = new Set();
  const keptByGuard = [];
  for (const [fn, fnIds] of byFunction) {
    const deferredIds = fnIds.filter(
      (id) => modePriority(spec, modeOfPreviewId(id, modes)) === DEFERRED,
    );
    if (deferredIds.length === 0) continue;
    if (deferredIds.length === fnIds.length) {
      keptByGuard.push(fn);
      continue;
    }
    for (const id of deferredIds) ids.add(id);
  }
  return { ids: [...ids].sort(), keptByGuard: keptByGuard.sort() };
}

/**
 * Pull the preview list out of whatever JSON the caller captured: `compose-preview list --json`
 * (`{ previews: [...] }` or a bare array) or a module's discovery manifest (`previews.json`, also
 * `{ previews: [...] }`). Anything else yields an empty list, which means "exclude nothing".
 */
export function previewsFromJson(parsed) {
  if (Array.isArray(parsed)) return parsed;
  if (Array.isArray(parsed?.previews)) return parsed.previews;
  if (Array.isArray(parsed?.results)) return parsed.results;
  return [];
}

// --- CLI ----------------------------------------------------------------------
// `node deferred-preview-ids.mjs --spec catalog.spec.json --previews previews.json [--out ids.txt]`
// Prints (and optionally writes) the comma-separated exclusion list the render consumes as
// `compose-preview bundle pack --exclude-preview-id` / `-PcomposePreview.idExclude`. Empty output
// is the normal case for a spec that defers no mode, and means "render everything".
if (import.meta.url === `file://${process.argv[1]}`) {
  const { values } = parseArgs({
    options: {
      spec: { type: "string" },
      previews: { type: "string" },
      out: { type: "string" },
    },
  });
  if (!values.spec || !values.previews) {
    console.error(
      "usage: deferred-preview-ids.mjs --spec <catalog.spec.json> --previews <list.json> [--out <file>]",
    );
    process.exit(2);
  }
  const spec = JSON.parse(readFileSync(values.spec, "utf8"));
  const previews = previewsFromJson(JSON.parse(readFileSync(values.previews, "utf8")));
  const { ids, keptByGuard } = deferredPreviewIds(spec, previews);
  for (const fn of keptByGuard) {
    console.error(
      `[${spec.system}] ${fn}: every discovered preview renders a DEFERRED mode — keeping all of ` +
        `them so the component still has pixels. Check \`modePriority\` against the modes this ` +
        `function actually renders.`,
    );
  }
  console.error(
    `[${spec.system}] mode deferral: ${ids.length} preview id(s) excluded from the render` +
      (ids.length > 0 ? ` (${ids.slice(0, 5).join(", ")}${ids.length > 5 ? ", …" : ""})` : ""),
  );
  const line = ids.join(",");
  if (values.out) writeFileSync(values.out, line);
  console.log(line);
}
