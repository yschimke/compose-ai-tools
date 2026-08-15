// What the spec lane says about the pair in front of the visitor.
//
// Two surfaces report one comparison — the readout under the views, and the design-spec chip up in
// the identity row — and the reason they are decided together here is that they used to be able to
// disagree. The chip carries the score baked at PUBLISH; the moment an override, a knob or a theme
// moves the render, that number describes a frame that is no longer on the stage. So the lane
// overwrites it while it has a live measurement, and restores the published one on the way out —
// off the lane there is nothing live to describe, and leaving a knob-bent number there would
// misreport every later visit as if it were the publish.

/**
 * Below this the two content boxes are the same shape to within rasteriser noise, and reporting a
 * proportion difference would be reporting the rasteriser. Matches `format-compare.js`.
 */
export const GEOMETRY_REPORT_THRESHOLD = 2;

/** How close counts as which. Mirrors `ServeWeb.specMatchBand` and the export driver's `matchBand`. */
export type MatchBand = "match" | "close" | "off";

export function matchBand(percent: number): MatchBand {
    if (percent >= 99.5) return "match";
    if (percent >= 97) return "close";
    return "off";
}

/** The chip's label while the lane is live: the component's name, plus what it currently scores. */
export function chipText(name: string, percent: number): string {
    return `${name} ${percent.toFixed(1)}%`;
}

/**
 * The readout under the view buttons.
 *
 * Two numbers, not one, because they answer different questions: the match percentage is
 * structural (how alike are these?), the changed-pixel percentage is literal (how much of the frame
 * moved?). A 99% structural match with 8% of pixels differing is a uniform shift; the reverse is a
 * small element in the wrong place. Reporting only one loses that.
 */
export function readout(
    percent: number,
    changedPercent: number,
    geometry: number,
): string {
    const drift =
        geometry >= GEOMETRY_REPORT_THRESHOLD
            ? ` · ${geometry.toFixed(1)}% proportion difference`
            : "";
    return `${percent.toFixed(1)}% match · ${changedPercent.toFixed(2)}% pixels differ${drift}`;
}

/** Changed pixels as a percentage of the frame, guarding the empty frame. */
export function changedPercentOf(
    changed: number,
    width: number,
    height: number,
): number {
    const pixels = width * height;
    return pixels ? (changed * 100) / pixels : 0;
}

/** Said in place of a number when the pair cannot be compared at all. */
export const UNAVAILABLE = "Comparison unavailable";

/** Said while a comparison is in flight. */
export const COMPARING = "comparing…";
