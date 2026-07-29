import { test } from "node:test";
import assert from "node:assert/strict";

import { CAPTURE_MODES, captureMode, exportsNoSticker } from "./capture-mode.mjs";

test("absent capture reads as static — the strict default", () => {
  assert.equal(captureMode({ componentId: "Button/Filled", preview: "FilledButton" }), "static");
  assert.equal(exportsNoSticker({ preview: "FilledButton" }), false);
  assert.equal(exportsNoSticker(undefined), false);
});

test("an explicit capture is read back", () => {
  assert.equal(captureMode({ capture: "none" }), "none");
  assert.equal(exportsNoSticker({ capture: "none" }), true);
  assert.equal(exportsNoSticker({ capture: "static" }), false);
});

test("only the declared modes exist — a typo is not an exemption", () => {
  assert.deepEqual(CAPTURE_MODES, ["static", "none"]);
  // The spec validator rejects these outright (see catalog-spec.test.mjs); the
  // consumers must not treat a near-miss as an exemption in the meantime.
  assert.equal(exportsNoSticker({ capture: "animated" }), false);
  assert.equal(exportsNoSticker({ capture: "gif" }), false);
  assert.equal(exportsNoSticker({ capture: "None" }), false);
});
