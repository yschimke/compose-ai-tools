import assert from "node:assert/strict";
import test from "node:test";

import {
  publishTransform,
  scaleTree,
  transformAnnotations,
  transformBounds,
} from "./reference-layout.mjs";

const tree = {
  root: {
    role: "frame",
    bounds: { x: 0, y: 0, width: 200, height: 48 },
    children: [
      { role: "text", bounds: { x: 46, y: 26, width: 108, height: 20 } },
      { role: "icon" }, // no bounds — must survive the walk without inventing any
    ],
  },
};

test("scales every box by the ratio the resample used", () => {
  // A 200dp frame published as a 400px sticker: everything doubles, or the annotations sit at half
  // the position of what they describe.
  const scaled = scaleTree(tree, 400);
  assert.deepEqual(scaled.root.bounds, { x: 0, y: 0, width: 400, height: 96 });
  assert.deepEqual(scaled.root.children[0].bounds, { x: 92, y: 52, width: 216, height: 40 });
});

test("is identity when the frame already matches the published width", () => {
  const scaled = scaleTree(tree, 200);
  assert.deepEqual(scaled.root.bounds, tree.root.bounds);
  assert.deepEqual(scaled.root.children[0].bounds, tree.root.children[0].bounds);
});

test("leaves an unbounded node unbounded rather than inventing a box", () => {
  assert.equal(scaleTree(tree, 400).root.children[1].bounds, undefined);
});

test("preserves non-geometry fields so labels and roles survive", () => {
  const withTokens = {
    root: {
      ...tree.root,
      tokens: { spacing: { padding: 16 } },
      children: [{ role: "text", label: "Send", bounds: { x: 0, y: 0, width: 10, height: 10 } }],
    },
  };
  const scaled = scaleTree(withTokens, 400);
  assert.deepEqual(scaled.root.tokens, { spacing: { padding: 16 } });
  assert.equal(scaled.root.children[0].label, "Send");
});

test("keeps the tree's density, which describes tokens rather than boxes", () => {
  // Only bounds are relocated into the raster; the specs the annotations quote stay in the
  // design's own pixels, so the factor that converts them must survive unchanged.
  const scaled = scaleTree({ ...tree, density: 3 }, 400);
  assert.equal(scaled.density, 3);
});

test("returns undefined when there is no frame to scale against", () => {
  // Nothing to anchor a ratio to; annotating with an assumed scale would be worse than not at all.
  assert.equal(scaleTree(undefined, 400), undefined);
  assert.equal(scaleTree({ root: {} }, 400), undefined);
  assert.equal(scaleTree({ root: { bounds: { x: 0, y: 0, width: 0, height: 0 } } }, 400), undefined);
});

test("offsets every box by where the artwork sits in a letterboxed raster", () => {
  // A reference whose proportions differ from the sticker's is scaled to fit and centred, so the
  // annotations have to move with the artwork rather than with the canvas.
  const scaled = scaleTree(tree, 400, 0, 60);
  assert.deepEqual(scaled.root.bounds, { x: 0, y: 60, width: 400, height: 96 });
  assert.deepEqual(scaled.root.children[0].bounds, { x: 92, y: 112, width: 216, height: 40 });
});

test("no offset is the default, so a full-bleed reference is unaffected", () => {
  assert.deepEqual(scaleTree(tree, 400).root.bounds, scaleTree(tree, 400, 0, 0).root.bounds);
});

// ---- publishTransform / transformBounds / transformAnnotations ----------------------------------
//
// The half of this module that carries geometry captured by SOMEBODY ELSE — an adapter's annotation
// layer, a finding's anchor — out of the source raster's pixels and onto the raster published
// (#4696). `scaleTree` above works from the design frame's own units; these work from the raster's.

test("no transform for a reference published exactly as it arrived", () => {
  // Identity is null, not a unit transform: the manifests of a reference nothing moved must stay
  // byte-identical to what they are today, and that is easiest to guarantee by not writing a field.
  assert.equal(publishTransform(400, 800, { width: 400, height: 800, x: 0, y: 0 }), null);
});

test("states the factor a rounding resample applied", () => {
  // 402x800 stretched onto the sticker's 400x800: both axes move, neither by the same amount.
  assert.deepEqual(publishTransform(402, 800, { width: 400, height: 800, x: 0, y: 0 }), {
    scaleX: 0.995025,
    scaleY: 1,
    offsetX: 0,
    offsetY: 0,
  });
});

test("states the factor and the offset a letterboxed fit applied", () => {
  assert.deepEqual(publishTransform(200, 200, { width: 400, height: 400, x: 0, y: 200 }), {
    scaleX: 2,
    scaleY: 2,
    offsetX: 0,
    offsetY: 200,
  });
});

test("a centred placement is a transform even when nothing was rescaled", () => {
  // `placeRgba` centres a density-matched export without enlarging it. The scale is 1 and the
  // artwork still moved, so a box that ignored the offset would sit off it by the whole margin.
  assert.deepEqual(publishTransform(100, 40, { width: 100, height: 40, x: 150, y: 180 }), {
    scaleX: 1,
    scaleY: 1,
    offsetX: 150,
    offsetY: 180,
  });
});

