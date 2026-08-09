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
 * @returns {{ ids: string[], rows: string[], keptByGuard: string[] }} `ids` sorted and unique;
 *   `rows` the deferred mode names to skip as `@PreviewParameter` row labels (see [deferredRowLabels]);
 *   `keptByGuard` names the functions whose every id was mode-deferred and which were therefore left
 *   alone, so the caller can log the (spec-level) misconfiguration instead of hiding it behind a
 *   smaller render.
 */
export function deferredPreviewIds(spec, previews) {
  const referenced = specPreviewFunctions(spec);
  const modes = spec?.modes ?? [];
  const byFunction = new Map();
  // Per referenced function: the ids it produced, and whether any of them is a `@PreviewParameter`
  // preview — the row axis is decided per function, so a module-wide view would answer the wrong
  // question (see [deferredRowLabels]).
  const parameterized = new Set();
  for (const preview of previews ?? []) {
    const id = preview?.id;
    if (typeof id !== "string" || id.length === 0) continue;
    const fn = preview.functionName ?? id;
    if (!referenced.has(fn)) continue;
    const list = byFunction.get(fn) ?? [];
    list.push(id);
    byFunction.set(fn, list);
    if (previewParameterProvider(preview)) parameterized.add(fn);
  }
  // A payload that carries no `params` at all (an older/brief listing) can't answer "is this
  // parameterized?", so fall back to treating every referenced function as a candidate — the same
  // exclusion polarity as everywhere else: a label that matches no row costs nothing.
  const providerFieldAvailable = (previews ?? []).some((p) => p?.params !== undefined);

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
  return {
    ids: [...ids].sort(),
    rows: deferredRowLabels(spec, byFunction, parameterized, providerFieldAvailable),
    keptByGuard: keptByGuard.sort(),
  };
}

/**
 * The deferred modes to hand the render as `@PreviewParameter` **row labels**
 * (`--exclude-preview-row`), for the axis no id can name.
 *
 * When a catalog's palettes come from a provider rather than a multipreview, discovery emits ONE
 * preview per function and the rows only exist inside the render — so a deferred mode leaves no
 * trace in [previews] to exclude by id, and `deferredPreviewIds` above finds nothing to skip. What it
 * *can* do is say which modes are deferred and let the renderer match them against the labels it
 * mints.
 *
 * The selection rule is **per function**, not per module: a deferred mode is emitted when some
 * spec-referenced *parameterized* function has no discovered id carrying it. A module-wide "is this
 * mode visible as an id anywhere?" test looks equivalent and isn't — in a mixed catalog where `A`
 * fans out as `A_Light`/`A_Dark` while `B` gets its palettes from a provider, `dark` is visible on
 * `A`, and suppressing the label on that basis would leave `B`'s Dark row rendering, which is exactly
 * the cost this exists to remove. Conversely a function that IS covered by ids needs no label, and a
 * catalog with no parameterized function at all gets none — the id filter is exact, so the wider tool
 * is only reached for when it's the only one that fits.
 *
 * The residual risk is real but bounded: labels are matched module-wide inside the render, so an
 * unrelated parameterized preview whose row happens to be labelled `Dark` loses that row. Then —
 *  - the renderer never empties a preview's row set, so nothing can render to zero pixels;
 *  - the missing row belongs to a component the spec did NOT defer, so the completeness gate fails
 *    the publish — loudly, rather than thinning the sheet in silence.
 *
 * @param parameterized function names known to carry a `@PreviewParameter` provider.
 * @param providerFieldAvailable false when the payload carries no `params` at all (an older or brief
 *   listing), in which case every referenced function is treated as a candidate rather than none —
 *   the same exclusion polarity as everywhere else, since a label matching no row costs nothing.
 */
function deferredRowLabels(spec, byFunction, parameterized, providerFieldAvailable) {
  const modes = (spec?.modes ?? []).filter((m) => typeof m === "string" && m.length > 0);
  const deferredModes = modes.filter((mode) => modePriority(spec, mode) === DEFERRED);
  if (deferredModes.length === 0) return [];

  const candidates = [...byFunction.entries()].filter(
    ([fn]) => !providerFieldAvailable || parameterized.has(fn),
  );
  return deferredModes
    .filter((mode) =>
      candidates.some(([, fnIds]) => !fnIds.some((id) => modeOfPreviewId(id, modes) === mode)),
    )
    .sort();
}

