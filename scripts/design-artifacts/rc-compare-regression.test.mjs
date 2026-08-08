import assert from "node:assert/strict";
import test from "node:test";

import {
  compareCmpWasmParity,
  formatCmpWasmRegression,
} from "./rc-compare-regression.mjs";

const row = (id, extra) => ({ id, cmpWasmRendered: true, ...extra });

test("a jump beyond the delta fails; a standing divergence does not", () => {
  // `steady` is the case the old absolute gate got wrong: 3.1% every run, nobody's regression, and
  // it must not turn a PR red. `jumped` is the shape that actually happened on 7 Aug 2026 —
  // 0.26% → 2.17% from a font-resolution change — and it must.
  const baseline = [
    row("steady", { cmpWasmMismatchPct: 3.1 }),
    row("jumped", { cmpWasmMismatchPct: 0.26 }),
    row("drifted-a-little", { cmpWasmMismatchPct: 0.4 }),
  ];
  const current = [
    row("steady", { cmpWasmMismatchPct: 3.12 }),
    row("jumped", { cmpWasmMismatchPct: 2.17 }),
    row("drifted-a-little", { cmpWasmMismatchPct: 0.5 }),
  ];

  const result = compareCmpWasmParity(baseline, current, { maxIncreasePp: 0.25 });

  assert.equal(result.passed, false);
  assert.deepEqual(result.failures.map((f) => f.id), ["jumped"]);
  assert.match(result.failures[0].note, /0\.26% → 2\.17%/);
  assert.deepEqual(result.unchanged.map((u) => u.id).sort(), ["drifted-a-little", "steady"]);
});

test("a document that stops rendering fails regardless of pixels", () => {
  const baseline = [row("doc", { cmpWasmMismatchPct: 0.2 })];
  const current = [
    {
      id: "doc",
      cmpWasmRendered: false,
      cmpWasmMismatchPct: null,
      cmpWasmNote: "Unsupported operation at byte 110, opcode=150",
    },
  ];

  const result = compareCmpWasmParity(baseline, current);

  assert.equal(result.passed, false);
  assert.equal(result.failures[0].kind, "render");
  assert.match(result.failures[0].note, /opcode=150/);
});

test("rows the baseline never measured are reported, never judged", () => {
  // A preview added since the last publish, and one that could not render on the baseline either:
  // neither is evidence about this diff, so neither may fail it.
  const baseline = [
    row("kept", { cmpWasmMismatchPct: 0.5 }),
    { id: "was-broken", cmpWasmRendered: false, cmpWasmNote: "unsupported" },
    row("dropped", { cmpWasmMismatchPct: 0.5 }),
  ];
  const current = [
    row("kept", { cmpWasmMismatchPct: 0.5 }),
    row("was-broken", { cmpWasmMismatchPct: 9.9 }),
    row("brand-new", { cmpWasmMismatchPct: 8.8 }),
  ];

  const result = compareCmpWasmParity(baseline, current);

  assert.equal(result.passed, true);
  assert.deepEqual(result.added.map((a) => a.id), ["brand-new"]);
  assert.deepEqual(result.removed.map((r) => r.id), ["dropped"]);
  assert.deepEqual(
    result.improvements.map((i) => [i.id, i.note]),
    [["was-broken", "now renders"]],
  );
});

test("an improvement past the delta is reported as one", () => {
  const baseline = [row("fixed", { cmpWasmMismatchPct: 3.5 })];
  const current = [row("fixed", { cmpWasmMismatchPct: 0.3 })];

  const result = compareCmpWasmParity(baseline, current);

  assert.equal(result.passed, true);
  assert.match(formatCmpWasmRegression(result), /✓ fixed: 3\.50% → 0\.30%/);
});
