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

/**
 * The tight box of pixels that are not fully transparent, or null when nothing is drawn at all.
 *
 * A Figma component node is exported at its own `absoluteBoundingBox`, and a kit routinely draws
 * that box larger than the component: the Material 3 kit wraps a 32dp XSmall button in a 48dp
 * touch-target frame, and every icon button, checkbox, switch and radio in a 48dp one. Those extra
 * rows and columns are empty, but they are pixels, and [placeRgba] used to size its reduction off
 * them — so a component that matched the render exactly was published two thirds the size, and the
 * comparison then reported the padding instead of the design (m3-catalog#180).
 */
export function alphaBounds(data, width, height) {
  if (width <= 0 || height <= 0) return null;
  let minX = width;
  let minY = height;
  let maxX = -1;
  let maxY = -1;
  for (let y = 0; y < height; y++) {
    const row = y * width * 4;
    for (let x = 0; x < width; x++) {
      if (data[row + x * 4 + 3] === 0) continue;
      if (x < minX) minX = x;
      if (x > maxX) maxX = x;
      if (y < minY) minY = y;
      if (y > maxY) maxY = y;
    }
  }
  if (maxX < 0) return null;
  return { x: minX, y: minY, width: maxX - minX + 1, height: maxY - minY + 1 };
}

/**
 * The tight box of non-transparent pixels inside `region` of an RGBA raster, or null when it holds
 * none. Windowed rather than whole-raster so a caller that deliberately nominates a sub-rect keeps
 * its meaning: only the artwork it named decides the placement.
 */
function drawnBoundsIn(data, width, region) {
  let minX = region.x1;
  let minY = region.y1;
  let maxX = -1;
  let maxY = -1;
  for (let y = region.y0; y < region.y1; y++) {
    const row = y * width * 4;
    for (let x = region.x0; x < region.x1; x++) {
      if (data[row + x * 4 + 3] === 0) continue;
      if (x < minX) minX = x;
      if (x > maxX) maxX = x;
      if (y < minY) minY = y;
      if (y > maxY) maxY = y;
    }
  }
  if (maxX < 0) return null;
  return { x: minX, y: minY, width: maxX - minX + 1, height: maxY - minY + 1 };
}

/**
 * Where to start drawing a `placed`-long axis so the content occupying `[start, start + size)` of
 * it sits centred in `target`, clamped so content that fits is never pushed off an edge.
 *
 * `start` and `size` are measured in the RESAMPLED raster, not estimated from the source by the
 * scale. Estimating was wrong twice over: once against the unrounded factor, and once more when a
 * `ceil` on a fractional edge reported a 25-row content box as 26, skipped the clamp as "does not
 * fit", and cropped the row it was protecting.
 */
function centreOn(start, size, target) {
  const offset = Math.floor((target - size) / 2) - start;
  if (size > target) return offset;
  return Math.min(Math.max(offset, -start), target - (start + size));
}

/**
 * Centre a raster on a transparent target canvas without enlarging it.
 *
 * This is the operation a density-matched component export needs. Its pixel dimensions already
 * describe the component at the renderer's scale; fitting it upward to consume a padded preview
 * canvas would change that scale.
 *
 * `content` — a sub-rect of the source, defaulting to the whole of it — is what has to stay
 * representable, and it is centred rather than the raster it sits in. An oversized source is still
 * reduced uniformly, but only when its *content* overflows: transparent margin that would otherwise
 * force a reduction is cropped away instead, because empty pixels carry nothing the comparison can
 * read and shrinking the artwork to keep them costs it the one thing it can.
 *
 * The returned `box` is where the WHOLE source landed, not where the content did — it may start
 * outside the canvas or extend past it once a margin has been cropped. That is deliberate: the
 * annotation layer maps design-frame coordinates through this box, and a box describing anything
 * but the frame would put every greenline somewhere the artwork is not.
 */
export function placeRgba(data, width, height, targetWidth, targetHeight, content = undefined) {
  if (width <= 0 || height <= 0 || targetWidth <= 0 || targetHeight <= 0) {
    throw new Error(`place: bad dimensions ${width}x${height} -> ${targetWidth}x${targetHeight}`);
  }
  const keep =
    content && content.width > 0 && content.height > 0
      ? content
      : { x: 0, y: 0, width, height };
  const scale = Math.min(1, targetWidth / keep.width, targetHeight / keep.height);
  const placedWidth = Math.max(1, Math.round(width * scale));
  const placedHeight = Math.max(1, Math.round(height * scale));
  const placed =
    placedWidth === width && placedHeight === height
      ? data
      : resampleRgba(data, width, height, placedWidth, placedHeight);

  // Where the content actually ended up, read off the resampled raster. The placed dimensions are
  // rounded and a bilinear edge lands where it lands, so anything derived from `scale` alone is an
  // estimate — and an estimate a pixel out either crops the artwork or mis-centres it.
  const scaleX = placedWidth / width;
  const scaleY = placedHeight / height;
  const window = {
    x0: Math.max(0, Math.floor(keep.x * scaleX) - 1),
    y0: Math.max(0, Math.floor(keep.y * scaleY) - 1),
    x1: Math.min(placedWidth, Math.ceil((keep.x + keep.width) * scaleX) + 1),
    y1: Math.min(placedHeight, Math.ceil((keep.y + keep.height) * scaleY) + 1),
  };
  const drawn = drawnBoundsIn(placed, placedWidth, window) ?? {
    x: window.x0,
    y: window.y0,
    width: Math.max(1, window.x1 - window.x0),
    height: Math.max(1, window.y1 - window.y0),
  };

  // Centre the CONTENT on the canvas, then say where that puts the frame around it.
  const box = {
    width: placedWidth,
    height: placedHeight,
    x: centreOn(drawn.x, drawn.width, targetWidth),
    y: centreOn(drawn.y, drawn.height, targetHeight),
  };
  if (box.width === targetWidth && box.height === targetHeight && box.x === 0 && box.y === 0) {
    return { data: placed, box };
  }
  const out = Buffer.alloc(targetWidth * targetHeight * 4);
  // Clip: a cropped margin puts part of the source outside the canvas, by design.
  const fromY = Math.max(0, -box.y);
  const toY = Math.min(placedHeight, targetHeight - box.y);
  const fromX = Math.max(0, -box.x);
  const toX = Math.min(placedWidth, targetWidth - box.x);
  for (let y = fromY; y < toY; y++) {
    if (toX <= fromX) break;
    const from = (y * placedWidth + fromX) * 4;
    const to = ((y + box.y) * targetWidth + (fromX + box.x)) * 4;
    out.set(placed.subarray(from, from + (toX - fromX) * 4), to);
  }
  return { data: out, box };
}
