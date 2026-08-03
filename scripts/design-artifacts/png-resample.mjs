/**
 * Resample an RGBA raster to an exact target size.
 *
 * A design reference has to be published at **exactly** the dimensions of the catalog sticker it
 * will be compared against. That isn't a nicety: the preview server's comparison
 * (`format-compare.js` → `compareImageUrls`) throws `"image dimensions differ"` and renders
 * "Unavailable · reference and actual dimensions differ" rather than scaling one side into a
 * score that looks meaningful but isn't. So a reference that is off by even a pixel — and headless
 * Chrome rounding `411dp × 2.625` differently from the Compose renderer is exactly that case —
 * publishes as a dead row.
 *
 * The two cases this has to cover are quite different in size, which is why it resamples rather
 * than crops: a browser raster is within a pixel or two of the target, while a design-tool export
 * can be a whole integer multiple of it.
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
 * rather than a rescale. Used only to decide whether to *say something*: a whole-multiple rescale
 * (a design-tool export at a different density) is worth a log line, a 1px correction is noise.
 */
export function isRoundingDelta(width, height, targetWidth, targetHeight, tolerancePx = 4) {
  return (
    Math.abs(width - targetWidth) <= tolerancePx && Math.abs(height - targetHeight) <= tolerancePx
  );
}
