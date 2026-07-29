/**
 * Unit tests for the `rc-compare` pixel preprocessing. These cover the one subtlety the parity page
 * depends on and that no HTML-level test can see: whether a *blank baked reference* is recognised
 * before `flattenOnto` erases the evidence.
 *
 * Run with `node --test scripts/design-artifacts/*.test.mjs`.
 */
import assert from "node:assert/strict";
import { test } from "node:test";
import { PNG } from "pngjs";

import { BG, flattenOnto, isFullyTransparent } from "./rc-compare-pixels.mjs";

/** A tiny RGBA image; `fill` is called per pixel and returns `[r, g, b, a]`. */
function image(width, height, fill) {
  const png = new PNG({ width, height });
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const i = (y * width + x) * 4;
      const [r, g, b, a] = fill(x, y);
      png.data[i] = r;
      png.data[i + 1] = g;
      png.data[i + 2] = b;
      png.data[i + 3] = a;
    }
  }
  return png;
}

const blank = () => image(8, 8, () => [0, 0, 0, 0]);

test("a fully transparent capture is recognised as blank", () => {
  assert.equal(isFullyTransparent(blank()), true);
});

test("a single opaque pixel is enough to make a capture non-blank", () => {
  const png = image(8, 8, (x, y) => (x === 5 && y === 3 ? [255, 255, 255, 255] : [0, 0, 0, 0]));
  assert.equal(isFullyTransparent(png), false);
});

test("faint content is not blank — a near-invisible render is a bug worth scoring", () => {
  // alpha 1/255. Deliberately not thresholded: "the player drew almost nothing" is a rendering
  // regression to report, not a missing reference to excuse.
  const png = image(8, 8, (x) => (x === 0 ? [255, 0, 0, 1] : [0, 0, 0, 0]));
  assert.equal(isFullyTransparent(png), false);
});

test("an opaque image is never blank, even when it is solid background-coloured", () => {
  const png = image(8, 8, () => [...BG, 255]);
  assert.equal(isFullyTransparent(png), false);
});

test("flattening a blank capture yields exactly the background — the false-match case", () => {
  // This is *why* the blankness check exists: after flattening, a blank baked reference and a
  // player that drew nothing are byte-identical, so pixelmatch reports a perfect 0.00%.
  const bakedBlank = flattenOnto(blank(), BG);
  const playerDrewNothing = flattenOnto(blank(), BG);
  assert.deepEqual([...bakedBlank.data], [...playerDrewNothing.data]);
  for (let i = 0; i < bakedBlank.data.length; i += 4) {
    assert.deepEqual([...bakedBlank.data.slice(i, i + 4)], [...BG, 255]);
  }
});

test("blankness must be read before flattening — flattening destroys the signal", () => {
  const png = blank();
  assert.equal(isFullyTransparent(png), true);
  flattenOnto(png, BG); // mutates in place
  assert.equal(isFullyTransparent(png), false, "alpha is gone; the answer is now meaningless");
});

test("flattening composites partial alpha toward the background", () => {
  // Half-opaque red over mid-grey: r = 255*0.5 + 128*0.5 ≈ 192, g/b = 0*0.5 + 128*0.5 = 64.
  const png = flattenOnto(image(1, 1, () => [255, 0, 0, 128]), BG);
  assert.deepEqual([...png.data], [192, 64, 64, 255]);
});

test("flattening leaves opaque pixels untouched", () => {
  const png = flattenOnto(image(1, 1, () => [10, 20, 30, 255]), BG);
  assert.deepEqual([...png.data], [10, 20, 30, 255]);
});
