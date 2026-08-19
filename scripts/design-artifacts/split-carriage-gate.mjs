/**
 * The bound that stops `split-mode` from taking an expensive default in silence.
 *
 * A FULL per-preview split copies the shared re-render carriage — `classes/app.jar`, `libs/`, the
 * Android `resources.ap_` payload — into every bundle. That is correct, and its cost is a function
 * of the **preview count**, not of the backend: the carriage is a fixed per-bundle price paid N
 * times. At 88 previews that is a rounding error; at ~1,300 it was 790 MB.
 *
 * Worse, the cost is not paid once. Any catalog change rewrites `app.jar`, hence all N bundles,
 * hence a fresh git delta chain on the delivery branch — ~33 MB per publish for m3-catalog, ~230
 * MB/day, unbounded. None of it shows in the branch tip (which packs to 71.5 MB, because git deltas
 * near-identical polyglots well *within* a snapshot), so 76 green publishes took that clone to
 * 2.52 GiB before anyone looked.
 *
 * This gate makes the crossing loud. `split-mode: auto` — the default — splits exactly as `full`
 * always did, so no delivery branch changes shape underneath its consumers; what changes is that
 * once the carriage passes [CARRIAGE_GATE_THRESHOLD_PERCENT] of the output, the publish fails and
 * the caller has to write down which trade they want:
 *
 *   - `full-shared-classpath` publishes `app.jar` once into a content-addressed pool and keeps the
 *     live re-render lane (each manifest carries a hash-verified `externalClasspath`). 15.7× smaller
 *     on m3-catalog. The trade is real and belongs to the catalog owner: pooled per-preview bundles
 *     are not offline-executable from a bare download (docs/portable-bundles.md), so anything that
 *     fetches one preview and expects it to run breaks — which is exactly why this is a forced
 *     choice and not an adaptive default that flips underneath such a consumer.
 *   - `full` keeps self-contained bundles and accepts the cost, on the record, in the workflow file.
 *
 * An explicit mode is never failed — the point is that the choice is written down, not that one
 * answer is right. Pure and dependency-free so it unit-tests without an `npm ci`.
 */

import fs from "node:fs";

/**
 * Report the carriage as a failure once it is at least this share of everything the split wrote.
 * Mirrors `SPLIT_CARRIAGE_REPORT_PERCENT` in the CLI's BundleSplit.kt, which is the threshold the
 * same measurement warns at; the two are the same number on purpose, so a run that warns is the run
 * that fails.
 */
export const CARRIAGE_GATE_THRESHOLD_PERCENT = 50;

/** Every `split-mode` the reusable workflow accepts. */
export const SPLIT_MODES = ["auto", "full", "full-shared-classpath", "view-only"];

const MODE_DOC = "docs/design/DESIGN_CATALOGS.md#per-preview-split-modes";

/** Raw bytes, with a MiB gloss once the number stops being readable as bytes. */
function bytes(n) {
  const mib = n / (1024 * 1024);
  return mib >= 1 ? `${n} bytes (${mib.toFixed(1)} MiB)` : `${n} bytes`;
}

/**
 * Decide what a split's carriage report means for the chosen [mode].
 *
 * @param {object} args
 * @param {string} args.mode one of [SPLIT_MODES]
 * @param {object|null} args.report parsed `--carriage-report` JSON, or null when the CLI wrote none
 * @param {string} [args.system] the delivery system, for the message
 * @returns {{level: "ok"|"notice"|"warning"|"error", title: string, headline: string,
 *   details: string[]}}
 */
