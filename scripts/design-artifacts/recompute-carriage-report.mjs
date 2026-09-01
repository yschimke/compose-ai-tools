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
import path from "node:path";

/** Sum the on-disk size of every `*.png` bundle directly in [dir]. Missing dir → 0 bytes, 0 files. */
export function publishedBundleBytes(dir, { readdir = fs.readdirSync, stat = fs.statSync } = {}) {
  let bytes = 0;
  let files = 0;
  let entries;
  try {
    entries = readdir(dir, { withFileTypes: true });
  } catch {
    return { bytes, files };
  }
  for (const entry of entries) {
    if (!entry.isFile() || !entry.name.endsWith(".png")) continue;
    bytes += stat(path.join(dir, entry.name)).size;
    files += 1;
  }
  return { bytes, files };
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
  const bundlesDir = flag(argv, "--bundles-dir");
  if (!reportPath || !bundlesDir) {
    console.error(
      "usage: recompute-carriage-report.mjs --report <split-carriage.json> --bundles-dir <dir>",
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
  const { bytes, files } = publishedBundleBytes(bundlesDir);
  if (files === 0) {
    console.error(`no published *.png bundles under ${bundlesDir}; leaving the report as written`);
    return 0;
  }
  const { report: restated, note } = restateCarriage(report, bytes);
  fs.writeFileSync(reportPath, `${JSON.stringify(restated, null, 2)}\n`);
  console.log(`${note} (measured across ${files} published bundle(s))`);
  return 0;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  process.exit(main(process.argv.slice(2)));
}
