/**
 * The bug this pins: dropping `--disable-frame-rate-limit` from the render lanes' Chromium launch.
 *
 * Without it Chromium paces `requestAnimationFrame` at roughly 1 fps for a page whose CSS viewport
 * is only a few dozen pixels tall. The CMP/Wasm player waits three frames before reporting
 * readiness, so the 76 dp-tall widget previews in `remote-m3` took ~6,150 ms instead of ~1,980 ms —
 * a *warm* render over the lane's 5,000 ms warm first-frame budget (issue #3445). It is a
 * correctness flag for the measurement, not a speed knob, so it must not be dropped as noise.
 *
 * The slow-viewport behaviour itself is exercised end-to-end by rc-cmp-wasm-frame-pacing.test.mjs,
 * which needs a built player distribution; this one always runs.
 *
 * Run with `node --test scripts/design-artifacts/*.test.mjs`.
 */
import assert from "node:assert/strict";
import { test } from "node:test";

import { CHROMIUM_LAUNCH_ARGS } from "./rc-chromium.mjs";

test("the shared launch args uncap the compositor frame rate", () => {
  assert.ok(
    CHROMIUM_LAUNCH_ARGS.includes("--disable-frame-rate-limit"),
    "short viewports get ~1 fps frame pacing without it, inflating first-frame measurements",
  );
});

test("the shared launch args keep the software GL fallback headless containers need", () => {
  assert.ok(CHROMIUM_LAUNCH_ARGS.includes("--enable-unsafe-swiftshader"));
  assert.ok(CHROMIUM_LAUNCH_ARGS.includes("--no-sandbox"));
});

test("the shared launch args are frozen, so a lane cannot mutate what other lanes measure with", () => {
  assert.throws(() => CHROMIUM_LAUNCH_ARGS.push("--headless=old"));
});
