import fs from "node:fs";

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

/**
 * Read the deliberately small temporary-exception format used by the strict CMP/Wasm lane:
 *
 *   { "entries": [{ "id": "preview.id", "reason": "issue URL or explanation",
 *                   "expires": "2026-08-10" }] }
 */
export function readCmpWasmAllowlist(file, today = new Date().toISOString().slice(0, 10)) {
  if (!file) return new Map();
  const parsed = JSON.parse(fs.readFileSync(file, "utf8"));
  if (!parsed || !Array.isArray(parsed.entries)) {
    throw new Error("CMP/Wasm allowlist must be an object with an entries array");
  }
  const result = new Map();
  for (const [index, entry] of parsed.entries.entries()) {
    const label = `CMP/Wasm allowlist entry ${index + 1}`;
    if (!entry || typeof entry.id !== "string" || !entry.id.trim()) {
      throw new Error(`${label} must have a non-empty id`);
    }
    if (typeof entry.reason !== "string" || !entry.reason.trim()) {
      throw new Error(`${label} (${entry.id}) must have a non-empty reason`);
    }
    if (typeof entry.expires !== "string" || !ISO_DATE.test(entry.expires)) {
      throw new Error(`${label} (${entry.id}) must have an expires date in YYYY-MM-DD form`);
    }
    if (new Date(`${entry.expires}T00:00:00Z`).toISOString().slice(0, 10) !== entry.expires) {
      throw new Error(`${label} (${entry.id}) has an invalid expires date ${entry.expires}`);
    }
    if (entry.expires < today) {
      throw new Error(`${label} (${entry.id}) expired on ${entry.expires}`);
    }
    if (result.has(entry.id)) throw new Error(`${label} duplicates id ${entry.id}`);
    result.set(entry.id, { reason: entry.reason.trim(), expires: entry.expires });
  }
  return result;
}

/** Read reviewed, catalog-specific mismatch ceilings for rows above the strict 1% default. */
export function readCmpWasmPixelTolerances(file) {
  if (!file) return new Map();
  const parsed = JSON.parse(fs.readFileSync(file, "utf8"));
  if (!parsed || !Array.isArray(parsed.entries)) {
    throw new Error("CMP/Wasm pixel tolerances must be an object with an entries array");
  }
  const result = new Map();
  for (const [index, entry] of parsed.entries.entries()) {
    const label = `CMP/Wasm pixel tolerance ${index + 1}`;
    if (!entry || typeof entry.id !== "string" || !entry.id.trim()) {
      throw new Error(`${label} must have a non-empty id`);
    }
    if (!Number.isFinite(entry.maxMismatchPct) || entry.maxMismatchPct <= 1) {
      throw new Error(`${label} (${entry.id}) must set maxMismatchPct above the strict 1% default`);
    }
    if (typeof entry.classification !== "string" || !entry.classification.trim()) {
      throw new Error(`${label} (${entry.id}) must have a classification`);
    }
    if (typeof entry.reason !== "string" || !entry.reason.trim()) {
      throw new Error(`${label} (${entry.id}) must have a reason`);
    }
    if (result.has(entry.id)) throw new Error(`${label} duplicates id ${entry.id}`);
    result.set(entry.id, entry);
  }
  return result;
}

/** Return the strict-lane verdict without throwing, so callers can write all evidence first. */
export function evaluateCmpWasmGate(expectedIds, rows, allowlist = new Map()) {
  const byId = new Map(rows.map((row) => [row.id, row]));
  const failures = [];
  const allowed = [];
  for (const id of expectedIds) {
    const row = byId.get(id);
    if (row?.cmpWasmRendered === true) continue;
    const exception = allowlist.get(id);
    const note = row?.cmpWasmNote || (row ? "CMP/Wasm produced no result" : "row was dropped");
    if (exception) allowed.push({ id, note, ...exception });
    else failures.push({ id, note });
  }
  return {
    passed: failures.length === 0,
    expected: expectedIds.length,
    rendered: expectedIds.filter((id) => byId.get(id)?.cmpWasmRendered === true).length,
    allowed,
    failures,
  };
}