test("no transform for dimensions that describe nothing", () => {
  assert.equal(publishTransform(0, 800, { width: 400, height: 800 }), null);
  assert.equal(publishTransform(400, 800, undefined), null);
  assert.equal(publishTransform(400, 800, { width: 0, height: 800 }), null);
});

test("moves a box by the transform the raster went through", () => {
  const transform = { scaleX: 2, scaleY: 2, offsetX: 0, offsetY: 200 };
  assert.deepEqual(transformBounds({ x: 10, y: 20, width: 30, height: 40 }, transform), {
    x: 20,
    y: 240,
    width: 60,
    height: 80,
  });
});

test("returns the very box it was given when nothing moved", () => {
  // Identity is pass-through by reference, so an unrescaled reference cannot even be re-rounded.
  const bounds = { x: 10, y: 20, width: 30, height: 40 };
  assert.equal(transformBounds(bounds, null), bounds);
});

test("keeps a box drawable when a reduction would round it away", () => {
  // A 1px hairline under a 0.3x reduction rounds to zero, and a zero-area rectangle is invisible
  // rather than honest — the reader loses the annotation and is told nothing.
  const thin = transformBounds({ x: 4, y: 4, width: 1, height: 1 }, { scaleX: 0.3, scaleY: 0.3 });
  assert.deepEqual(thin, { x: 1, y: 1, width: 1, height: 1 });
});

test("moves every annotation's box and leaves the rest of it alone", () => {
  const layer = [
    { kind: "layout", label: "pad 16dp", bounds: { x: 0, y: 0, width: 100, height: 50 } },
    { kind: "typography", label: "bodyLarge 16sp", detail: { unit: "sp" } },
  ];
  const moved = transformAnnotations(layer, { scaleX: 2, scaleY: 2, offsetX: 0, offsetY: 10 });
  assert.deepEqual(moved[0].bounds, { x: 0, y: 10, width: 200, height: 100 });
  assert.equal(moved[0].label, "pad 16dp");
  // No bounds, nothing to move — and nothing invented either.
  assert.deepEqual(moved[1], layer[1]);
});

test("passes an annotation layer straight through when nothing moved", () => {
  const layer = [{ kind: "layout", bounds: { x: 0, y: 0, width: 10, height: 10 } }];
  assert.equal(transformAnnotations(layer, null), layer);
});

test("clips a box the placement cropped rather than letting it be discarded", () => {
  // `placeRgba` crops an empty margin by placing the source at a NEGATIVE offset — the m3-catalog
  // touch-target case is `{ width: 218, height: 126, x: 0, y: -21 }`. A box spanning that margin
  // transforms to a negative origin, and both `ServeAnnotationStore` and `ServeParityFindingStore`
  // discard a box with one, so the annotation would vanish instead of moving.
  const transform = { scaleX: 1, scaleY: 1, offsetX: 0, offsetY: -21 };
  const canvas = { width: 219, height: 84 };
  assert.deepEqual(transformBounds({ x: 0, y: 0, width: 218, height: 126 }, transform, canvas), {
    x: 0,
    y: 0,
    width: 218,
    height: 84,
  });
});

test("drops a box the placement cropped away entirely", () => {
  // Nothing of it is published, so there is nowhere honest to draw it.
  const transform = { scaleX: 1, scaleY: 1, offsetX: 0, offsetY: -21 };
  const gone = transformBounds({ x: 0, y: 0, width: 218, height: 20 }, transform, {
    width: 219,
    height: 84,
  });
  assert.equal(gone, null);
});

test("clips a box that runs off the far edge too", () => {
  const clipped = transformBounds({ x: 90, y: 0, width: 40, height: 10 }, { scaleX: 2, scaleY: 2 }, {
    width: 200,
    height: 100,
  });
  assert.deepEqual(clipped, { x: 180, y: 0, width: 20, height: 20 });
});

test("clips nothing without a canvas to clip to", () => {
  // The canvas is optional, and absent it the box is moved and left alone rather than guessed at.
  assert.deepEqual(
    transformBounds({ x: 0, y: 0, width: 10, height: 10 }, { scaleX: 1, scaleY: 1, offsetY: -21 }),
    { x: 0, y: -21, width: 10, height: 10 },
  );
});

test("drops an annotation whose box the crop removed, and keeps the rest", () => {
  const layer = [
    { kind: "layout", label: "in the margin", bounds: { x: 0, y: 0, width: 218, height: 20 } },
    { kind: "layout", label: "on the button", bounds: { x: 0, y: 21, width: 218, height: 84 } },
  ];
  const moved = transformAnnotations(layer, { scaleX: 1, scaleY: 1, offsetX: 0, offsetY: -21 }, {
    width: 219,
    height: 84,
  });
  assert.equal(moved.length, 1);
  assert.equal(moved[0].label, "on the button");
  assert.deepEqual(moved[0].bounds, { x: 0, y: 0, width: 218, height: 84 });
});
