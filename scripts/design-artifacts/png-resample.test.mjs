import { test } from "node:test";
import assert from "node:assert/strict";

import {
  alphaBounds,
  fitBox,
  fitRgba,
  isRoundingDelta,
  placeRgba,
  resampleRgba,
} from "./png-resample.mjs";

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

test("fitBox keeps the source aspect ratio and centres what is left over", () => {
  // Taller than the target: width is the slack axis, so the artwork is pillarboxed.
  assert.deepEqual(fitBox(200, 400, 400, 400), { width: 200, height: 400, x: 100, y: 0 });
  // Wider than the target: letterboxed instead.
  assert.deepEqual(fitBox(400, 200, 400, 400), { width: 400, height: 200, x: 0, y: 100 });
  // Same proportions — fills the box, nothing to centre.
  assert.deepEqual(fitBox(100, 100, 400, 400), { width: 400, height: 400, x: 0, y: 0 });
});

test("fitBox never exceeds the target box", () => {
  // Rounding a scaled edge up must not push the artwork outside the canvas it is pasted into.
  for (const [w, h] of [[893, 924], [1078, 2399], [7, 999], [999, 7]]) {
    const box = fitBox(w, h, 400, 300);
    assert.ok(box.width <= 400 && box.height <= 300, `${w}x${h} overflowed: ${JSON.stringify(box)}`);
    assert.ok(box.x >= 0 && box.y >= 0);
    assert.ok(box.x + box.width <= 400 && box.y + box.height <= 300);
  }
});

test("fitRgba pads with transparency instead of distorting the artwork", () => {
  // A solid 2x1 source into a square target: it must stay 2:1 and the rest must be transparent,
  // because a stretched reference republishes the design at proportions nobody drew.
  const data = raster(2, 1, () => [10, 20, 30, 255]);
  const { data: out, box } = fitRgba(data, 2, 1, 4, 4);
  assert.deepEqual(box, { width: 4, height: 2, x: 0, y: 1 });
  assert.equal(out.length, 4 * 4 * 4);
  const alphaAt = (x, y) => out[(y * 4 + x) * 4 + 3];
  assert.equal(alphaAt(0, 0), 0, "the padded row above the artwork must be transparent");
  assert.equal(alphaAt(0, 3), 0, "the padded row below the artwork must be transparent");
  assert.equal(alphaAt(0, 1), 255, "the artwork row must be opaque");
  assert.equal(out[(1 * 4 + 0) * 4], 10, "artwork colour must survive the paste");
});

test("fitRgba is a plain resample when the proportions already agree", () => {
  const data = raster(2, 2, () => [1, 2, 3, 255]);
  const { data: out, box } = fitRgba(data, 2, 2, 4, 4);
  assert.deepEqual(box, { width: 4, height: 4, x: 0, y: 0 });
  assert.deepEqual(out, resampleRgba(data, 2, 2, 4, 4));
});

test("fitRgba rejects bad dimensions rather than emitting a malformed buffer", () => {
  const data = raster(2, 2, () => [0, 0, 0, 255]);
  assert.throws(() => fitRgba(data, 2, 2, 0, 4), /bad dimensions/);
});

test("placeRgba centres a density-matched component without enlarging it", () => {
  const data = raster(2, 2, () => [10, 20, 30, 255]);
  const { data: out, box } = placeRgba(data, 2, 2, 6, 4);
  assert.deepEqual(box, { width: 2, height: 2, x: 2, y: 1 });
  const alphaAt = (x, y) => out[(y * 6 + x) * 4 + 3];
  assert.equal(alphaAt(1, 1), 0);
  assert.equal(alphaAt(2, 1), 255);
  assert.equal(alphaAt(3, 2), 255);
  assert.equal(alphaAt(4, 2), 0);
});

test("placeRgba only scales when the source would overflow the target", () => {
  const data = raster(4, 2, () => [1, 2, 3, 255]);
  const { box } = placeRgba(data, 4, 2, 2, 2);
  assert.deepEqual(box, { width: 2, height: 1, x: 0, y: 0 });
});

