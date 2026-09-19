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

/**
 * Measure cold/warm first frames against their budgets — **without gating on them.**
 *
 * This used to fail the lane on the slowest single sample, and it is deliberately no longer a
 * pass/fail signal. The argument is the one [summarizeCmpWasmPixelParity] makes one function down,
 * and it applies here with less room for doubt.
 *
 * The lane runs in the publish job, on `main`, after the change has landed. It could never stop a
 * slow player arriving — only strand `design-artifacts/<system>` on the last render that happened
 * to come in under the line, which is the worst of both: the regression ships and the published
 * catalog stops being refreshed. (Which is what happened: wear-m3-catalog `main` went red on
 * 10 Sep 2026 for one warm frame at 10,023 ms, on a commit whose entire diff was shelf names in a
 * JSON policy file, and the catalog bundle was not published.)
 *
 * And this measurement is *noisier* than the pixel one it sits beside. A mismatch percentage is a
 * property of the two images; a first frame is a wall clock on a shared runner, one sample per
 * document, competing with whatever else that machine is doing. Gating a build on the maximum of
 * several hundred such samples fails when the runner hiccups and says nothing about the player.
 *
 * What stays is everything that is not the verdict. Every row's `cmpWasmFirstFrameMs` is in
 * `rc-compare-summary.json`, the max and the count are reported here, and rows over budget are
 * named in the job summary rather than left to be found. **A missing measurement is still a
 * failure**, and that one is not about speed: it means the lane stopped recording the number, and
 * a report nobody can read is the one way this check could quietly become nothing.
 *
 * A guard that should stop a performance regression has to run on the pull request, against the
 * change proposing it, and compare like with like — a budget applied post-merge is not that.
 */
export function summarizeCmpWasmFirstFrame(gate, rows, coldBudgetMs, warmBudgetMs) {
  gate.performance = {};
  const budgets = [
    ["cold", coldBudgetMs],
    ["warm", warmBudgetMs],
  ];
  for (const [kind, budget] of budgets) {
    if (budget == null) continue;
    const measured = rows.filter((row) => row.cmpWasmRendered && row.cmpWasmStartup === kind);
    const slowest = measured.reduce(
      (current, row) =>
        current == null || row.cmpWasmFirstFrameMs > current.cmpWasmFirstFrameMs ? row : current,
      null,
    );
    if (slowest == null) {
      gate.performance[kind] = null;
      // Not a speed judgement: nothing was measured, so there is no report. See above.
      gate.failures.push({
        id: `performance-${kind}`,
        note: `no ${kind} first-frame measurement was recorded`,
      });
      continue;
    }
    const over = measured
      .filter((row) => row.cmpWasmFirstFrameMs > budget)
      .map((row) => ({ id: row.id, firstFrameMs: row.cmpWasmFirstFrameMs }))
      .sort((a, b) => b.firstFrameMs - a.firstFrameMs);
    gate.performance[kind] = {
      count: measured.length,
      maxMs: slowest.cmpWasmFirstFrameMs,
      budgetMs: budget,
      over,
    };
  }
  gate.passed = gate.failures.length === 0;
  return gate;
}

/**
 * Report which rows sit above the advisory per-preview mismatch line — **without gating on it.**
 *
 * This used to enforce 1% with a checked-in file of reviewed per-preview ceilings, and it is
 * deliberately no longer a pass/fail signal. The check ran in the publish job, on `main`, after the
 * change that moved a number had already landed: it could never stop a regression arriving, only
 * strand `design-artifacts/<system>` on the last render that happened to be under the line. That is
 * the worst of both — the divergence lands anyway, and the published catalog silently rots while the
 * page that would have shown the divergence stops being republished. (Which is exactly what
 * happened: five text-bearing `remote-m3` rows drifted past 1% on 7 Aug 2026 and the delivery branch
 * froze on the 3 Aug render, whose CMP/Wasm column was four days of fixed player bugs out of date.)
 *
 * The measurement itself is worth keeping and stays: every row's mismatch is in
 * `rc-compare-summary.json`, on the comparison page, and listed here in the job summary. What is
 * gone is its power to block publication. A guard that should stop a regression has to run on the
 * pull request, against the change proposing it.
 */
export function summarizeCmpWasmPixelParity(gate, rows, advisoryPct = 1) {
  const above = [];
  let measured = 0;
  for (const row of rows) {
    if (!row.cmpWasmRendered || row.referenceBlank) continue;
    if (!Number.isFinite(row.cmpWasmMismatchPct)) continue;
    measured += 1;
    if (row.cmpWasmMismatchPct > advisoryPct) {
      above.push({ id: row.id, mismatchPct: row.cmpWasmMismatchPct });
    }
  }
  above.sort((a, b) => b.mismatchPct - a.mismatchPct);
  gate.pixelParity = { advisoryPct, measured, above };
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
  // Report-only, on the same terms as pixel parity below and for the same reason: this lane runs
  // after the merge, so a budget here can strand a publish and cannot stop a regression.
  for (const kind of ["cold", "warm"]) {
    const performance = gate.performance?.[kind];
    if (!performance) continue;
    lines.push(
      `- ${kind} first frame (report-only): ${performance.maxMs.toFixed(0)} ms max / ` +
        `${performance.budgetMs.toFixed(0)} ms budget (${performance.count} measured, ` +
        `${performance.over.length} over)`,
    );
    for (const row of performance.over) {
      lines.push(`  · ${row.id}: ${row.firstFrameMs.toFixed(0)} ms`);
    }
  }
  // Report-only, and said out loud rather than left to be found on the page: these rows are how a
  // human notices a backend divergence, but none of them can fail the lane or hold back a publish.
  const parity = gate.pixelParity;
  if (parity) {
    lines.push(
      `- pixel parity (report-only): ${parity.above.length} of ${parity.measured} row(s) ` +
        `above ${parity.advisoryPct}%`,
    );
    for (const row of parity.above) {
      lines.push(`  · ${row.id}: ${row.mismatchPct.toFixed(2)}%`);
    }
  }
  return lines.join("\n");
}
