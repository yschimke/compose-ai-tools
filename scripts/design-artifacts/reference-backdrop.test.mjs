import { test } from "node:test";
import assert from "node:assert/strict";

import { applyBackdrop, parseBackdrop, stageOf } from "./reference-backdrop.mjs";

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

/** The pixel at (x, y) as `[r,g,b,a]`. */
function at(data, width, x, y) {
  const i = (y * width + x) * 4;
  return [data[i], data[i + 1], data[i + 2], data[i + 3]];
}

/** A round Wear sticker: an opaque disc inscribed in a square frame, corners bare. */
function discSticker(size, fill = [0, 0, 0]) {
  const centre = size / 2;
  return raster(size, size, (x, y) => {
    const dx = x + 0.5 - centre;
    const dy = y + 0.5 - centre;
    return dx * dx + dy * dy <= centre * centre ? [...fill, 255] : [0, 0, 0, 0];
  });
}

test("parseBackdrop reads the three colour spellings and the off-switches", () => {
  assert.deepEqual(parseBackdrop("#000000"), { r: 0, g: 0, b: 0 });
  assert.deepEqual(parseBackdrop("1a2b3c"), { r: 0x1a, g: 0x2b, b: 0x3c });
  assert.deepEqual(parseBackdrop("#F00"), { r: 255, g: 0, b: 0 });
  assert.equal(parseBackdrop("none"), null);
  assert.equal(parseBackdrop(""), null);
  assert.equal(parseBackdrop(undefined), null);
});

// A typo that silently meant "off" would publish a whole catalog's references unchanged and read
// as the feature not working, which is the one failure mode worth being loud about.
test("parseBackdrop refuses anything that is not a colour", () => {
  assert.throws(() => parseBackdrop("black"), /not a colour/);
  assert.throws(() => parseBackdrop("#12345"), /not a colour/);
});

test("stageOf recognises a fully opaque frame", () => {
  const data = raster(8, 4, () => [10, 20, 30, 255]);
  const stage = stageOf(data, 8, 4);
  assert.equal(stage.kind, "frame");
  assert.equal(stage.coverage.length, 32);
  assert.ok(stage.coverage.every((a) => a === 255));
});

test("stageOf recognises the disc a round Wear preview paints", () => {
  const stage = stageOf(discSticker(64), 64, 64);
  assert.equal(stage.kind, "disc");
  // The corners are outside the mask, so the stencil says so.
  assert.equal(stage.coverage[0], 0);
  assert.equal(stage.coverage[32 * 64 + 32], 255);
});

// The load-bearing negative: a transparent component sticker is the majority of a Wear catalog's
// references, and repainting one would put a coloured tile under artwork that is meant to sit on
// the reader's own canvas.
test("stageOf leaves a transparent component sticker alone", () => {
  const data = raster(64, 64, (x, y) =>
    x >= 8 && x < 56 && y >= 24 && y < 40 ? [200, 200, 200, 255] : [0, 0, 0, 0],
  );
  assert.equal(stageOf(data, 64, 64), null);
});

// The Wear scroll capsule's vertical stadium is a real published shape that is neither. It must be
// skipped and counted rather than approximated by the disc.
test("stageOf refuses a shape it does not recognise", () => {
  const data = raster(64, 128, (x, y) => (y >= 16 && y < 112 ? [0, 0, 0, 255] : [0, 0, 0, 0]));
  assert.equal(stageOf(data, 64, 128), null);
});

test("stageOf tolerates the antialiased rim of a real mask", () => {
  const size = 64;
  const centre = size / 2;
  const data = raster(size, size, (x, y) => {
    const d = Math.hypot(x + 0.5 - centre, y + 0.5 - centre);
    if (d <= centre - 1) return [0, 0, 0, 255];
    if (d >= centre + 1) return [0, 0, 0, 0];
    return [0, 0, 0, 128];
  });
  assert.equal(stageOf(data, size, size).kind, "disc");
});

/** The stage of a fully opaque frame the size of `width` x `height`. */
function frameStage(width, height) {
  return stageOf(raster(width, height, () => [0, 0, 0, 255]), width, height);
}

test("applyBackdrop fills the disc and leaves the corners bare", () => {
  const size = 32;
  const stage = stageOf(discSticker(size), size, size);
  const reference = raster(size, size, () => [0, 0, 0, 0]);
  const out = applyBackdrop(reference, size, size, stage, { r: 0, g: 0, b: 0 });
  assert.deepEqual(at(out, size, 16, 16), [0, 0, 0, 255]);
  assert.deepEqual(at(out, size, 0, 0), [0, 0, 0, 0]);
});

test("applyBackdrop leaves opaque artwork untouched and blends its soft edges", () => {
  const size = 16;
  const reference = raster(size, size, (x, y) => {
    if (x === 4 && y === 4) return [255, 255, 255, 255];
    if (x === 5 && y === 4) return [255, 255, 255, 128];
    return [0, 0, 0, 0];
  });
  const out = applyBackdrop(reference, size, size, frameStage(size, size), { r: 0, g: 0, b: 0 });
  assert.deepEqual(at(out, size, 4, 4), [255, 255, 255, 255]);
  const [r, , , a] = at(out, size, 5, 4);
  assert.equal(a, 255);
  assert.ok(r > 120 && r < 136, `expected a half-blend, got ${r}`);
});

// The invariant that keeps this safe to turn on for a whole catalog: where a kit cell already draws
// the ground, there is nothing to add and nothing may move. Compositing source-over would break it,
// stacking the two descriptions of one coincident edge into a harder rim than either image has.
test("applyBackdrop is a no-op on a reference that already covers the stage", () => {
  const size = 48;
  const stage = stageOf(discSticker(size), size, size);
  const reference = discSticker(size, [12, 34, 56]);
  const out = applyBackdrop(reference, size, size, stage, { r: 0, g: 0, b: 0 });
  assert.deepEqual(out, reference);
});

// The rim is why the stencil is the sticker's own alpha rather than a redrawn ideal: a reference
// that already carries the ground must come out matching the sticker's edge, not harder than it.
test("applyBackdrop reproduces the sticker's antialiased rim rather than hardening it", () => {
  const size = 64;
  const centre = size / 2;
  const sticker = raster(size, size, (x, y) => {
    const d = Math.hypot(x + 0.5 - centre, y + 0.5 - centre);
    if (d <= centre - 1) return [0, 0, 0, 255];
    if (d >= centre + 1) return [0, 0, 0, 0];
    return [0, 0, 0, 96];
  });
  const stage = stageOf(sticker, size, size);
  const reference = raster(size, size, () => [0, 0, 0, 0]);
  const out = applyBackdrop(reference, size, size, stage, { r: 0, g: 0, b: 0 });
  for (let p = 0; p < size * size; p++) {
    assert.equal(out[p * 4 + 3], sticker[p * 4 + 3], `alpha at ${p} should track the sticker's`);
  }
});

test("applyBackdrop does not mutate its input", () => {
  const size = 8;
  const reference = raster(size, size, () => [0, 0, 0, 0]);
  const before = Buffer.from(reference);
  applyBackdrop(reference, size, size, frameStage(size, size), { r: 255, g: 0, b: 0 });
  assert.deepEqual(reference, before);
});
