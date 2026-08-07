/**
 * Lane-mean aggregation for the `rc-compare` summary.
 *
 * Extracted so the one subtlety here is testable: **each lane's means must be scoped to the rows
 * that lane rendered**, not to the JS player's set. The optional players are independent — an
 * embedded or CMP/Wasm player routinely renders a document the JS player chokes on (before #3427
 * the JS lane managed 15 of remote-m3's 27 while the embedded player managed all 27). Averaging an
 * optional lane over the JS lane's rows silently drops exactly those, leaving the lane's split
 * disagreeing with the `meanMismatchPct` and `scored` count printed beside it.
 */

/** Mean of the numeric values [pick] returns over [rows]; null when it never returns one. */
export function meanOf(rows, pick) {
  const values = rows.map(pick).filter((v) => typeof v === "number");
  return values.length ? values.reduce((sum, v) => sum + v, 0) / values.length : null;
}

/**
 * The rows an optional lane actually scored: it rendered them, and the baked reference wasn't blank
 * (a blank reference is unscorable, which is why the lane's own mismatch is null there too).
 */
export function laneRows(rows, prefix) {
  return rows.filter((r) => r[`${prefix}Rendered`] && !r.referenceBlank);
}

/** `{ meanCoverageDeltaPct, meanContentMismatchPct }` for one optional lane. */
export function laneSplit(rows, prefix) {
  const scoped = laneRows(rows, prefix);
  return {
    meanCoverageDeltaPct: meanOf(scoped, (r) => r[`${prefix}CoverageDeltaPct`]),
    meanContentMismatchPct: meanOf(scoped, (r) => r[`${prefix}ContentMismatchPct`]),
  };
}
