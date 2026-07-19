/**
 * Helpers for shipping the layered `compose/figma-svg` export per sticker on the design-catalog
 * delivery branch. The SVG bytes are produced Kotlin-side and carried in the bundle
 * (`previews/<id>.figma.svg`); a **hybrid** sticker (opaque Image/Icon/Canvas node) also carries
 * `previews/<id>.figma-raster/<node>.png` crops its `<image>` layers reference. These readers pull
 * both out of the bundle so the driver can copy them onto the branch verbatim (no re-render).
 *
 * Extracted from `generate-design-catalog.mjs` so the carry + href-rewrite logic is unit-testable
 * without running the whole export (which needs the private `@design-parity/catalog-export`).
 */

/**
 * Map componentFunction → `{ id, svg }` for each preview that carried a `compose/figma-svg`. Prefer
 * the light variant so a single deterministic sticker is emitted per component; the `id` is kept so
 * the caller can find the sticker's hybrid `figma-raster/` crops.
 */
export function figmaSvgByFunction(bundle) {
  const out = new Map();
  const prefer = (id) => /(_|\b)light$/i.test(id);
  for (const preview of bundle.previews ?? []) {
    const bytes = bundle.entries?.[`previews/${preview.id}.figma.svg`];
    if (!bytes) continue;
    const fn = preview.functionName ?? preview.id;
    if (out.has(fn) && !prefer(preview.id)) continue;
    const svg = new TextDecoder().decode(bytes);
    if (svg.includes("<svg")) out.set(fn, { id: preview.id, svg });
  }
  return out;
}

/**
 * Fold {@link figmaSvgByFunction} across several bundles, later bundles overriding earlier ones for
 * the same function name — matching generate-design-catalog's `--extra-renders` candidate fold, where
 * the supplementary bundle wins. Crucially a function present in ONLY a later bundle (an
 * `--extra-renders`-only sticker, e.g. a screen rendered from a second CMP-desktop module) is *added*,
 * so an extra render carries its editable vector into the catalog too — not just its raster PNG.
 * Reading only the primary bundle silently dropped those vectors. Falsy bundles are skipped.
 */
export function figmaSvgByFunctions(bundles) {
  const out = new Map();
  for (const bundle of bundles) {
    if (!bundle) continue;
    for (const [fn, entry] of figmaSvgByFunction(bundle)) out.set(fn, entry);
  }
  return out;
}

/**
 * The hybrid raster crops carried for preview [id] as `previews/<id>.figma-raster/<node>.png`.
 * Returns a Map name→bytes; empty for the common vector-only sticker.
 */
export function figmaRastersForId(bundle, id) {
  const out = new Map();
  const prefix = `previews/${id}.figma-raster/`;
  for (const [path, bytes] of Object.entries(bundle.entries ?? {})) {
    if (!path.startsWith(prefix)) continue;
    const name = path.slice(prefix.length);
    // Zip-slip guard: the driver writes each crop to figma/<slug>.figma-raster/<name>, so a hostile
    // bundle carrying `..`/absolute/nested crop names could escape that dir. The daemon only ever
    // emits a bare `<node>.png` filename (see FigmaSvgModel.defaultRasterHref), so accept exactly
    // that — reject anything with a path separator or a `.`/`..` segment.
    if (!name || name === "." || name === ".." || /[\\/]/.test(name)) continue;
    out.set(name, bytes);
  }
  return out;
}

/**
 * Rewrite a hybrid SVG's relative `figma-raster/<node>.png` `<image>` hrefs to a per-slug directory
 * (`<slug>.figma-raster/<node>.png`) so they resolve next to `figma/<slug>.svg` and never collide
 * across components (two stickers can each have a `node-3.png`). A no-op for a vector-only SVG.
 */
export function rewriteRasterHrefs(svg, componentSlug) {
  return svg.replaceAll("figma-raster/", `${componentSlug}.figma-raster/`);
}