export function evaluateSplitCarriage({ mode, report, system = "<system>" }) {
  if (!SPLIT_MODES.includes(mode)) {
    return {
      level: "error",
      title: "split-mode",
      headline: `unknown split-mode '${mode}' — use ${SPLIT_MODES.join(", ")}.`,
      details: [],
    };
  }

  if (!report) {
    // An older released CLI has no `--carriage-report`, so it wrote nothing. Refusing to publish
    // over that would break every caller pinned below the release that added it, for a measurement
    // rather than a defect — so say it out loud and carry on with the historic behaviour.
    if (mode !== "auto") return { level: "ok", title: "", headline: "", details: [] };
    return {
      level: "warning",
      title: "split carriage unmeasured",
      headline:
        "the installed compose-preview CLI has no 'bundle split --carriage-report', so " +
        "split-mode: auto ran as 'full' without checking what the shared carriage costs.",
      details: [
        "Raise cli-version (or the version-catalog pin behind cli-version: catalog) to a release " +
          "that carries it, or set split-mode explicitly to record the choice.",
      ],
    };
  }

  const {
    bundles = 0,
    carriageBytesPerBundle = 0,
    repeatedBytes = 0,
    totalBytes = 0,
    sharePercent = 0,
  } = report;
  const share = `${sharePercent.toFixed(1)}%`;
  const measured =
    `shared carriage is ${carriageBytesPerBundle} bytes in each of ${bundles} bundle(s) — ` +
    `${bytes(repeatedBytes)} of ${bytes(totalBytes)} written (${share}).`;
  const dominates = bundles > 1 && sharePercent >= CARRIAGE_GATE_THRESHOLD_PERCENT;

  if (mode === "auto" && dominates) {
    return {
      level: "error",
      title: "split-mode",
      headline: `split-mode defaulted to 'full' and the carriage now dominates: ${measured}`,
      details: [
        `Every catalog edit rewrites that payload in all ${bundles} bundles, so ` +
          `design-artifacts/${system} grows by ${bytes(repeatedBytes)} on EVERY publish — ` +
          "unbounded, and invisible in the branch tip.",
        "The default will not choose for you, because the choice changes what a delivery branch " +
          "promises. Write one of these into the workflow inputs:",
        "  split-mode: full-shared-classpath   # app.jar published once into bundle/res; the live " +
          "re-render lane stays intact (each manifest keeps a hash-verified externalClasspath). " +
          "Per-preview bundles are then NOT offline-executable from a bare download.",
        "  split-mode: full                    # keep self-contained bundles and accept the cost.",
        `See ${MODE_DOC}.`,
      ],
    };
  }

  if (dominates) {
    return {
      level: "notice",
      title: "split carriage",
      headline: `split-mode: ${mode} — ${measured}`,
      details: [
        mode === "full"
          ? "Chosen explicitly, so the repetition is accepted rather than accidental; " +
            "full-shared-classpath would publish that payload once."
          : "app.jar is already pooled; what repeats is libs/ + the Android payload.",
      ],
    };
  }

  return {
    level: "notice",
    title: "split carriage",
    headline:
      `split-mode: ${mode}${mode === "auto" ? " (resolved to full)" : ""} — ${measured} ` +
      `Bound is ${CARRIAGE_GATE_THRESHOLD_PERCENT}%.`,
    details: [],
  };
}

/** Read a carriage report, or null when the CLI that ran the split wrote none. */
export function readCarriageReport(path) {
  if (!path || !fs.existsSync(path)) return null;
  try {
    return JSON.parse(fs.readFileSync(path, "utf8"));
  } catch {
    return null;
  }
}

function flag(argv, name, fallback = "") {
  const i = argv.indexOf(name);
  return i >= 0 && i + 1 < argv.length ? argv[i + 1] : fallback;
}

function main(argv) {
  const mode = flag(argv, "--mode");
  const system = flag(argv, "--system", "<system>");
  const result = evaluateSplitCarriage({
    mode,
    report: readCarriageReport(flag(argv, "--report")),
    system,
  });
  if (result.level === "ok") return 0;

  const lines = [result.headline, ...result.details];
  for (const line of lines) console.log(line);
  // One annotation, so the run's summary carries the headline; the body is above it in the log.
  console.log(`::${result.level} title=${result.title}::${result.headline}`);
  if (process.env.GITHUB_STEP_SUMMARY) {
    fs.appendFileSync(
      process.env.GITHUB_STEP_SUMMARY,
      `\n**${result.title}** — ${lines.join("\n\n")}\n`,
    );
  }
  return result.level === "error" ? 1 : 0;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  process.exit(main(process.argv.slice(2)));
}
