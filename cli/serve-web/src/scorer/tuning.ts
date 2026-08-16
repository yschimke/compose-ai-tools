// The comparison metric's tuning numbers, with why each is the number it is.
//
// Gathered here rather than left at the top of the file that uses them, because they are the whole
// behaviour: every one of them is a judgement about what counts as a difference, and changing any of
// them changes what the catalog reports as a finding. None of these is shared with another surface —
// `compare/thresholds.ts` holds the ones that are.

/** Longest side of the downscale a score is computed over. */
export const MAX_SIDE = 192;

// Figma's browser SVG rasteriser and Skia's Compose rasteriser cover the same vector edge with
// different sub-pixels. Search a small neighbourhood only for actual edge pixels, and charge a
// positional cost for displacement so repeated luminances cannot hide a missing or added mark.
export const EDGE_SEARCH_RADIUS = 5;
export const EDGE_POSITION_COST = 10;
export const EDGE_GRADIENT_THRESHOLD = 12;
export const LUMA_TOLERANCE = 16;

// Longest side of the downscale that content-box detection samples, and how far a pixel may sit
// from the backdrop colour before it counts as drawn.
export const BOX_SAMPLE_SIDE = 256;
export const BOX_COLOUR_TOLERANCE = 12;

/**
 * Smallest share of its canvas a content box may cover before cropping to it stops being
 * trustworthy — see `normalisedBoxes`.
 */
export const MIN_BOX_COVERAGE = 0.05;

/**
 * The backing colours `@Preview(showBackground = true)` resolves to.
 *
 * White for a day uiMode and Material 3's dark surface (#1C1B1F) for a night one, mirroring
 * `PreviewBackground` on the server. An opaque capture whose corner is one of these is sitting on a
 * scaffold sheet; any other corner colour is artwork reaching the edge. See `contentBox`.
 */
export const SCAFFOLD_SHEETS: ReadonlyArray<readonly [number, number, number]> =
    [
        [255, 255, 255],
        [28, 27, 31],
    ];

/** Slack for PNG round-tripping and the detection downscale's resampling of an edge pixel. */
export const SHEET_TOLERANCE = 6;

/**
 * The comparison backdrop, fixed on purpose.
 *
 * Site light/dark appearance must not change the score, and a transparent frame composited onto
 * anything else would be scored against a different ground than its partner.
 */
export const COMPARISON_BACKDROP = "#ffffff";

/** Below this per-channel delta a pixel has not moved — PNG round-tripping and resampling noise. */
export const DIFF_CHANNEL_TOLERANCE = 3;
