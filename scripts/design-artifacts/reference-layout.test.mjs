import assert from "node:assert/strict";
import test from "node:test";

import { scaleTree } from "./reference-layout.mjs";

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
