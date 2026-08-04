import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import {
  evaluateCmpWasmGate,
  formatCmpWasmGate,
  readCmpWasmAllowlist,
} from "./rc-compare-gate.mjs";

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