test("alphaBounds finds the drawn box inside a transparent frame", () => {
  const data = raster(10, 6, (x, y) => (x >= 2 && x <= 6 && y >= 1 && y <= 3 ? [0, 0, 0, 255] : [0, 0, 0, 0]));
  assert.deepEqual(alphaBounds(data, 10, 6), { x: 2, y: 1, width: 5, height: 3 });
});

test("alphaBounds keeps a barely-visible pixel — alpha 1 is drawn", () => {
  const data = raster(4, 4, (x, y) => (x === 3 && y === 0 ? [0, 0, 0, 1] : [0, 0, 0, 0]));
  assert.deepEqual(alphaBounds(data, 4, 4), { x: 3, y: 0, width: 1, height: 1 });
});

test("alphaBounds reports nothing for a fully transparent raster", () => {
  assert.equal(alphaBounds(raster(4, 4, () => [9, 9, 9, 0]), 4, 4), null);
});

test("placeRgba crops an empty margin rather than shrinking what is drawn", () => {
  // The m3-catalog#180 geometry, to scale: the kit exports its 32dp XSmall button inside a 48dp
  // touch-target frame, so at the renderer's 2.625 density a 218x84 button arrives in a 218x126
  // raster. The sticker canvas is the button alone. Sizing the reduction off the frame published
  // it at 145x56 — two thirds of the render it is compared with.
  const content = { x: 0, y: 21, width: 218, height: 84 };
  const data = raster(218, 126, (x, y) =>
    y >= content.y && y < content.y + content.height ? [0, 0, 0, 255] : [0, 0, 0, 0],
  );
  const { data: out, box } = placeRgba(data, 218, 126, 219, 84, content);
  assert.deepEqual(box, { width: 218, height: 126, x: 0, y: -21 });
  assert.deepEqual(alphaBounds(out, 219, 84), { x: 0, y: 0, width: 218, height: 84 });
});

test("placeRgba still reduces when the DRAWN content overflows", () => {
  // A kit shape board: a 300-unit vector on a 380-unit artboard. The content genuinely does not
  // fit, so it is reduced — but to the canvas, not to the canvas times the padding's ratio.
  const content = { x: 40, y: 40, width: 300, height: 300 };
  const data = raster(380, 380, (x, y) =>
    x >= 40 && x < 340 && y >= 40 && y < 340 ? [0, 0, 0, 255] : [0, 0, 0, 0],
  );
  const { data: out } = placeRgba(data, 380, 380, 150, 150, content);
  const drawn = alphaBounds(out, 150, 150);
  assert.equal(drawn.width, 150);
  assert.equal(drawn.height, 150);
});

test("placeRgba is unchanged when the content is the whole raster", () => {
  const data = raster(4, 4, () => [1, 2, 3, 255]);
  const withContent = placeRgba(data, 4, 4, 10, 8, { x: 0, y: 0, width: 4, height: 4 });
  const without = placeRgba(data, 4, 4, 10, 8);
  assert.deepEqual(withContent.box, without.box);
  assert.deepEqual(withContent.data, without.data);
});

test("placeRgba ignores an empty content box rather than dividing by it", () => {
  const data = raster(4, 4, () => [1, 2, 3, 255]);
  const { box } = placeRgba(data, 4, 4, 10, 8, { x: 0, y: 0, width: 0, height: 0 });
  assert.deepEqual(box, { width: 4, height: 4, x: 3, y: 2 });
});

test("placeRgba keeps content that exactly fits, despite the resample's rounding", () => {
  // A 300-unit vector on a 380-unit artboard, fitted to 151px: the placed raster rounds to 191,
  // and offsetting by the unrounded factor pushed a column off the canvas — 150px published for
  // content that fills 151.
  const content = { x: 40, y: 40, width: 300, height: 300 };
  const data = raster(380, 380, (x, y) =>
    x >= 40 && x < 340 && y >= 40 && y < 340 ? [0, 0, 0, 255] : [0, 0, 0, 0],
  );
  const { data: out } = placeRgba(data, 380, 380, 151, 151, content);
  assert.deepEqual(alphaBounds(out, 151, 151), { x: 0, y: 0, width: 151, height: 151 });
});
