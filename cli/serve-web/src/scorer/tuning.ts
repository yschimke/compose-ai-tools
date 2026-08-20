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

/**
 * The luminance gap at which a pixel counts as *completely* wrong.
 *
 * Below this the cost ramps, so a fill that drifted a shade still reads as mostly right. At or above
 * it the pixel is charged in full: half the luminance range apart is not a tone that moved, it is a
 * different mark. Charging the gap linearly all the way to 255 — which is what this used to do —
 * meant a filled control sitting where the reference has bare background cost about a fifth of a
 * pixel, so a component that had lost its fills entirely still read as "mostly matching".
 */
export const FULL_DIFFERENCE_DELTA = 128;

/**
 * How far the content mask reaches beyond the pixels that carry detail (see `contentMask`).
 *
 * One pixel, because a mark is wider than its own gradient: the anti-aliased skirt of a hairline
 * stroke is part of the stroke, and leaving it outside the measured region would put a stroke's
 * disagreement in the numerator while its own pixels sat outside the denominator.
 */
export const CONTENT_DILATION = 1;

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
 * The grounds a comparison is scored on — **both** of them, every time.
 *
 * Fixed rather than themed, for the reason a single ground was fixed before: site appearance must
 * not move a score, and two frames composited onto different colours differ by that colour
 * everywhere. What has changed is the count, because one opaque ground is not a neutral choice. It
 * *annihilates* ink that matches it, and the metric next door reads that as agreement rather than as
 * missing evidence: `scorePlanes` finds no content and no disagreement in two blank planes and
 * returns `100` by definition.
 *
 * That is not a corner case. Measured off `samples/design-catalog-wear-m3`, `TextMaxLinesTruncated`
 * is 8% opaque with an ink luminance of 255 — pure white glyphs on transparency. Composited onto
 * white it is a blank image, so it was being scored as a perfect match against whatever it was
 * paired with, including a reference it looks nothing like. Black has the same flaw pointed the
 * other way, and a theme-derived ground only makes the collision rarer, never impossible.
 *
 * Scoring on white AND black and keeping the worse result makes the failure structural rather than
 * statistical: a pixel can only vanish on both grounds when its alpha is zero on both sides — that
 * is, when it is genuinely absent from both images, which is the one case where "no evidence" is the
 * honest answer. The cost is two passes over a plane capped at {@link MAX_SIDE}, so under 37k pixels
 * either way.
 */
export const COMPARISON_GROUNDS: ReadonlyArray<string> = ["#ffffff", "#000000"];

/** Below this per-channel delta a pixel has not moved — PNG round-tripping and resampling noise. */
export const DIFF_CHANNEL_TOLERANCE = 3;
