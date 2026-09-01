/**
 * Re-measure a split carriage report against the bundles that were actually published.
 *
 * `bundle split --carriage-report` writes `totalBytes` as the sum of the per-preview bundles *at
 * split time*, and `sharePercent` as `repeatedBytes / totalBytes`. That is the right measurement
 * for a plain split. It stops being the right measurement the moment a later step removes bytes
 * from those same files: with `defer-figma-svg`, the split needs the transient
 * `compose/figma-svg` sidecars to crop each cover, so they are stripped only *afterwards* — and the
 * carriage gate then divides the repeated payload by a denominator that still counts thousands of
 * sidecar copies that are no longer on disk.
 *
 * The direction of the error is the dangerous one. Sidecar bytes inflate `totalBytes`, which
 * *deflates* `sharePercent`, so a catalog whose published bundles are overwhelmingly repeated
 * classpath can sit under the 50% bound and keep the `auto` default — which is exactly the silent,
 * unbounded delivery-branch growth the gate exists to stop.
 *
 * So: re-sum the published bundles and restate `totalBytes` / `sharePercent` / `dominates` from
 * what is really there. `repeatedBytes` and `carriageBytesPerBundle` are untouched — the shared
 * carriage is `classes/app.jar`, `libs/` and the Android payload, none of which a figma-svg strip
 * touches.
 *
 * Pure and dependency-free (bar `node:fs`) so it unit-tests without an `npm ci`, matching
 * `split-carriage-gate.mjs` next to it.
 */

import fs from "node:fs";

/**
 * Sum the current on-disk size of exactly the bundles named in [manifest], one path per line.
 *
 * A manifest rather than a directory scan, and that is load-bearing. The primary split, the
 * additional-module splits and the extra-module split all run in one workflow step, and the EXTRA
 * split writes into the same `previews/` directory as the primary one — while the carriage report
 * describes the primary split alone. Scanning the directory afterwards would therefore divide the
 * primary `repeatedBytes` by a primary-plus-extra denominator and understate the share, which is
 * the very understatement this re-measure exists to correct. The workflow snapshots the primary
 * file list before those later splits run; this reads it back.
 *
 * A path that has since vanished counts as zero rather than throwing — the strip rewrites bundles
 * in place and never removes them, so a missing entry is a real anomaly that the caller's count
 * check below will catch and report, not something to crash on here.
 */
export function manifestBundleBytes(manifest, { read = fs.readFileSync, stat = fs.statSync } = {}) {
  let bytes = 0;
  let files = 0;
  let missing = 0;
  for (const line of String(read(manifest, "utf8")).split("\n")) {
    const file = line.trim();
    if (file.length === 0) continue;
    files += 1;
    try {
      bytes += stat(file).size;
    } catch {
      missing += 1;
    }
  }
  return { bytes, files, missing };
}

/**
 * Restate [report] against a measured [totalBytes].
 *
 * @returns {{report: object, changed: boolean, note: string}} the rewritten report, whether the
 *   numbers actually moved, and one line for the run log.
 */
export function restateCarriage(report, totalBytes, { reportThresholdPercent = 50 } = {}) {
  const before = Number(report?.totalBytes ?? 0);
  const repeatedBytes = Number(report?.repeatedBytes ?? 0);
  const bundles = Number(report?.bundles ?? 0);
  const sharePercent = totalBytes <= 0 ? 0 : (repeatedBytes * 100) / totalBytes;
  const rounded = Math.round(sharePercent * 10) / 10;
  const threshold = Number(report?.reportThresholdPercent ?? reportThresholdPercent);
  const restated = {
    ...report,
    totalBytes,
    sharePercent: rounded,
    dominates: bundles > 1 && sharePercent >= threshold,
    // Keep the split-time number rather than discarding it: when the gate fails, the two together
    // say how much of the output was deferred rather than merely how big the share now is.
    totalBytesAtSplit: before,
  };
  const changed = before !== totalBytes;
  const note = changed
    ? `carriage report restated against published bundles: totalBytes ${before} -> ${totalBytes}, ` +
      `sharePercent ${report?.sharePercent ?? 0} -> ${rounded}`
    : `carriage report unchanged: published bundles still total ${totalBytes} bytes`;
  return { report: restated, changed, note };
}

function flag(argv, name, fallback = "") {
  const i = argv.indexOf(name);
  return i >= 0 && i + 1 < argv.length ? argv[i + 1] : fallback;
}

function main(argv) {
  const reportPath = flag(argv, "--report");
  const manifest = flag(argv, "--bundles-from");
  if (!reportPath || !manifest) {
    console.error(
      "usage: recompute-carriage-report.mjs --report <split-carriage.json> " +
        "--bundles-from <primary-bundle-list.txt>",
    );
    return 2;
  }
  if (!fs.existsSync(reportPath)) {
    // An older pinned CLI writes no report at all; `split-carriage-gate.mjs` already says so and
    // carries on with the historic behaviour. Restating a report that does not exist is a no-op,
    // not a failure — this step must not be the thing that breaks those callers.
    console.log(`no carriage report at ${reportPath}; nothing to restate`);
    return 0;
  }
  let report;
  try {
    report = JSON.parse(fs.readFileSync(reportPath, "utf8"));
  } catch (e) {
    console.error(`carriage report ${reportPath} is not valid JSON: ${e.message}`);
    return 1;
  }
  let measured;
  try {
    measured = manifestBundleBytes(manifest);
  } catch (e) {
    console.error(`cannot read the primary bundle manifest ${manifest}: ${e.message}`);
    return 1;
  }
  const { bytes, files, missing } = measured;
  if (files === 0) {
    console.error(`the primary bundle manifest ${manifest} is empty; leaving the report as written`);
    return 0;
  }
  // Fail closed rather than restate against a set that is not what the report measured. A count
  // that disagrees with `bundles` means the manifest and the report describe different splits, and
  // silently dividing by the wrong denominator is precisely the failure this step exists to fix.
  const declared = Number(report?.bundles ?? 0);
  if (Number.isInteger(declared) && declared > 0 && declared !== files) {
    console.error(
      `the manifest names ${files} primary bundle(s) but the carriage report declares ${declared}; ` +
        "refusing to restate the share against a different set of files",
    );
    return 1;
  }
  if (missing > 0) {
    console.error(`${missing} of ${files} manifested bundle(s) are gone; refusing to restate`);
    return 1;
  }
  const { report: restated, note } = restateCarriage(report, bytes);
  fs.writeFileSync(reportPath, `${JSON.stringify(restated, null, 2)}\n`);
  console.log(`${note} (measured across ${files} primary bundle(s))`);
  return 0;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  process.exit(main(process.argv.slice(2)));
}
