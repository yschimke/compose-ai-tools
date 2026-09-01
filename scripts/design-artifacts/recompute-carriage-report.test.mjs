import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";

import { manifestBundleBytes, restateCarriage } from "./recompute-carriage-report.mjs";

import { evaluateSplitCarriage } from "./split-carriage-gate.mjs";

/** A previews/ directory plus the manifest the workflow snapshots after the PRIMARY split. */
function split(primarySizes, extraSizes = []) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "carriage-"));
  const primary = primarySizes.map((size, i) => {
    const file = path.join(dir, `p${i}.png`);
    fs.writeFileSync(file, Buffer.alloc(size));
    return file;
  });
  const manifest = path.join(dir, "primary.txt");
  fs.writeFileSync(manifest, `${primary.join("\n")}\n`);
  // Written into the SAME directory afterwards, exactly as the extra-module split does.
  extraSizes.forEach((size, i) =>
    fs.writeFileSync(path.join(dir, `extra${i}.png`), Buffer.alloc(size)),
  );
  return { dir, manifest, primary };
}

test("measures only the manifested primary bundles, not the extra split beside them", () => {
  // The extra-module split writes into the same previews/ directory, and the carriage report
  // describes the primary split alone. Counting the extra bundles would inflate the denominator
  // and understate the share — the same understatement this whole step exists to correct.
  const { manifest } = split([100, 250], [9000, 9000]);

  assert.deepEqual(manifestBundleBytes(manifest), { bytes: 350, files: 2, missing: 0 });
});

test("a manifested bundle that has vanished is counted as missing, not thrown on", () => {
  const { manifest, primary } = split([100, 250]);
  fs.rmSync(primary[1]);

  assert.deepEqual(manifestBundleBytes(manifest), { bytes: 100, files: 2, missing: 1 });
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
