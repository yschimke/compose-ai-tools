// What the spec lane says about the pair in front of the visitor.
//
// Two surfaces report one comparison — the readout under the views, and the design-spec chip up in
// the identity row — and the reason they are decided together here is that they used to be able to
// disagree. The chip carries the score baked at PUBLISH; the moment an override, a knob or a theme
// moves the render, that number describes a frame that is no longer on the stage. So the lane
// overwrites it while it has a live measurement, and restores the published one on the way out —
// off the lane there is nothing live to describe, and leaving a knob-bent number there would
// misreport every later visit as if it were the publish.

// Shared rather than restated: the parity page decides a pair is worth opening on the same number
// this lane then judges it by, and the two disagreeing reads as a broken page.
import { GEOMETRY_REPORT_THRESHOLD } from "../compare/thresholds.js";

export { GEOMETRY_REPORT_THRESHOLD };

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

/**
 * Said in place of a match score when the render on the stage is not the one the spec describes.
 *
 * A design spec is imported ONCE, at the catalog's baseline — default theme, declared knob
 * defaults, no overrides. There is no per-theme, per-locale or per-font-scale export, and the
 * comparison has no way to synthesise one. So the moment an override moves the render, the two
 * frames are answering different questions and any percentage taken across them is measuring the
 * override rather than the component.
 *
 * On `shape-bun` under Light Medium Contrast that is not a subtle overstatement: the geometry is
 * identical and only the token colour moves, so 89% of the pixels "differ" and the lane reported
 * "90.5% match" — a number that reads as a parity finding and is nothing of the kind. Suppressing
 * it costs a reader nothing, because there was no honest number to lose.
 *
 * The changed-pixel count still goes out, because it is literally true about the two frames in
 * front of the visitor. What it is NOT is a verdict, and the line says so rather than leaving the
 * reader to infer it.
 */
export function offBaselineReadout(changedPercent: number): string {
    return (
        `${changedPercent.toFixed(2)}% pixels differ · the imported spec is baseline-only, ` +
        `so this is not a match score — clear the overrides to compare`
    );
}

/** Said in place of a number when the pair cannot be compared at all. */
export const UNAVAILABLE = "Comparison unavailable";

/** Said while a comparison is in flight. */
export const COMPARING = "comparing…";
