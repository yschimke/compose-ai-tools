/**
 * Fit an RGBA raster into the dimensions of the catalog sticker it will be compared against.
 *
 * A design reference is published at the sticker's dimensions so the two sit together in the
 * comparison UI. Two very different adjustments hide behind that one sentence, and treating them
 * as one was a bug:
 *
 *   * **A rounding correction.** Headless Chrome rounds `411dp × 2.625` differently from the
 *     Compose renderer, so a browser raster lands a pixel or two off. Stretching to close that gap
 *     is right: the proportions are already correct and the delta is sub-pixel.
 *   * **A genuine size difference.** A design-tool export is authored at its own size, and its
 *     proportions need not match the sticker's at all — a component drawn tight to its artboard has
 *     nothing corresponding to the preview's scaffold padding or a fixed-height container the
 *     content does not fill. Stretching *that* to fit silently rewrites the design: the published
 *     reference shows the component at proportions its author never drew, and the comparison then
 *     measures this resample instead of the drift it exists to find.
 *
 * So the first case resamples ([resampleRgba]) and the second fits ([fitRgba]) — scaled to sit
 * inside the target box with its aspect ratio intact, centred, with the remainder left
 * transparent. The comparison scorer normalises both sides to their content box before scoring, so
 * the transparent remainder costs nothing and the reference keeps the shape it was drawn at.
 *
 * Bilinear, and deliberately so: nearest-neighbour on a ~1px correction shears text stems and would
 * show up as spurious drift in the very diff this feeds. Pure and dependency-free (operates on a
 * plain RGBA buffer, no pngjs import) so it unit-tests without an `npm ci`.
 */

/**
 * Bilinearly resample `data` (RGBA, `width` × `height`) to `targetWidth` × `targetHeight`.
 * Returns the source buffer unchanged when it is already the target size, so the common
 * already-exact case costs nothing and stays bit-identical.
 */
export function resampleRgba(data, width, height, targetWidth, targetHeight) {
  if (width === targetWidth && height === targetHeight) return data;
  if (width <= 0 || height <= 0 || targetWidth <= 0 || targetHeight <= 0) {
    throw new Error(
      `resample: bad dimensions ${width}x${height} -> ${targetWidth}x${targetHeight}`,
    );
  }
  const out = Buffer.alloc(targetWidth * targetHeight * 4);
  // Map destination pixel CENTRES back into source space, so the resample stays centred
  // rather than drifting half a pixel toward the origin (visible as a hairline shift on a
  // near-1:1 correction, which is most of what this does).
  const sx = width / targetWidth;
  const sy = height / targetHeight;
  for (let y = 0; y < targetHeight; y++) {
    const fy = Math.min(height - 1, Math.max(0, (y + 0.5) * sy - 0.5));
    const y0 = Math.floor(fy);
    const y1 = Math.min(height - 1, y0 + 1);
    const wy = fy - y0;
    for (let x = 0; x < targetWidth; x++) {
      const fx = Math.min(width - 1, Math.max(0, (x + 0.5) * sx - 0.5));
      const x0 = Math.floor(fx);
      const x1 = Math.min(width - 1, x0 + 1);
      const wx = fx - x0;

      const i00 = (y0 * width + x0) * 4;
      const i01 = (y0 * width + x1) * 4;
      const i10 = (y1 * width + x0) * 4;
      const i11 = (y1 * width + x1) * 4;
      const o = (y * targetWidth + x) * 4;
      for (let c = 0; c < 4; c++) {
        const top = data[i00 + c] * (1 - wx) + data[i01 + c] * wx;
        const bottom = data[i10 + c] * (1 - wx) + data[i11 + c] * wx;
        out[o + c] = Math.round(top * (1 - wy) + bottom * wy);
      }
    }
  }
  return out;
}

/**
 * Whether a source size is close enough to the target that resampling is a rounding correction
 * rather than a rescale.
 *
 * This now decides what actually happens, not just what gets logged: within tolerance the raster is
 * stretched to the target ([resampleRgba]), outside it the raster is fitted with its proportions
 * kept ([fitRgba]).
 */
export function isRoundingDelta(width, height, targetWidth, targetHeight, tolerancePx = 4) {
  return (
    Math.abs(width - targetWidth) <= tolerancePx && Math.abs(height - targetHeight) <= tolerancePx
  );
}

/**
 * Where a `width` × `height` raster lands inside a `targetWidth` × `targetHeight` box when scaled
 * to fit with its aspect ratio intact and centred.
 *
 * Returned rather than kept private because the annotation layer needs it: greenlines are captured
 * in the design frame's own coordinates and have to be scaled and offset by exactly the transform
 * the raster went through, or every box drifts off the thing it describes.
 */
export function fitBox(width, height, targetWidth, targetHeight) {
  if (width <= 0 || height <= 0 || targetWidth <= 0 || targetHeight <= 0) {
    throw new Error(`fit: bad dimensions ${width}x${height} -> ${targetWidth}x${targetHeight}`);
  }
  const scale = Math.min(targetWidth / width, targetHeight / height);
  const fittedWidth = Math.max(1, Math.min(targetWidth, Math.round(width * scale)));
  const fittedHeight = Math.max(1, Math.min(targetHeight, Math.round(height * scale)));
  return {
    width: fittedWidth,
    height: fittedHeight,
    x: Math.floor((targetWidth - fittedWidth) / 2),
    y: Math.floor((targetHeight - fittedHeight) / 2),
  };
}

/**
 * Scale `data` to fit a `targetWidth` × `targetHeight` canvas without distorting it, centred, with
 * the remainder left fully transparent.
 *
 * Returns `{ data, box }` — the padded raster and where the artwork sits within it.
 */
export function fitRgba(data, width, height, targetWidth, targetHeight) {
  const box = fitBox(width, height, targetWidth, targetHeight);
  const scaled = resampleRgba(data, width, height, box.width, box.height);
  if (box.width === targetWidth && box.height === targetHeight) return { data: scaled, box };
  // Buffer.alloc zero-fills, and RGBA zero is transparent — no explicit clear needed.
  const out = Buffer.alloc(targetWidth * targetHeight * 4);
  const rowBytes = box.width * 4;
  for (let y = 0; y < box.height; y++) {
    const from = y * rowBytes;
    const to = ((y + box.y) * targetWidth + box.x) * 4;
    out.set(scaled.subarray(from, from + rowBytes), to);
  }
  return { data: out, box };
}
