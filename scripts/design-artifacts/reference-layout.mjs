/**
 * Rescale a captured design-reference layout tree into the published raster's pixel space.
 *
 * `layoutFromNode` (@design-parity/adapter-figma) returns root-relative dp measured off the Figma
 * frame. The raster those annotations are drawn over is resampled to the catalog sticker's exact
 * dimensions — see png-resample.mjs for why "exact" is load-bearing — so a frame authored at a
 * different size would put every annotation box in the wrong place. Scaling by the same ratio the
 * resample used keeps each annotation on top of the thing it describes.
 *
 * Split out of emit-design-references.mjs so it can be tested: that script self-executes on import.
 */

/**
 * Rescale a captured layout tree into the published raster's pixel space.
 *
 * `layoutFromNode` returns root-relative dp off the Figma frame, but the raster this annotates is
 * resampled to the catalog sticker's exact dimensions — so a frame authored at a different size
 * would put every box in the wrong place. Scaling by the same ratio the resample used keeps the
 * annotation on top of the thing it describes.
 */
export function scaleTree(tree, targetWidth) {
  const frame = tree?.root?.bounds;
  if (!frame?.width) return undefined;
  const factor = targetWidth / frame.width;
  if (!Number.isFinite(factor) || factor <= 0) return undefined;
  const scaleBounds = (b) =>
    b === undefined
      ? undefined
      : {
          x: Math.round(b.x * factor),
          y: Math.round(b.y * factor),
          width: Math.round(b.width * factor),
          height: Math.round(b.height * factor),
        };
  const visit = (node) => ({
    ...node,
    bounds: scaleBounds(node.bounds),
    children: (node.children ?? []).map(visit),
  });
  return { ...tree, root: visit(tree.root) };
}
