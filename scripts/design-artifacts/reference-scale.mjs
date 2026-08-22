/**
 * Does a published reference show its component at the same size as the sticker it is compared
 * with?
 *
 * Nothing used to ask. The reference lane checks that a reference *rasterised*, and the scorer
 * reports how well the two pictures match once both have been cropped to their content box and
 * redrawn into one shared box — which is exactly the normalisation that makes a size difference
 * invisible to it. So a reference published at two thirds of its render scored like any other, and
 * a fifth of m3-catalog's 536 references sat centred in their canvas at 0.47–0.91 of the render
 * with no signal anywhere (m3-catalog#180).
 *
 * The signature this looks for is a **uniform** rescale: both axes off by the same factor. A
 * difference in *proportion* is what the scorer's `geometry` already reports, so a uniform one is
 * the interesting half.
 *
 * It does NOT by itself say whose fault the size difference is, and an earlier draft of this file
 * claimed it did. A kit and an implementation can disagree about a checkbox by a similar amount on
 * both axes, and calling that an export defect would gate a `--strict` publish on a genuine parity
 * finding — the opposite of what this lane is for. What separates the two is not the ratio: it is
 * whether the pipeline scaled the artwork on the way in, which the emitter knows for a fact from
 * the placement it just performed. So the caller passes that in, and [scaleVerdict] routes on it.
 *
 * Pure and dependency-free so it unit-tests without an `npm ci`.
 */

/** Both axes must agree within this to call a difference a uniform rescale rather than a reshape. */
const UNIFORM_TOLERANCE = 0.06;

/**
 * Below this a scale difference is the rasteriser rather than the pipeline.
 *
 * Relative, with a pixel floor: a bilinear resample and the antialiased edge either side of it move
 * a content box by about a pixel, which is 0.5% of a 219px button and 4% of a 25px checkmark. A
 * purely relative threshold would either miss the button or cry wolf on the checkmark.
 */
const SCALE_TOLERANCE = 0.03;
const SCALE_FLOOR_PX = 2;

/** The tight content box of a raster, as `{ width, height }`, or null when nothing is drawn. */
function sized(box) {
  return box && box.width > 0 && box.height > 0 ? box : null;
}

/**
 * Compare a published reference's content box with its sticker's.
 *
 * Returns null when the pair cannot be compared (either side draws nothing) or when the difference
 * is within tolerance or is not uniform. Otherwise `{ scale, widthRatio, heightRatio }`, where
 * `scale` is how large the reference is relative to the sticker — below 1 the reference was shrunk.
 */
export function scaleFinding(referenceBox, stickerBox, options = {}) {
  const tolerance = options.tolerance ?? SCALE_TOLERANCE;
  const reference = sized(referenceBox);
  const sticker = sized(stickerBox);
  if (!reference || !sticker) return null;

  const widthRatio = reference.width / sticker.width;
  const heightRatio = reference.height / sticker.height;
  const worst = Math.max(widthRatio, heightRatio);
  if (worst <= 0) return null;
  // Not uniform ⇒ the two are shaped differently, which is a `geometry` finding and not this one.
  if (Math.abs(widthRatio - heightRatio) / worst > UNIFORM_TOLERANCE) return null;

  const scale = (widthRatio + heightRatio) / 2;
  const drift = Math.abs(scale - 1);
  if (drift <= tolerance) return null;
  // A pixel of antialiasing on a small component clears the relative threshold on its own.
  const slack = Math.max(
    Math.abs(reference.width - sticker.width),
    Math.abs(reference.height - sticker.height),
  );
  if (slack <= SCALE_FLOOR_PX) return null;

  return {
    scale: Number(scale.toFixed(3)),
    widthRatio: Number(widthRatio.toFixed(3)),
    heightRatio: Number(heightRatio.toFixed(3)),
  };
}

/**
 * What to do about a finding, given whether the pipeline rescaled the artwork to place it.
 *
 * `rescaled` ⇒ the published picture is not the size the design was exported at, which is a defect
 * in this pipeline and gateable. Otherwise the reference went out at its own density and the two
 * still differ: a size divergence between the kit and the render, which is a finding about the
 * component and must not cost a catalog its publish.
 */
export function scaleVerdict(rescaled) {
  return rescaled ? "export" : "divergence";
}

/** One line describing a finding, for the run log. */
export function scaleMessage(id, finding, referenceBox, stickerBox, verdict = "export") {
  const because =
    verdict === "export"
      ? "the export was rescaled to fit this canvas, so the comparison sees the scale and not the design"
      : "the export was published at its own density, so this is a size divergence rather than an export defect";
  return (
    `${id}: reference draws ${referenceBox.width}x${referenceBox.height} against the sticker's ` +
    `${stickerBox.width}x${stickerBox.height} — a uniform ${finding.scale}x; ${because}`
  );
}
