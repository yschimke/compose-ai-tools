/**
 * Pixel preprocessing shared by the `rc-compare` lanes, split out of the driver so it can be tested
 * without a browser or a staged catalog (`rc-compare.mjs` renders on import, so nothing in it is
 * importable).
 *
 * The two functions here have an **ordering constraint** between them, and getting it wrong is
 * silent: `flattenOnto` composites alpha away *in place*, so `isFullyTransparent` must be asked
 * first — afterwards a blank sticker and a solid mid-grey card are the same pixels.
 */

/**
 * The neutral both sides are flattened onto before diffing.
 *
 * The catalog PNGs are stickers on a *transparent* background, and pixelmatch composites transparent
 * pixels over white — so light content on transparent (a white icon, pale text) would read as
 * identical to a blank canvas, a false 0% match. Compositing both sides over the same mid-grey makes
 * light *and* dark content contrast, so a blank render always diffs.
 */
export const BG = [128, 128, 128];

/** Flatten an RGBA image onto an opaque colour, in place. Returns the same object. */
export function flattenOnto(png, [br, bg, bb] = BG) {
  const d = png.data;
  for (let i = 0; i < d.length; i += 4) {
    const a = d[i + 3] / 255;
    d[i] = Math.round(d[i] * a + br * (1 - a));
    d[i + 1] = Math.round(d[i + 1] * a + bg * (1 - a));
    d[i + 2] = Math.round(d[i + 2] * a + bb * (1 - a));
    d[i + 3] = 255;
  }
  return png;
}

/**
 * True when a decoded PNG has no opaque pixel anywhere — the capture produced nothing.
 *
 * Why it matters: a blank *reference* makes the whole comparison vacuous. A player that also draws
 * nothing flattens onto the same neutral and scores an exact 0.00% — the best possible parity result
 * for a preview that baked to nothing at all. Rows like that are reported as unscorable rather than
 * as perfect matches.
 *
 * Strictly `alpha === 0`: a nearly-invisible render is a rendering bug worth scoring, not a missing
 * reference, so this deliberately does not take a threshold.
 *
 * Must be called *before* `flattenOnto` — see the module note.
 */
export function isFullyTransparent(png) {
  const d = png.data;
  for (let i = 3; i < d.length; i += 4) if (d[i] !== 0) return false;
  return true;
}

/**
 * Split a comparison into **coverage** disagreement and **content** disagreement.
 *
 * A single mismatch percentage conflates two unrelated failures. If the player paints a smaller
 * region than the baked render — a card that under-fills its canvas, a band left undrawn at the
 * bottom — every pixel of that band is composited onto {@link BG} on one side only, so a framing or
 * background gap reads as a large content error and drowns out the thing you were actually looking
 * at. Both are worth knowing; they are not worth adding together.
 *
 * - `coverageDeltaPct` — share of the canvas where exactly one side painted anything. Pure
 *   framing/background disagreement: nothing is said about colour.
 * - `contentMismatchPct` — share of the pixels **both** sides painted whose colour disagrees beyond
 *   [tolerance]. This is the number to read when asking "does the player draw this correctly",
 *   because it is computed only where the question is meaningful. Null when the two never overlap.
 * - `bothPaintedPct` — how much of the canvas that judgement covers, so a tiny overlap can't quietly
 *   masquerade as a clean score.
 *
 * Must be called *before* `flattenOnto` on either side — the alpha it reads is what flattening
 * destroys.
 */
export function splitCoverage(bakedRgba, playerRgba, width, height, tolerance = 24) {
  const ALPHA_PAINTED = 8;
  let bothPainted = 0;
  let bothPaintedDiff = 0;
  let coverageOnly = 0;
  for (let i = 0; i < bakedRgba.length; i += 4) {
    const bakedPainted = bakedRgba[i + 3] > ALPHA_PAINTED;
    const playerPainted = playerRgba[i + 3] > ALPHA_PAINTED;
    if (bakedPainted && playerPainted) {
      bothPainted++;
      const delta =
        Math.abs(bakedRgba[i] - playerRgba[i]) +
        Math.abs(bakedRgba[i + 1] - playerRgba[i + 1]) +
        Math.abs(bakedRgba[i + 2] - playerRgba[i + 2]);
      if (delta > tolerance) bothPaintedDiff++;
    } else if (bakedPainted !== playerPainted) {
      coverageOnly++;
    }
  }
  const total = width * height;
  return {
    coverageDeltaPct: total ? (100 * coverageOnly) / total : 0,
    contentMismatchPct: bothPainted ? (100 * bothPaintedDiff) / bothPainted : null,
    bothPaintedPct: total ? (100 * bothPainted) / total : 0,
  };
}
