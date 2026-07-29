import { test } from "node:test";
import assert from "node:assert/strict";

import { CAPTURE_MODES, captureMode, isAnimatedCapture } from "./capture-mode.mjs";

test("absent capture reads as static — the strict default", () => {
  assert.equal(captureMode({ componentId: "Button/Filled", preview: "FilledButton" }), "static");
  assert.equal(isAnimatedCapture({ preview: "FilledButton" }), false);
  assert.equal(isAnimatedCapture(undefined), false);
});

test("an explicit capture is read back", () => {
  assert.equal(captureMode({ capture: "animated" }), "animated");
  assert.equal(isAnimatedCapture({ capture: "animated" }), true);
  assert.equal(isAnimatedCapture({ capture: "static" }), false);
});

test("only the declared modes exist — a typo is not animated", () => {
  assert.deepEqual(CAPTURE_MODES, ["static", "animated"]);
  // The spec validator rejects these outright (see catalog-spec.test.mjs); the
  // consumers must not treat a near-miss as an exemption in the meantime.
  assert.equal(isAnimatedCapture({ capture: "animation" }), false);
  assert.equal(isAnimatedCapture({ capture: "gif" }), false);
});
