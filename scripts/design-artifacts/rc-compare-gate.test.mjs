import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import {
  applyCmpWasmPerformanceBudgets,
  evaluateCmpWasmGate,
  formatCmpWasmGate,
  readCmpWasmAllowlist,
  summarizeCmpWasmPixelParity,
} from "./rc-compare-gate.mjs";

test("pixel parity is reported, never gated", () => {
  // The whole point of the change this covers: a row three times over the advisory line is listed,
  // and the lane still passes. Publication must not hinge on a number the publish job is the first
  // thing to see.
  const rows = [
    { id: "exact", cmpWasmRendered: true, cmpWasmMismatchPct: 0.4 },
    { id: "drifted", cmpWasmRendered: true, cmpWasmMismatchPct: 3.1 },
    { id: "over", cmpWasmRendered: true, cmpWasmMismatchPct: 1.1 },
    { id: "blank-reference", cmpWasmRendered: true, referenceBlank: true, cmpWasmMismatchPct: 9 },
    { id: "unrendered", cmpWasmRendered: false, cmpWasmMismatchPct: null },
  ];

  const gate = summarizeCmpWasmPixelParity(
    evaluateCmpWasmGate(["exact", "drifted", "over"], rows),
    rows,
  );

  assert.equal(gate.passed, true);
  assert.deepEqual(gate.failures, []);
  assert.equal(gate.pixelParity.measured, 3);
  assert.deepEqual(
    gate.pixelParity.above.map((row) => row.id),
    ["drifted", "over"],
  );
  assert.match(formatCmpWasmGate(gate), /pixel parity \(report-only\): 2 of 3 row\(s\) above 1%/);
});

test("cold and warm first-frame budgets fail on the slowest measured render", () => {
  const rows = [
    { id: "cold", cmpWasmRendered: true, cmpWasmStartup: "cold", cmpWasmFirstFrameMs: 9001 },
    { id: "warm-a", cmpWasmRendered: true, cmpWasmStartup: "warm", cmpWasmFirstFrameMs: 1200 },
    { id: "warm-b", cmpWasmRendered: true, cmpWasmStartup: "warm", cmpWasmFirstFrameMs: 5200 },
  ];
  const gate = applyCmpWasmPerformanceBudgets(
    evaluateCmpWasmGate(rows.map((row) => row.id), rows),
    rows,
    10_000,
    5_000,
  );

  assert.equal(gate.passed, false);
  assert.deepEqual(gate.failures, [
    {
      id: "performance-warm",
      note: "warm first frame 5200 ms exceeds 5000 ms budget (warm-b)",
    },
  ]);
});

test("an enabled first-frame budget requires a measurement", () => {
  const gate = applyCmpWasmPerformanceBudgets(
    evaluateCmpWasmGate([], []),
    [],
    10_000,
    null,
  );

  assert.equal(gate.passed, false);
  assert.deepEqual(gate.failures, [
    { id: "performance-cold", note: "no cold first-frame measurement was recorded" },
  ]);
});

test("strict CMP/Wasm gate reports failed and dropped rows", () => {
  const gate = evaluateCmpWasmGate(
    ["ready", "error", "dropped"],
    [
      { id: "ready", cmpWasmRendered: true },
      { id: "error", cmpWasmRendered: false, cmpWasmNote: "opcode 150 at byte 42" },
    ],
  );
  assert.equal(gate.passed, false);
  assert.deepEqual(gate.failures, [
    { id: "error", note: "opcode 150 at byte 42" },
    { id: "dropped", note: "row was dropped" },
  ]);
  assert.match(formatCmpWasmGate(gate), /1\/3 rendered.*2 failed[\s\S]*opcode 150 at byte 42/);
});

test("a reasoned, unexpired exception allows only its named row", () => {
  const allowlist = new Map([
    ["known", { reason: "tracked by #123", expires: "2026-08-10" }],
  ]);
  const gate = evaluateCmpWasmGate(
    ["known", "unknown"],
    [
      { id: "known", cmpWasmRendered: false, cmpWasmNote: "known failure" },
      { id: "unknown", cmpWasmRendered: false, cmpWasmNote: "new failure" },
    ],
    allowlist,
  );
  assert.equal(gate.passed, false);
  assert.equal(gate.allowed.length, 1);
  assert.deepEqual(gate.failures, [{ id: "unknown", note: "new failure" }]);
  assert.equal(
    evaluateCmpWasmGate(
      ["known"],
      [{ id: "known", cmpWasmRendered: false, cmpWasmNote: "known failure" }],
      allowlist,
    ).passed,
    true,
  );
});

test("allowlist requires reasons and rejects expired entries", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "rc-cmp-wasm-gate-"));
  const file = path.join(dir, "allowlist.json");
  fs.writeFileSync(file, JSON.stringify({ entries: [{ id: "x", reason: "", expires: "2026-08-10" }] }));
  assert.throws(() => readCmpWasmAllowlist(file, "2026-08-03"), /non-empty reason/);
  fs.writeFileSync(
    file,
    JSON.stringify({ entries: [{ id: "x", reason: "tracked", expires: "2026-08-02" }] }),
  );
  assert.throws(() => readCmpWasmAllowlist(file, "2026-08-03"), /expired/);
});
