/**
 * rc-settle.test.mjs — the convergence rule itself, without a browser.
 *
 * The browser-level consequence (a text row captured at `ready` is not the row a settled render
 * produces) is guarded in `rc-cmp-wasm-document-swap.test.mjs`, which needs a built player and can
 * therefore skip. The *rule* — quiet window, not a single repeat; a timeout that yields rather than
 * throws — is arithmetic, so it is pinned here where nothing can skip it.
 */
import { test } from "node:test";
import assert from "node:assert/strict";

import { settledScreenshot } from "./rc-settle.mjs";

/** A page whose screenshots follow `frames`, advancing a fake clock `stepMs` per capture. */
function fakePage(frames, stepMs = 10) {
  const clock = { ms: 0 };
  let index = 0;
  return {
    clock,
    captures: () => index,
    now: () => clock.ms,
    page: {
      async screenshot() {
        const frame = frames[Math.min(index, frames.length - 1)];
        index++;
        clock.ms += stepMs;
        return Buffer.from(frame);
      },
    },
  };
}

test("returns once the pixels have held still for the whole quiet window", async () => {
  const { page, now } = fakePage(["a", "b", "c", "c", "c", "c", "c", "c"]);
  const result = await settledScreenshot(page, { quietMs: 30, now });
  assert.equal(result.buffer.toString(), "c");
  assert.equal(result.settled, true);
});

test("a repeat inside the window is not settlement", async () => {
  // "b" repeats once — a single-repeat rule would stop there and return the intermediate draw,
  // which is exactly the failure the quiet window exists to prevent.
  const { page, now } = fakePage(["a", "b", "b", "c", "c", "c", "c", "c", "c"]);
  const result = await settledScreenshot(page, { quietMs: 30, now });
  assert.equal(result.buffer.toString(), "c");
});

test("a page that never settles yields its last capture instead of throwing", async () => {
  let frame = 0;
  const clock = { ms: 0 };
  const page = {
    async screenshot() {
      clock.ms += 10;
      return Buffer.from(`frame-${frame++}`);
    },
  };
  const result = await settledScreenshot(page, {
    quietMs: 30,
    timeoutMs: 100,
    now: () => clock.ms,
  });
  assert.equal(result.settled, false);
  assert.ok(result.buffer.toString().startsWith("frame-"), "the last capture is still returned");
  assert.ok(result.settleMs > 100, "and the time spent trying is reported");
});

test("a converged frame the expectation rejects is not settlement", async () => {
  // The #3558 shape: the player paints the document's shapes and none of its text, so the frame is
  // stable from the first capture and every convergence test passes on a render that never
  // happened. `blank` here stands for that frame; `drawn` is the one worth scoring.
  const { page, now } = fakePage(["blank", "blank", "blank", "blank", "drawn", "drawn", "drawn"]);
  const result = await settledScreenshot(page, {
    quietMs: 20,
    timeoutMs: 1_000,
    expectation: (buffer) => buffer.toString() !== "blank",
    now,
  });
  assert.equal(result.buffer.toString(), "drawn");
  assert.equal(result.settled, true);
});

test("an expectation that never holds gives up at the timeout rather than blocking", async () => {
  // A render that stays blank is a real outcome — the swap path used to produce it permanently —
  // and the caller has to hear about it. Reporting `settled: false` is what lets `rc-compare` fail
  // the row instead of scoring a blank capture as a parity number.
  const { page, now } = fakePage(["blank", "blank", "blank", "blank", "blank"]);
  const result = await settledScreenshot(page, {
    quietMs: 20,
    timeoutMs: 100,
    expectation: (buffer) => buffer.toString() !== "blank",
    now,
  });
  assert.equal(result.settled, false);
  assert.equal(result.buffer.toString(), "blank");
});

test("no expectation keeps the previous behaviour exactly", async () => {
  const { page, now } = fakePage(["a", "b", "b", "b", "b", "b"]);
  const result = await settledScreenshot(page, { quietMs: 30, now });
  assert.equal(result.buffer.toString(), "b");
  assert.equal(result.settled, true);
});
