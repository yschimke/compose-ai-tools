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
 * Map previewId → svg for **every** preview that carried a `compose/figma-svg` — no per-function
 * collapse, no light preference. The bundle carries one vector per rendered preview (dark, locale
 * and size included: the Kotlin-side `figmaSvgById` is keyed by preview id with no theme gating),
 * so this is the raw carry that {@link figmaSvgByFunction} above narrows down to one sticker per
 * component. Used for the per-variant emit, which mirrors the raster set 1:1.
 */
export function figmaSvgById(bundle) {
  const out = new Map();
  for (const preview of bundle.previews ?? []) {
    const bytes = bundle.entries?.[`previews/${preview.id}.figma.svg`];
    if (!bytes) continue;
    const svg = new TextDecoder().decode(bytes);
    if (svg.includes("<svg")) out.set(preview.id, svg);
  }
  return out;
}

/**
 * Fold {@link figmaSvgById} across several bundles, later bundles winning — the by-id counterpart of
 * {@link figmaSvgByFunctions}, and there for the same reason: an `--extra-renders`-only preview (a
 * screen rendered from a second CMP-desktop module) exists in ONLY the supplementary bundle, so a
 * primary-only read emits no per-variant vector for it at all — which would gut this feature for
 * exactly the catalogs that lean on `--extra-renders`. Preview ids are unique per bundle, so the
 * "later wins" tie-break only fires when the extra bundle re-renders the same preview — precisely
 * where the extra render is meant to win, matching the candidate fold. Falsy bundles are skipped.
 */
export function figmaSvgByIds(bundles) {
  const out = new Map();
  for (const bundle of bundles) {
    if (!bundle) continue;
    for (const [id, svg] of figmaSvgById(bundle)) out.set(id, svg);
  }
  return out;
}

/**
 * The per-variant vector path for a manifest image path: `images/<slug>/<variant>.png` →
 * `figma/<slug>/<variant>.svg`. Deriving the vector's name from the image's own path (rather than
 * re-deriving one from theme/size/props) is what keeps the two sets 1:1 and reuses the export
 * engine's already-collision-safe `imagePath` naming — a vector always sits at its PNG's path with
 * `images/` → `figma/` and `.png` → `.svg`.
 *
 * Returns null for anything that isn't a plain `images/<slug>/<variant>.png`:
 *   - a non-`images/` or non-`.png` path has no raster to mirror;
 *   - empty / `.` / `..` / absolute / backslash segments are rejected so a hostile manifest can't
 *     write outside `figma/`;
 *   - a *flat* `images/<slug>.png` is rejected too: it would map onto `figma/<slug>.svg`, the
 *     back-compat per-component vector this emit must leave untouched. Every path the export engine
 *     writes carries the component subdir (see `catalogPreviewId`, which flattens exactly one `/`),
 *     so this only ever rejects a malformed manifest.
 */
export function figmaVariantSvgPath(imagePath) {
  if (typeof imagePath !== "string") return null;
  if (!imagePath.startsWith("images/") || !imagePath.endsWith(".png")) return null;
  const rest = imagePath.slice("images/".length, -".png".length);
  if (!rest || rest.startsWith("/") || rest.includes("\\")) return null;
  const segments = rest.split("/");
  if (segments.length < 2) return null;
  if (segments.some((s) => s === "" || s === "." || s === "..")) return null;
  return `figma/${rest}.svg`;
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
 * Rewrite a hybrid SVG's relative `figma-raster/<node>.png` `<image>` hrefs into a sibling directory
 * named after [key], so the crops resolve next to the SVG that references them and never collide
 * with another vector's crops (any two stickers can each carry a `node-3.png`).
 *
 * [key] is whatever makes the emitted vector unique **within its own directory**:
 *   - the component slug for the back-compat `figma/<slug>.svg`
 *     → `figma/<slug>.figma-raster/<node>.png`
 *   - the variant basename for a per-variant `figma/<slug>/<variant>.svg`
 *     → `figma/<slug>/<variant>.figma-raster/<node>.png`
 *
 * Keying the per-variant crops on the slug would have a component's light and dark vectors overwrite
 * each other's `<node>.png`, since both live in the same `figma/<slug>/` dir — hence the basename.
 * A no-op for a vector-only SVG.
 */
export function rewriteRasterHrefs(svg, key) {
  return svg.replaceAll("figma-raster/", `${key}.figma-raster/`);
}
