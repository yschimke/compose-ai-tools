/**
 * Bundle preview-list helpers for the catalog export.
 *
 * Kept in its own module (not inline in `generate-design-catalog.mjs`) so it can be unit-tested: the
 * generate script runs its whole pipeline at import (top-level `await`), so importing it from a test
 * would execute the CLI.
 */

/**
 * Drop previews that carry no static `previews/<id>.png` raster from [bundle] (mutating
 * `bundle.previews` in place), returning the ids removed.
 *
 * These are animated / non-raster captures — a `@ScrollingPreview` `ScrollMode.GIF` scroll preview
 * emits only `previews/<id>.gif`, no PNG. The `@design-parity/candidate` loader
 * (`bundleToCandidates`) represents every preview as a PNG sticker and throws `InvalidBundleError`
 * on a missing `previews/<id>.png`, so a single animated preview would otherwise fail the entire
 * catalog export. Filtering `bundle.previews` here also keeps every downstream consumer (layout
 * wireframes, fonts manifest) consistent — they all iterate the same list.
 */
export function dropNonRasterPreviews(bundle) {
  const dropped = [];
  bundle.previews = (bundle.previews ?? []).filter((preview) => {
    if (bundle.entries?.[`previews/${preview.id}.png`]) return true;
    dropped.push(preview.id);
    return false;
  });
  return dropped;
}
