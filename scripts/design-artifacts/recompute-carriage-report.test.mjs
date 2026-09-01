import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";

import {
  publishedBundleBytes,
  restateCarriage,
} from "./recompute-carriage-report.mjs";

import { evaluateSplitCarriage } from "./split-carriage-gate.mjs";

function bundleDir(sizes) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "carriage-"));
  sizes.forEach((size, i) => fs.writeFileSync(path.join(dir, `p${i}.png`), Buffer.alloc(size)));
  return dir;
}

test("sums only the *.png bundles directly in the directory", () => {
  const dir = bundleDir([100, 250]);
  fs.writeFileSync(path.join(dir, "manifest.json"), "{}");
  fs.mkdirSync(path.join(dir, "nested"));
  fs.writeFileSync(path.join(dir, "nested", "deep.png"), Buffer.alloc(9999));

  assert.deepEqual(publishedBundleBytes(dir), { bytes: 350, files: 2 });
});

test("a missing directory measures as nothing rather than throwing", () => {
  assert.deepEqual(publishedBundleBytes("/definitely/not/here"), { bytes: 0, files: 0 });
});

test("restating after a strip raises the share the gate reads", () => {
  // The shape of #4930: the split measured 1000 bytes across two bundles while the figma-svg
  // sidecars were still inside them; stripping left 600. 480 repeated bytes is 48% of the
  // split-time total (under the bound) and 80% of what was actually published (over it).
  const report = {
    bundles: 2,
    carriageBytesPerBundle: 240,
    repeatedBytes: 480,
    totalBytes: 1000,
    sharePercent: 48,
    reportThresholdPercent: 50,
    dominates: false,
  };

  const { report: restated, changed, note } = restateCarriage(report, 600);

  assert.equal(changed, true);
  assert.equal(restated.totalBytes, 600);
  assert.equal(restated.sharePercent, 80);
  assert.equal(restated.dominates, true);
  assert.equal(restated.totalBytesAtSplit, 1000);
  assert.equal(restated.repeatedBytes, 480, "the shared carriage is not what a strip removes");
  assert.match(note, /1000 -> 600/);

  // And the gate, which is the whole point, now fails the default instead of passing it.
  assert.equal(evaluateSplitCarriage({ mode: "auto", report }).level, "notice");
  assert.equal(evaluateSplitCarriage({ mode: "auto", report: restated }).level, "error");
});

test("restating an unstripped split leaves the numbers alone", () => {
  const report = {
    bundles: 3,
    carriageBytesPerBundle: 10,
    repeatedBytes: 30,
    totalBytes: 900,
    sharePercent: 3.3,
    reportThresholdPercent: 50,
    dominates: false,
  };

  const { report: restated, changed, note } = restateCarriage(report, 900);

  assert.equal(changed, false);
  assert.equal(restated.sharePercent, 3.3);
  assert.equal(restated.dominates, false);
  assert.match(note, /unchanged/);
});

test("an explicit mode still only reports, never fails, after restating", () => {
  const report = { bundles: 2, repeatedBytes: 480, totalBytes: 1000, reportThresholdPercent: 50 };
  const { report: restated } = restateCarriage(report, 600);

  assert.equal(evaluateSplitCarriage({ mode: "full", report: restated }).level, "notice");
  assert.equal(
    evaluateSplitCarriage({ mode: "full-shared-classpath", report: restated }).level,
    "notice",
  );
});