/**
 * The `@PreviewParameter` provider class a discovered preview declares, or null. Lives on
 * `params.previewParameterProviderClassName` in both payload shapes this module accepts
 * (`compose-preview list --json` and a module's `previews.json`).
 */
function previewParameterProvider(preview) {
  const fqn = preview?.params?.previewParameterProviderClassName;
  return typeof fqn === "string" && fqn.length > 0 ? fqn : null;
}

/**
 * The deferred ids as `--exclude-preview-id` patterns rather than as ids: each `=`-anchored, so it
 * matches that preview and not the fan-out hanging off it.
 *
 * The plain form matches by equality OR substring, and ids are hierarchical, so an unanchored
 * `Switch_Dark` also defers `Switch_Dark_VARIANT_off` — a preview the spec never deferred, whose
 * absence surfaces later as a completeness-gate failure rather than as anything pointing back here.
 * Deferral is an exclusion, and on the exclusion axis over-matching silently deletes work (#3559).
 *
 * Kept separate from the `ids` [deferredPreviewIds] returns because the sharder consumes those as
 * ids — it removes them from the partition by set membership, which an anchored string would miss.
 * Ids stay plain until the moment they become CLI patterns.
 *
 * @param {string[]} ids plain preview ids.
 * @returns {string[]} the same ids, each `=`-anchored.
 */
export function anchoredExclusions(ids) {
  return (ids ?? [])
    .filter((id) => typeof id === "string" && id.length > 0)
    .map((id) => (id.startsWith("=") ? id : `=${id}`));
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
// `node deferred-preview-ids.mjs --spec catalog.spec.json --previews previews.json
//    [--out ids.txt] [--anchored-out patterns.txt] [--rows-out rows.txt]`
// Prints (and optionally writes) the comma-separated exclusion list the render consumes as
// `compose-preview bundle pack --exclude-preview-id` / `-PcomposePreview.idExclude`, and — for a
// mode axis that lives in a `@PreviewParameter` provider rather than in ids — the row labels for
// `--exclude-preview-row` / `-PcomposePreview.rowExclude`. Empty output on either is the normal case
// for a spec that defers no mode, and means "render everything".
//
// Two id shapes, for two consumers: `--out` writes PLAIN ids, which is what `shard-preview-ids.mjs`
// needs (it removes them from the partition by set membership); `--anchored-out` writes the same ids
// `=`-anchored, which is what goes to the CLI. Handing the CLI the plain list defers each id's whole
// fan-out along with it (#3559).
if (import.meta.url === `file://${process.argv[1]}`) {
  const { values } = parseArgs({
    options: {
      spec: { type: "string" },
      previews: { type: "string" },
      out: { type: "string" },
      "anchored-out": { type: "string" },
      "rows-out": { type: "string" },
    },
  });
  if (!values.spec || !values.previews) {
    console.error(
      "usage: deferred-preview-ids.mjs --spec <catalog.spec.json> --previews <list.json> " +
        "[--out <file>] [--anchored-out <file>] [--rows-out <file>]",
    );
    process.exit(2);
  }
  const spec = JSON.parse(readFileSync(values.spec, "utf8"));
  const previews = previewsFromJson(JSON.parse(readFileSync(values.previews, "utf8")));
  const { ids, rows, keptByGuard } = deferredPreviewIds(spec, previews);
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
  if (rows.length > 0) {
    console.error(
      `[${spec.system}] mode deferral: ${rows.length} mode(s) carry no discovered id, so they are ` +
        `passed as @PreviewParameter row label(s) instead: ${rows.join(", ")}`,
    );
  }
  const line = ids.join(",");
  if (values.out) writeFileSync(values.out, line);
  if (values["anchored-out"]) {
    writeFileSync(values["anchored-out"], anchoredExclusions(ids).join(","));
  }
  if (values["rows-out"]) writeFileSync(values["rows-out"], rows.join(","));
  console.log(line);
}
