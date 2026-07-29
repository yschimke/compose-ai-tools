/**
 * Bundle preview-list helpers for the catalog export.
 *
 * Kept in its own module (not inline in `generate-design-catalog.mjs`) so it can be unit-tested: the
 * generate script runs its whole pipeline at import (top-level `await`), so importing it from a test
 * would execute the CLI.
 */

/**
 * Build the bundle view handed to `@design-parity/candidate`'s `bundleToCandidates`, keeping only
 * previews that carry a static `previews/<id>.png` raster. Returns `{ bundle, dropped }` where
 * `bundle` is a shallow clone with a filtered `previews` array (the original is left untouched) and
 * `dropped` is the ids removed.
 *
 * `bundleToCandidates` represents every preview as a PNG sticker and throws `InvalidBundleError` on a
 * missing `previews/<id>.png`, so a preview whose only artifact is non-raster would fail the whole
 * catalog export. Two kinds of preview have no PNG:
 *   - animated captures — a `@ScrollingPreview` `ScrollMode.GIF` preview emits only
 *     `previews/<id>.gif` (e.g. the wear catalog's `CardScalingScrollGif`); it isn't an importable
 *     static sticker, so it's simply excluded from the candidate join.
 *   - catalog-token sheets — `@ColorCatalog` / `@TypographyCatalog` / `@ThemeCatalog` metadata
 *     previews are rendered PNG-less by design, but their `previews/<id>.catalog.json` sidecars are
 *     read separately via `catalogTokensFromBundle(bundle)` to export `themeTokens`.
 *
 * Because this returns a **clone** and never mutates `bundle.previews`, those catalog-token sheets
 * stay in the original bundle for the token pass (and layout wireframes / fonts manifest, which
 * iterate the full list) — only the candidate join sees the filtered view.
 */
/**
 * `functionName -> [daemon preview id, …]` across every bundle, in bundle order. Falsy entries are
 * skipped so callers can pass `[bundle, extraBundle]` with no supplementary render; the first
 * bundle to claim an id wins, matching the primary-is-authoritative fold everywhere else.
 *
 * The live lane for a DEFERRED catalog entry (issue #2950) keys off this: its preview was never
 * rasterised, so it has no `image.previewId` to bridge from — but it is still listed in the
 * bundle's `previews.json`, because the bundle task carries every selected preview and simply omits
 * the PNG for one that didn't render. That listing is what makes a deferred entry addressable on
 * the serve host instead of lost.
 */
export function daemonPreviewIdsByFunction(bundles) {
  const out = new Map();
  const seen = new Set();
  for (const bundle of Array.isArray(bundles) ? bundles : [bundles]) {
    if (!bundle) continue;
    for (const preview of bundle.previews ?? []) {
      if (seen.has(preview.id)) continue;
      seen.add(preview.id);
      const fn = preview.functionName ?? preview.id;
      out.set(fn, [...(out.get(fn) ?? []), preview.id]);
    }
  }
  return out;
}

export function candidatePreviewBundle(bundle) {
  const dropped = [];
  const previews = (bundle.previews ?? []).filter((preview) => {
    if (bundle.entries?.[`previews/${preview.id}.png`]) return true;
    dropped.push(preview.id);
    return false;
  });
  return { bundle: { ...bundle, previews }, dropped };
}
