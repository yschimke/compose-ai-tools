/**
 * Detect stickers paired with a render that isn't theirs.
 *
 * Each catalog image carries the `previewId` of the daemon preview that renders it, and the
 * per-variant `compose/figma-svg` emit keys off exactly that id — so a mis-stamped `previewId` ships
 * one variant's vector against another variant's pixels. The published catalog already carries
 * enough to catch the whole class: an image records the `width`/`height` of the PNG it was built
 * from, so two images stamped with the SAME `previewId` and DIFFERENT dimensions cannot both have
 * come from that one render.
 *
 * That is precisely how the catalog-breakpoint half of #2883 shipped, undetected, for four
 * releases: Jetsnack's `compact` stickers (1082dp-wide renders of the `@Preview("large font",
 * widthDp = 412)` annotations) were all stamped with their function's `_default` previewId, whose
 * own render is 250×105. Eleven components in one catalog paired a wide raster with an intrinsic
 * vector, and nothing in the export said so.
 *
 * Reported rather than thrown: an export that has already rendered everything should still publish,
 * and a catalog can legitimately carry a same-id pair the checker can't distinguish. The driver
 * prints these so a regression is visible in the run log instead of only in a compare score.
 */

/**
 * Images whose `previewId` is shared with another image of different dimensions.
 *
 * @param {Array<{componentId?: string, images?: Array<{previewId?: string, path?: string,
 *   width?: number, height?: number}>}>} components
 * @returns {Array<{componentId: string, previewId: string, renders:
 *   Array<{path: string, width: number, height: number}>}>} one entry per collapsed pairing
 */
export function mismatchedVariantRenders(components) {
  const out = [];
  for (const component of components ?? []) {
    // Handed the PRE-manifest shape, this checker would iterate no images and report nothing —
    // whatever the export emitted. A silent all-clear is the worst way for a checker to fail, and
    // the driver has two component arrays of different shapes in scope (`catalog.components` keeps
    // captures under `variants.ideal`; only the written manifest carries the flattened `images[]`
    // and their `previewId`). Fail loudly on the wrong one instead of passing vacuously.
    if (component?.variants && !Array.isArray(component?.images)) {
      throw new TypeError(
        `mismatchedVariantRenders: component '${component?.componentId ?? "?"}' has no images[] ` +
          `— pass the components of the WRITTEN catalog.json, not the in-memory catalog`,
      );
    }
    const byPreviewId = new Map();
    for (const image of component?.images ?? []) {
      const previewId = image?.previewId;
      // An unbridged image has no vector lane at all — a different gap, counted separately by the
      // driver. Dimensions are optional in the schema; without both there is nothing to compare.
      if (!previewId) continue;
      if (typeof image.width !== "number" || typeof image.height !== "number") continue;
      const list = byPreviewId.get(previewId) ?? [];
      list.push({
        path: String(image.path ?? ""),
        width: image.width,
        height: image.height,
      });
      byPreviewId.set(previewId, list);
    }
    for (const [previewId, renders] of byPreviewId) {
      if (renders.length < 2) continue;
      const distinct = new Set(renders.map((r) => `${r.width}x${r.height}`));
      if (distinct.size < 2) continue;
      out.push({
        componentId: String(component?.componentId ?? ""),
        previewId,
        renders,
      });
    }
  }
  return out;
}

/** One-line summary per collapsed pairing, for the driver's run log. */
export function describeMismatchedRenders(mismatches) {
  return mismatches.map(({ componentId, previewId, renders }) => {
    const dims = renders.map((r) => `${r.width}×${r.height}`).join(" vs ");
    return `${componentId} — ${previewId.split(".").pop()} serves ${dims}`;
  });
}
