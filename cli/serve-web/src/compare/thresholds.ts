// Thresholds every surface that reports a comparison has to agree on.
//
// Shared rather than restated, because the surfaces are read against each other: the parity page
// decides a pair is worth opening, the visitor opens it, and the spec lane then has to agree that
// there is something there. Two copies of the number are two chances for a pair to be a finding on
// one page and clean on the next — which reads as the page being broken rather than as a threshold
// having drifted.
//
// STILL MIRRORED, deliberately, in two places this cannot reach:
//
//   - `assets/format-compare.js`, which owns the metric and is not yet ported.
//   - `scripts/design-artifacts/design-reference-score.mjs`, which runs at publish time under node
//     with no build step, so it cannot import from `src/`.
//
// Both carry a comment pointing here. When `format-compare.js` is ported, its copy becomes an
// import and only the publish-time driver is left to keep in step.

/**
 * Below this, a difference between two content boxes' proportions is the rasteriser rather than the
 * component.
 *
 * Figma's browser SVG rasteriser and Skia's Compose rasteriser cover the same vector edge with
 * different sub-pixels, so a pair that matches perfectly still measures a fraction of a percent
 * apart. Reporting that would be reporting the rasteriser — on every component, forever, which
 * trains the reader to ignore the column.
 */
export const GEOMETRY_REPORT_THRESHOLD = 2;
