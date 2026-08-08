#!/usr/bin/env node
/**
 * rc-compare-regression.mjs — fail a pull request whose player change makes a document render worse.
 *
 * This is the guard that replaced the publish-time pixel gate. The old one enforced an absolute 1%
 * per preview inside the `design-artifacts` publish job — on `main`, after the regression had
 * merged — so it could only ever strand the delivery branch on a stale render, never stop the
 * change. This runs on the pull request, against the change proposing it, and asks the only
 * question a PR can answer: **did this diff move any document?**
 *
 * Two comparisons, both against a baseline produced by the same lane on `main`:
 *
 *   • a document that rendered in the baseline and does not render now — always a failure, whatever
 *     the pixels say;
 *   • a document whose mismatch against the baked reference grew by more than `--max-increase-pp`
 *     percentage points.
 *
 * Deliberately a *delta*, not a bar. Absolute mismatch is dominated by backend differences nobody
 * on this PR introduced (Skia-vs-Android glyph coverage, a reference lane that cannot apply font
 * axes); the number that means something is how much this change moved it. A row that was already
 * at 3% and stays at 3% is somebody else's known divergence — the page reports it. A row that goes
 * 0.26% → 2.17% is this diff, and that is what turns the job red.
 *
 * The corpus is the published `design-artifacts/<system>` bundle rather than a freshly rendered
 * catalog: fixing the documents is what isolates the player. A PR that changes the *catalog* moves
 * numbers for reasons that are not a player regression, and re-rendering the catalog here would
 * both cost an Android render and confuse the signal.
 *
 * Usage:
 *   node rc-compare-regression.mjs --baseline <rc-compare-summary.json> \
 *     --current <rc-compare-summary.json> [--max-increase-pp 0.25]
 */
import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

export const DEFAULT_MAX_INCREASE_PP = 0.25;

/**
 * Compare two `rc-compare-summary.json` row sets on the CMP/Wasm lane.
 *
 * Rows the baseline never measured (a preview added since it was published) and rows the current
 * run no longer has (one removed) are reported, never failed: neither is evidence about this diff.
 */
export function compareCmpWasmParity(
  baselineRows,
  currentRows,
  { maxIncreasePp = DEFAULT_MAX_INCREASE_PP } = {},
) {
  const baseline = new Map(baselineRows.map((row) => [row.id, row]));
  const current = new Map(currentRows.map((row) => [row.id, row]));
  const failures = [];
  const improvements = [];
  const unchanged = [];
  const added = [];
  const removed = [];

  for (const [id, now] of current) {
    const before = baseline.get(id);
    if (!before) {
      added.push({ id, mismatchPct: now.cmpWasmMismatchPct ?? null });
      continue;
    }
    if (before.cmpWasmRendered && !now.cmpWasmRendered) {
      failures.push({
        id,
        kind: "render",
        note: `rendered on the baseline and does not render now: ${now.cmpWasmNote || "no result"}`,
      });
      continue;
    }
    // A row that could not render on the baseline either is not this PR's doing. It is worth saying
    // out loud when it starts rendering, and worth saying nothing about when it still does not.
    if (!before.cmpWasmRendered) {
      if (now.cmpWasmRendered) improvements.push({ id, deltaPp: null, note: "now renders" });
      continue;
    }
    if (!Number.isFinite(before.cmpWasmMismatchPct) || !Number.isFinite(now.cmpWasmMismatchPct)) {
      continue;
    }
    const deltaPp = now.cmpWasmMismatchPct - before.cmpWasmMismatchPct;
    const entry = {
      id,
      deltaPp,
      baselinePct: before.cmpWasmMismatchPct,
      currentPct: now.cmpWasmMismatchPct,
    };
    if (deltaPp > maxIncreasePp) {
      failures.push({
        ...entry,
        kind: "pixels",
        note:
          `mismatch grew ${deltaPp.toFixed(2)} pp — ` +
          `${before.cmpWasmMismatchPct.toFixed(2)}% → ${now.cmpWasmMismatchPct.toFixed(2)}% ` +
          `(limit ${maxIncreasePp.toFixed(2)} pp)`,
      });
    } else if (deltaPp < -maxIncreasePp) {
      improvements.push(entry);
    } else {
      unchanged.push(entry);
    }
  }

  for (const id of baseline.keys()) if (!current.has(id)) removed.push({ id });

  failures.sort((a, b) => (b.deltaPp ?? Infinity) - (a.deltaPp ?? Infinity));
  improvements.sort((a, b) => (a.deltaPp ?? 0) - (b.deltaPp ?? 0));
  return { passed: failures.length === 0, maxIncreasePp, failures, improvements, unchanged, added, removed };
}

export function formatCmpWasmRegression(result) {
  const lines = [
    `CMP/Wasm parity vs baseline: ${result.failures.length} regression(s), ` +
      `${result.improvements.length} improvement(s), ${result.unchanged.length} unchanged ` +
      `(±${result.maxIncreasePp} pp), ${result.added.length} new, ${result.removed.length} dropped.`,
  ];
  for (const failure of result.failures) lines.push(`✗ ${failure.id}: ${failure.note}`);
  for (const entry of result.improvements) {
    lines.push(
      entry.deltaPp == null
        ? `✓ ${entry.id}: ${entry.note}`
        : `✓ ${entry.id}: ${entry.baselinePct.toFixed(2)}% → ${entry.currentPct.toFixed(2)}%`,
    );
  }
  for (const entry of result.added) lines.push(`+ ${entry.id}: not in the baseline, not judged`);
  for (const entry of result.removed) lines.push(`- ${entry.id}: dropped since the baseline`);
  return lines.join("\n");
}

function readRows(file, label) {
  const parsed = JSON.parse(fs.readFileSync(file, "utf8"));
  if (!parsed || !Array.isArray(parsed.rows)) {
    throw new Error(`${label} (${file}) is not an rc-compare summary with a rows array`);
  }
  return parsed.rows;
}

// Run as a CLI only when invoked directly, so the comparison above stays importable by tests.
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const arg = (name, fallback) => {
    const index = process.argv.indexOf(`--${name}`);
    return index >= 0 && index + 1 < process.argv.length ? process.argv[index + 1] : fallback;
  };
  const baselineFile = arg("baseline");
  const currentFile = arg("current");
  if (!baselineFile || !currentFile) {
    console.error("rc-compare-regression: --baseline and --current are required");
    process.exit(2);
  }
  const maxIncreasePp = Number(arg("max-increase-pp", String(DEFAULT_MAX_INCREASE_PP)));
  if (!Number.isFinite(maxIncreasePp) || maxIncreasePp <= 0) {
    console.error("rc-compare-regression: --max-increase-pp must be a positive number");
    process.exit(2);
  }
  const result = compareCmpWasmParity(
    readRows(baselineFile, "baseline"),
    readRows(currentFile, "current"),
    { maxIncreasePp },
  );
  const report = formatCmpWasmRegression(result);
  console.log(report);
  if (process.env.GITHUB_STEP_SUMMARY) {
    fs.appendFileSync(
      process.env.GITHUB_STEP_SUMMARY,
      `### Remote Compose CMP/Wasm parity\n\n\`\`\`\n${report}\n\`\`\`\n`,
    );
  }
  process.exit(result.passed ? 0 : 1);
}
