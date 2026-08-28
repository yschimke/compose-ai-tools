/**
 * Map design-reference geometry into the published raster's pixel space.
 *
 * `layoutFromNode` (@design-parity/adapter-figma) returns root-relative dp measured off the Figma
 * frame. The raster those annotations are drawn over is fitted to the catalog sticker's dimensions
 * — see png-resample.mjs — so a frame authored at a different size would put every annotation box
 * in the wrong place. Applying the same transform the raster went through keeps each annotation on
 * top of the thing it describes.
 *
 * [scaleTree] does that for the one tree this pipeline captures itself, whose numbers are in the
 * DESIGN frame's units. Geometry captured by somebody else — a reference-side annotation layer an
 * adapter already wrote into `annotations/index.json`, a parity finding's reference-side anchor — is
 * measured in the source raster's own pixels instead, and only the publish step knows what it did to
 * those pixels. [publishTransform] states that as a factor and an offset, and [transformBounds] /
 * [transformAnnotations] carry a box through it, so both halves of a comparison end up describing
 * the same picture (compose-ai-tools#4696).
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

/**
 * The transform the publish step applied to a reference raster, as `{ scaleX, scaleY, offsetX,
 * offsetY }` mapping a source-raster pixel onto the published one — or null when the raster was
 * published exactly as it arrived.
 *
 * `placement` is where the source landed inside the published canvas: `png-resample.mjs` returns it
 * from [fitRgba] and [placeRgba], and a raster that was merely resampled to close a rounding gap
 * "landed" at the target's full size with no offset. Null for the identity so a caller can carry it
 * only where it means something — a reference nothing moved must publish exactly the manifests it
 * publishes today.
 *
 * Scales are trimmed to six decimals: a factor is a ratio of two pixel counts and its tail is
 * float noise, which would otherwise be written into every manifest that carries one.
 */
export function publishTransform(sourceWidth, sourceHeight, placement) {
  if (!(sourceWidth > 0) || !(sourceHeight > 0) || !placement) return null;
  const { width, height, x = 0, y = 0 } = placement;
  if (!(width > 0) || !(height > 0)) return null;
  const scaleX = Number((width / sourceWidth).toFixed(6));
  const scaleY = Number((height / sourceHeight).toFixed(6));
  if (scaleX === 1 && scaleY === 1 && x === 0 && y === 0) return null;
  return { scaleX, scaleY, offsetX: x, offsetY: y };
}

/**
 * Move one box from the source raster's pixel space into the published raster's.
 *
 * Returned unchanged — the same object — when there is no transform, so a consumer can call this
 * unconditionally and a reference that was not rescaled keeps the very bytes it has today.
 *
 * Width and height are floored at 1: a box small enough to round to nothing under a reduction is
 * still a box somebody drew, and a zero-area rectangle is invisible rather than honest.
 */
export function transformBounds(bounds, transform) {
  if (!transform || !bounds) return bounds;
  const { scaleX, scaleY, offsetX = 0, offsetY = 0 } = transform;
  return {
    ...bounds,
    x: Math.round(bounds.x * scaleX) + offsetX,
    y: Math.round(bounds.y * scaleY) + offsetY,
    width: Math.max(1, Math.round(bounds.width * scaleX)),
    height: Math.max(1, Math.round(bounds.height * scaleY)),
  };
}

/**
 * Move a reference's annotation layer into the published raster's pixel space.
 *
 * The list is returned as it stands when nothing moved, and an annotation carrying no `bounds` is
 * passed through rather than given one.
 */
export function transformAnnotations(annotations, transform) {
  if (!transform || !Array.isArray(annotations)) return annotations;
  return annotations.map((annotation) =>
    annotation?.bounds
      ? { ...annotation, bounds: transformBounds(annotation.bounds, transform) }
      : annotation,
  );
}
