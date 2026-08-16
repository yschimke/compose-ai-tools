// "Fit screen" — how tall the stage is allowed to be, and when that answer needs recomputing.
//
// The cap is MEASURED, not a fixed `72vh` guess. The guess was wrong in both directions: on the
// viewer, where a title block and two control rows sit above the stage, 72vh reached past the fold
// and cut the render off; on a short window it left the image taller than the space it had.
//
// The re-measure guard is the subtle half. Applying a cap resizes the image, which resizes the
// container being observed, which re-measures — so without a comparison against what was last
// applied, the observer feeds itself. It stops because the second measurement matches the first.
//
// DOM-free: `viewer.js` measures the stage and passes numbers.

/** Below this the stage is a sliver rather than a usable view, whatever the window says. */
export const MIN_FIT_HEIGHT = 320;

/**
 * Slack under the stage.
 *
 * The stage's own padding is inside the box the image has to fit in, and a little room keeps the
 * card's bottom edge on screen rather than flush against it.
 */
export const FIT_SLACK = 64;

export type ZoomMode = "fit" | "width";

/** Anything that is not an explicit "fit width" is "fit screen" — the default a page opens in. */
export function zoomMode(raw: string | null | undefined): ZoomMode {
    return raw === "width" ? "width" : "fit";
}

/** The stage's height cap, as a CSS length. */
export function fitCap(stageTop: number, viewportHeight: number): string {
    return `${Math.max(MIN_FIT_HEIGHT, Math.round(viewportHeight - stageTop - FIT_SLACK))}px`;
}

/**
 * Whether a re-measure should actually re-apply.
 *
 * Two reasons to do nothing, and they are different. "Fit width" is an explicit choice to ignore
 * the viewport's height, so it is left alone entirely. And a cap that lands on the value already
 * applied is what breaks the observer's feedback loop — see the note at the top of this file.
 */
export function needsRefit(
    mode: ZoomMode,
    measured: string,
    applied: string | null,
): boolean {
    if (mode === "width") return false;
    return measured !== applied;
}
