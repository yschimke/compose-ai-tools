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
