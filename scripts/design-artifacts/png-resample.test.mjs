import { test } from "node:test";
import assert from "node:assert/strict";

import { isRoundingDelta, resampleRgba } from "./png-resample.mjs";

/** An RGBA buffer whose pixels are produced by `fn(x, y)` returning `[r,g,b,a]`. */
function raster(width, height, fn) {
  const data = Buffer.alloc(width * height * 4);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const [r, g, b, a] = fn(x, y);
      const i = (y * width + x) * 4;
      data[i] = r;
      data[i + 1] = g;
      data[i + 2] = b;
      data[i + 3] = a;
    }
  }
  return data;
}

test("an already-exact size is returned untouched", () => {
  const data = raster(4, 3, () => [1, 2, 3, 255]);
  assert.equal(resampleRgba(data, 4, 3, 4, 3), data);
});

test("a solid colour survives any rescale exactly", () => {
  const data = raster(8, 8, () => [0x40, 0x80, 0xc0, 0xff]);
  const out = resampleRgba(data, 8, 8, 5, 11);
  assert.equal(out.length, 5 * 11 * 4);
  for (let i = 0; i < out.length; i += 4) {
    assert.deepEqual([out[i], out[i + 1], out[i + 2], out[i + 3]], [0x40, 0x80, 0xc0, 0xff]);
  }
});

test("the 2x design-tool export case halves cleanly", () => {
  // Every 2x2 source block is one colour, so the exact-halving result is that colour —
  // this is the Figma-frame-at-double-density shape the emitter has to correct.
  const data = raster(4, 4, (x, y) => {
    const v = (Math.floor(y / 2) * 2 + Math.floor(x / 2)) * 60;
    return [v, v, v, 255];
  });
  const out = resampleRgba(data, 4, 4, 2, 2);
  assert.deepEqual([out[0], out[4], out[8], out[12]], [0, 60, 120, 180]);
});

test("a 1px rounding correction stays centred and doesn't shear a gradient", () => {
  // The headless-Chrome case: 1079 wide vs a 1078 target. A horizontal ramp must come
  // out still monotonic, and the ends must still be the ends.
  const width = 1079;
  const data = raster(width, 2, (x) => {
    const v = Math.round((x / (width - 1)) * 255);
    return [v, v, v, 255];
  });
  const out = resampleRgba(data, width, 2, 1078, 2);

  assert.equal(out.length, 1078 * 2 * 4);
  assert.equal(out[0], 0);
  assert.equal(out[(1077 * 4) | 0], 255);
  for (let x = 1; x < 1078; x++) {
    assert.ok(out[x * 4] >= out[(x - 1) * 4], `not monotonic at x=${x}`);
  }
});

test("alpha is resampled alongside colour", () => {
  const data = raster(2, 1, (x) => [255, 255, 255, x === 0 ? 0 : 255]);
  const out = resampleRgba(data, 2, 1, 4, 1);
  assert.equal(out[3], 0);
  assert.equal(out[15], 255);
  assert.ok(out[7] < out[11], "alpha should ramp across the resampled row");
});

test("bad dimensions throw rather than emitting a malformed buffer", () => {
  const data = raster(2, 2, () => [0, 0, 0, 255]);
  assert.throws(() => resampleRgba(data, 2, 2, 0, 2), /bad dimensions/);
  assert.throws(() => resampleRgba(data, 0, 2, 2, 2), /bad dimensions/);
});

test("isRoundingDelta separates a density correction from a rescale", () => {
  assert.equal(isRoundingDelta(1079, 2399, 1078, 2399), true);
  assert.equal(isRoundingDelta(2156, 4798, 1078, 2399), false);
});
