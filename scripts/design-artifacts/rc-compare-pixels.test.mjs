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

import {
  BG,
  flattenOnto,
  flattenedCopy,
  isFullyTransparent,
  splitCoverage,
} from "./rc-compare-pixels.mjs";

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

test("flattenedCopy flattens the copy and leaves the source alone", () => {
  // The published-render case: the lane PNG is written from the source, so the diffing neutral must
  // not reach it. A transparent pixel stays transparent on the source and becomes the neutral on
  // the copy.
  const source = image(1, 1, () => [255, 0, 0, 0]);
  const copy = flattenedCopy(source, BG);
  assert.deepEqual([...source.data], [255, 0, 0, 0], "the source keeps its alpha");
  assert.deepEqual([...copy.data], [...BG, 255]);
  assert.equal(isFullyTransparent(source), true, "the source is still readable as blank");
});

test("flattenedCopy matches flattenOnto pixel for pixel", () => {
  const partial = () => image(4, 4, (x, y) => [255, 0, 0, (x + y) * 16]);
  assert.deepEqual([...flattenedCopy(partial(), BG).data], [...flattenOnto(partial(), BG).data]);
});

test("flattenedCopy carries the source dimensions, which the size gate reads", () => {
  const copy = flattenedCopy(image(7, 3, () => [0, 0, 0, 255]), BG);
  assert.equal(copy.width, 7);
  assert.equal(copy.height, 3);
});

/**
 * `splitCoverage` exists because a single mismatch number can't distinguish "the player drew this
 * the wrong colour" from "the player didn't draw here at all" — and on a card that under-fills its
 * canvas the second dominates, making a framing gap read as a content error.
 */
test("splitCoverage separates an under-filled region from a colour disagreement", () => {
  // Baked paints all 10 rows white; the player paints only the top 5 — a classic under-fill.
  const baked = image(10, 10, () => [255, 255, 255, 255]);
  const player = image(10, 10, (_x, y) => (y < 5 ? [255, 255, 255, 255] : [0, 0, 0, 0]));
  const s = splitCoverage(baked.data, player.data, 10, 10);
  assert.equal(s.coverageDeltaPct, 50, "half the canvas is painted by exactly one side");
  assert.equal(s.contentMismatchPct, 0, "where both painted, the colours agree exactly");
  assert.equal(s.bothPaintedPct, 50);
});

test("splitCoverage scores colour disagreement only over the shared painted area", () => {
  // Both paint the same top half, but the player's colour is wrong there.
  const baked = image(10, 10, (_x, y) => (y < 5 ? [255, 255, 255, 255] : [0, 0, 0, 0]));
  const player = image(10, 10, (_x, y) => (y < 5 ? [0, 0, 0, 255] : [0, 0, 0, 0]));
  const s = splitCoverage(baked.data, player.data, 10, 10);
  assert.equal(s.coverageDeltaPct, 0, "both sides painted exactly the same region");
  assert.equal(s.contentMismatchPct, 100, "…and disagreed on every pixel of it");
  assert.equal(s.bothPaintedPct, 50, "the judgement covers only half the canvas");
});

test("splitCoverage reports no content verdict when the two never overlap", () => {
  const baked = image(10, 10, (_x, y) => (y < 5 ? [255, 255, 255, 255] : [0, 0, 0, 0]));
  const player = image(10, 10, (_x, y) => (y >= 5 ? [255, 255, 255, 255] : [0, 0, 0, 0]));
  const s = splitCoverage(baked.data, player.data, 10, 10);
  assert.equal(s.coverageDeltaPct, 100);
  assert.equal(s.contentMismatchPct, null, "no shared pixels means no meaningful colour verdict");
  assert.equal(s.bothPaintedPct, 0);
});

test("splitCoverage tolerates sub-threshold colour drift", () => {
  const baked = image(4, 4, () => [100, 100, 100, 255]);
  const player = image(4, 4, () => [104, 103, 102, 255]); // total delta 9, under the 24 default
  assert.equal(splitCoverage(baked.data, player.data, 4, 4).contentMismatchPct, 0);
  assert.equal(splitCoverage(baked.data, player.data, 4, 4, 4).contentMismatchPct, 100);
});
