/**
 * The scorer's contract that does NOT need a browser: the verdict bands, and the fail-soft posture
 * when there is nothing to score with.
 *
 * The scoring itself is deliberately not re-asserted here. Its whole design is that it runs the
 * viewer's own `format-compare.js` rather than a copy, so a test that pinned expected percentages
 * would be testing that asset — and would have to be rewritten every time the scorer legitimately
 * improved, which is exactly the pressure that produces a second, drifting implementation.
 */
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { test } from "node:test";

import { matchBand, openScorer } from "./design-reference-score.mjs";

test("the verdict bands come from a real catalog's distribution", () => {
  // Across m3-catalog's 120 published pairs the median match is 99.70% and 72 sit at or above
  // 99.5, so `match` is the "nothing to look at" majority rather than a perfection test. The 8
  // below 97 are the genuine divergences — a 57.98% corner-radius sheet, a 72.80% colour grid.
  assert.equal(matchBand(100), "match");
  assert.equal(matchBand(99.5), "match");
  assert.equal(matchBand(99.49), "close");
  assert.equal(matchBand(97), "close");
  assert.equal(matchBand(96.99), "off");
  assert.equal(matchBand(57.98), "off");
  assert.equal(matchBand(0), "off");
});

test("a band is never invented for a number that isn't one", () => {
  // The band decides a colour on a chip. Answering for NaN would paint a verdict over a producer
  // bug instead of leaving the chip unmarked.
  assert.equal(matchBand(NaN), null);
  assert.equal(matchBand(undefined), null);
  assert.equal(matchBand(null), null);
  assert.equal(matchBand("99"), null);
});

test("the driver reads the viewer's own comparison asset, not a copy of it", () => {
  // The single most important property of this module: one scorer, so the number baked onto the
  // chip and the number the lane computes live cannot disagree. If this path ever stops resolving,
  // scoring must go dark rather than fall back to some other implementation of the same question.
  const asset = path.resolve(
    path.dirname(fileURLToPath(import.meta.url)),
    "../../cli/src/main/resources/ee/schimke/composeai/cli/serve/assets/format-compare.js",
  );
  assert.ok(fs.existsSync(asset), `${asset} is where the scorer expects the viewer's asset`);
  const source = fs.readFileSync(asset, "utf8");
  // The two entry points the in-page evaluation calls. A rename upstream would otherwise surface as
  // every reference silently publishing without a score.
  for (const api of ["scoreImages", "normaliseImageUrls", "diffCanvases", "loadImage"]) {
    assert.ok(
      source.includes(`${api}: ${api}`),
      `format-compare.js still exports ${api} on window.ComposePreviewCompare`,
    );
  }
});

test("no browser yields no scorer, not a thrown export", async () => {
  // Fail-soft is the whole posture of the reference lane: a fork with no Chromium still publishes
  // its references, just without the baked number, and the viewer scores live on entry as before.
  const messages = [];
  const scorer = await openScorer({
    executablePath: "/nonexistent/chromium",
    log: (message) => messages.push(message),
  });
  assert.equal(scorer, null);
  assert.equal(messages.length, 1, "the reason is said out loud exactly once");
  assert.match(messages[0], /cannot score references/);
});
