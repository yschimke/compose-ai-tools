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
  // Across wear-m3-catalog's 186 published pairs the median match is 91 and 63 sit at or above 95,
  // so `match` is the "nothing to look at" majority rather than a perfection test. The 59 below 85
  // are the genuine divergences — a 4% scroll indicator, a 52% picker, a 70% stepper.
  assert.equal(matchBand(100), "match");
  assert.equal(matchBand(95), "match");
  assert.equal(matchBand(94.99), "close");
  assert.equal(matchBand(85), "close");
  assert.equal(matchBand(84.99), "off");
  assert.equal(matchBand(51.6), "off");
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

// Resolved the same way `design-reference-score.mjs` resolves it: the asset moved to
// yschimke/compose-preview-server with the server (#4732), so it lives in an optional sibling
// checkout. Absent ⇒ SKIP with a reason, matching the module's own go-dark behaviour; a silent pass
// would be the copy-drift this test exists to catch.
const SERVER_ROOT =
  (process.env.COMPOSE_PREVIEW_SERVER_ROOT ?? "").trim() ||
  path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../../compose-preview-server");
const COMPARE_ASSET = path.join(
  SERVER_ROOT,
  "server/src/main/resources/ee/schimke/composeai/cli/serve/assets/format-compare.js",
);
const NO_SERVER = {
  skip: fs.existsSync(COMPARE_ASSET)
    ? false
    : "no compose-preview-server checkout (set COMPOSE_PREVIEW_SERVER_ROOT) — the viewer asset " +
      "lives there since #4732",
};

test("the driver reads the viewer's own comparison asset, not a copy of it", NO_SERVER, () => {
  // The single most important property of this module: one scorer, so the number baked onto the
  // chip and the number the lane computes live cannot disagree. If this path ever stops resolving,
  // scoring must go dark rather than fall back to some other implementation of the same question.
  const asset = COMPARE_ASSET;
  assert.ok(fs.existsSync(asset), `${asset} is where the scorer expects the viewer's asset`);
  const source = fs.readFileSync(asset, "utf8");
  // The asset is BUILT (from the server repo's `serve-web/src/scorer/`), so this is a grep over minified
  // output. Property names survive minification because they are the published contract — that is
  // exactly what is being checked. The build's own type annotation on `window.ComposePreviewCompare`
  // catches a rename inside the repo; this catches the case that annotation cannot see, which is
  // this driver reading a built file it does not participate in building.
  assert.ok(
    /window\.ComposePreviewCompare\s*=/.test(source),
    "format-compare.js still publishes the comparison API as a global",
  );
  // The entry points the in-page evaluation calls. A rename upstream would otherwise surface as
  // every reference silently publishing without a score.
  for (const api of ["scoreImages", "normaliseImageUrls", "diffCanvases", "loadImage"]) {
    assert.ok(
      new RegExp(`\\b${api}\\s*:`).test(source),
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
