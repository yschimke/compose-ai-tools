import { test } from "node:test";
import assert from "node:assert/strict";

import {
  CARRIAGE_GATE_THRESHOLD_PERCENT,
  SPLIT_MODES,
  evaluateSplitCarriage,
} from "./split-carriage-gate.mjs";

/** m3-catalog's measured numbers — the run this gate exists because of. */
const M3 = {
  mode: "full",
  bundles: 1296,
  carriageBytesPerBundle: 625114,
  repeatedBytes: 810147744,
  totalBytes: 828091478,
  sharePercent: 97.8,
};

/** A small catalog: the carriage is there, but it is not the story. */
const SMALL = {
  mode: "full",
  bundles: 88,
  carriageBytesPerBundle: 600000,
  repeatedBytes: 52800000,
  totalBytes: 220000000,
  sharePercent: 24,
};

test("the default fails once the carriage dominates, and names both explicit options", () => {
  const result = evaluateSplitCarriage({ mode: "auto", report: M3, system: "m3" });

  assert.equal(result.level, "error");
  const text = [result.headline, ...result.details].join("\n");
  assert.match(text, /625114 bytes in each of 1296 bundle\(s\)/);
  assert.match(text, /97\.8%/);
  assert.match(text, /split-mode: full-shared-classpath/);
  assert.match(text, /split-mode: full\b/);
  assert.match(text, /design-artifacts\/m3/);
});

test("the default is unchanged for a catalog below the bound", () => {
  const result = evaluateSplitCarriage({ mode: "auto", report: SMALL });

  assert.equal(result.level, "notice");
  assert.match(result.headline, /resolved to full/);
  assert.match(result.headline, /Bound is 50%/);
});

test("an explicit mode is never failed — the point is that the choice is recorded", () => {
  for (const mode of ["full", "full-shared-classpath", "view-only"]) {
    const result = evaluateSplitCarriage({ mode, report: { ...M3, mode } });
    assert.equal(result.level, "notice", `${mode} must not fail`);
    assert.match(result.headline, new RegExp(`split-mode: ${mode}`));
  }
});

test("explicit full still says what it is paying, so the acceptance stays visible", () => {
  const result = evaluateSplitCarriage({ mode: "full", report: M3 });

  assert.match(result.details.join(" "), /Chosen explicitly/);
  assert.match(result.details.join(" "), /full-shared-classpath/);
});

test("a single-bundle split repeats nothing, whatever the share arithmetic says", () => {
  const result = evaluateSplitCarriage({
    mode: "auto",
    report: {
      bundles: 1,
      carriageBytesPerBundle: 900000,
      repeatedBytes: 900000,
      totalBytes: 1000000,
      sharePercent: 90,
    },
  });

  assert.equal(result.level, "notice");
});

test("exactly at the bound fails — the threshold is inclusive on both sides of the pair", () => {
  const atBound = {
    bundles: 100,
    carriageBytesPerBundle: 1000,
    repeatedBytes: 100000,
    totalBytes: 200000,
    sharePercent: CARRIAGE_GATE_THRESHOLD_PERCENT,
  };
  assert.equal(evaluateSplitCarriage({ mode: "auto", report: atBound }).level, "error");
});

test("a CLI too old to measure warns instead of blocking the publish", () => {
  const result = evaluateSplitCarriage({ mode: "auto", report: null });

  assert.equal(result.level, "warning");
  assert.match(result.headline, /--carriage-report/);
  assert.match(result.details.join(" "), /cli-version/);
});

test("no report and an explicit mode has nothing to say", () => {
  assert.equal(evaluateSplitCarriage({ mode: "full", report: null }).level, "ok");
  assert.equal(evaluateSplitCarriage({ mode: "view-only", report: null }).level, "ok");
});

test("an unknown mode is rejected rather than treated as the default", () => {
  const result = evaluateSplitCarriage({ mode: "shared", report: SMALL });

  assert.equal(result.level, "error");
  assert.match(result.headline, /unknown split-mode 'shared'/);
  assert.deepEqual(SPLIT_MODES, ["auto", "full", "full-shared-classpath", "view-only"]);
});
