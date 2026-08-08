/**
 * Rescale a captured design-reference layout tree into the published raster's pixel space.
 *
 * `layoutFromNode` (@design-parity/adapter-figma) returns root-relative dp measured off the Figma
 * frame. The raster those annotations are drawn over is fitted to the catalog sticker's dimensions
 * — see png-resample.mjs — so a frame authored at a different size would put every annotation box
 * in the wrong place. Applying the same transform the raster went through keeps each annotation on
 * top of the thing it describes.
 *
 * Split out of emit-design-references.mjs so it can be tested: that script self-executes on import.
 */

/**
 * Rescale a captured layout tree into the published raster's pixel space.
 *
 * `targetWidth` is the width the artwork occupies in the published raster, and `offsetX`/`offsetY`
 * are where it starts. Those default to a raster the artwork fills edge to edge; they carry real
 * values when the reference was letterboxed to keep its proportions, since the annotations then
 * have to move with the artwork rather than with the canvas.
 *
 * Only `bounds` move. `tokens` (the padding / gap / type sizes the annotations quote) stay in the
 * design's own pixels, and the tree's `density` describes exactly those — a spec is not relocated
 * by where its box was drawn, and rescaling it here would leave `density` describing a unit that no
 * longer exists.
 */
export function scaleTree(tree, targetWidth, offsetX = 0, offsetY = 0) {
  const frame = tree?.root?.bounds;
  if (!frame?.width) return undefined;
  const factor = targetWidth / frame.width;
  if (!Number.isFinite(factor) || factor <= 0) return undefined;
  const scaleBounds = (b) =>
    b === undefined
      ? undefined
      : {
          x: Math.round(b.x * factor) + offsetX,
          y: Math.round(b.y * factor) + offsetY,
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