/** Add cold/warm first-frame budget failures to an existing strict-lane verdict. */
export function applyCmpWasmPerformanceBudgets(gate, rows, coldBudgetMs, warmBudgetMs) {
  gate.performance = {};
  const budgets = [
    ["cold", coldBudgetMs],
    ["warm", warmBudgetMs],
  ];
  for (const [kind, budget] of budgets) {
    if (budget == null) continue;
    const measured = rows.filter(
      (row) => row.cmpWasmRendered && row.cmpWasmStartup === kind,
    );
    const slowest = measured.reduce(
      (current, row) =>
        current == null || row.cmpWasmFirstFrameMs > current.cmpWasmFirstFrameMs ? row : current,
      null,
    );
    gate.performance[kind] =
      slowest == null
        ? null
        : {
            count: measured.length,
            maxMs: slowest.cmpWasmFirstFrameMs,
            budgetMs: budget,
          };
    if (slowest == null) {
      gate.failures.push({
        id: `performance-${kind}`,
        note: `no ${kind} first-frame measurement was recorded`,
      });
    } else if (slowest.cmpWasmFirstFrameMs > budget) {
      gate.failures.push({
        id: `performance-${kind}`,
        note:
          `${kind} first frame ${slowest.cmpWasmFirstFrameMs.toFixed(0)} ms exceeds ` +
          `${budget.toFixed(0)} ms budget (${slowest.id})`,
      });
    }
  }
  gate.passed = gate.failures.length === 0;
  return gate;
}

/** Enforce 1% by default, with reviewed per-preview ceilings for classified backend variance. */
export function applyCmpWasmPixelTolerances(gate, rows, tolerances = new Map()) {
  gate.pixelTolerances = [];
  const seen = new Set();
  for (const row of rows) {
    if (!row.cmpWasmRendered || row.referenceBlank || row.cmpWasmMismatchPct <= 1) continue;
    const tolerance = tolerances.get(row.id);
    if (tolerance) seen.add(row.id);
    if (!tolerance) {
      gate.failures.push({
        id: `pixels-${row.id}`,
        note: `${row.cmpWasmMismatchPct.toFixed(2)}% exceeds 1% without a reviewed tolerance`,
      });
    } else if (row.cmpWasmMismatchPct > tolerance.maxMismatchPct) {
      gate.failures.push({
        id: `pixels-${row.id}`,
        note:
          `${row.cmpWasmMismatchPct.toFixed(2)}% exceeds reviewed ` +
          `${tolerance.maxMismatchPct.toFixed(2)}% tolerance`,
      });
    } else {
      gate.pixelTolerances.push({ id: row.id, actualPct: row.cmpWasmMismatchPct, ...tolerance });
    }
  }
  for (const id of tolerances.keys()) {
    if (!seen.has(id)) {
      gate.failures.push({ id: `pixels-${id}`, note: "reviewed tolerance is stale or unmeasured" });
    }
  }
  gate.passed = gate.failures.length === 0;
  return gate;
}

export function formatCmpWasmGate(gate) {
  const lines = [
    `CMP/Wasm: ${gate.rendered}/${gate.expected} rendered, ${gate.allowed.length} temporarily allowed, ${gate.failures.length} failed.`,
  ];
  for (const failure of gate.failures) lines.push(`- ${failure.id}: ${failure.note}`);
  for (const entry of gate.allowed) {
    lines.push(`- ${entry.id}: allowed until ${entry.expires} — ${entry.reason} (${entry.note})`);
  }
  for (const kind of ["cold", "warm"]) {
    const performance = gate.performance?.[kind];
    if (performance) {
      lines.push(
        `- ${kind} first frame: ${performance.maxMs.toFixed(0)} ms max / ` +
          `${performance.budgetMs.toFixed(0)} ms budget (${performance.count} measured)`,
      );
    }
  }
  if (gate.pixelTolerances?.length) {
    lines.push(
      `- pixel parity: strict 1% default; ${gate.pixelTolerances.length} reviewed ` +
        `per-preview tolerance(s) applied`,
    );
  }
  return lines.join("\n");
}
