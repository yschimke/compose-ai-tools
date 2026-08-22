import { test } from "node:test";
import assert from "node:assert/strict";

import { scaleFinding, scaleMessage } from "./reference-scale.mjs";

const box = (width, height) => ({ x: 0, y: 0, width, height });

test("a matched pair is not a finding", () => {
  assert.equal(scaleFinding(box(219, 84), box(219, 84)), null);
});

test("the m3-catalog#180 signature is a finding", () => {
  // button-filled__ideal__xs as published: the reference drew 145x56 against a 219x84 render.
  const finding = scaleFinding(box(145, 56), box(219, 84));
  assert.deepEqual(finding, { scale: 0.664, widthRatio: 0.662, heightRatio: 0.667 });
});

test("a difference in proportion is left to the scorer's geometry", () => {
  // splitbutton-filled__ideal__xl: 371x126 against 1049x105. Both axes are off, in opposite
  // directions — that is a reshape, and reporting it as a scale would be a wrong diagnosis.
  assert.equal(scaleFinding(box(371, 126), box(1049, 105)), null);
});

test("a pixel of antialiasing on a small component is not a finding", () => {
  // 2px on a 25px checkmark clears the relative threshold on its own; the floor holds it back.
  assert.equal(scaleFinding(box(27, 27), box(25, 25)), null);
});

test("a real divergence on a small component still is one", () => {
  assert.ok(scaleFinding(box(25, 25), box(53, 53)));
});

test("a sub-percent rounding difference on a large one is not", () => {
  assert.equal(scaleFinding(box(218, 84), box(219, 84)), null);
});

test("the tolerance is caller-tunable", () => {
  assert.equal(scaleFinding(box(100, 100), box(105, 105), { tolerance: 0.1 }), null);
  assert.ok(scaleFinding(box(100, 100), box(105, 105), { tolerance: 0.01 }));
});

test("a reference larger than its sticker is a finding too", () => {
  const finding = scaleFinding(box(300, 150), box(200, 100));
  assert.equal(finding.scale, 1.5);
});

test("an undrawn side cannot be compared", () => {
  assert.equal(scaleFinding(null, box(10, 10)), null);
  assert.equal(scaleFinding(box(10, 10), null), null);
  assert.equal(scaleFinding(box(0, 10), box(10, 10)), null);
});

test("the message names both sides and the factor", () => {
  const message = scaleMessage(
    "button-filled__ideal__xs",
    scaleFinding(box(145, 56), box(219, 84)),
    box(145, 56),
    box(219, 84),
  );
  assert.match(message, /button-filled__ideal__xs/);
  assert.match(message, /145x56/);
  assert.match(message, /219x84/);
  assert.match(message, /0\.664x/);
});
