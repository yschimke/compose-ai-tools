// The three lanes, and what the two filters do to the sheet.
//
// One sheet, three lanes: this catalog's renders standing in the design's slots, the design's own
// drawing, or the difference between them scored per node.
//
// The first two are deliberately not a composite — no opacity slider, no `difference` blend over the
// whole sheet. Those answered "how close are these two pictures" by making the reader squint; the
// diff lane answers it with a number and a map, and the eye compares two clean frames better than
// one muddy one.

export type Lane = "code" | "design" | "diff";

/** The classes the stage carries for a lane. */
export interface StageState {
    "cp-page-swap-on": boolean;
    "cp-page-hide-design": boolean;
    "cp-page-diff-on": boolean;
}

export function laneState(lane: Lane): StageState {
    // Anything that is not the design's own drawing shows ours in the slots — including the diff
    // lane, which scores what is actually on the sheet.
    const ours = lane !== "design";
    return {
        "cp-page-swap-on": ours,
        "cp-page-hide-design": ours,
        "cp-page-diff-on": lane === "diff",
    };
}

/** Whether entering this lane needs the renders adopted out of their inert `<template>`. */
export function needsRenders(lane: Lane): boolean {
    return lane !== "design";
}

export function laneOf(value: string | null | undefined): Lane {
    return value === "design" || value === "diff" ? value : "code";
}

/**
 * What the coverage filter does when it is switched on.
 *
 * A coverage filter with nothing to draw on is a no-op the reader cannot see, so asking for it turns
 * the resting marks on. Unchecking leaves them on: it was an explicit state to arrive at, and
 * silently repainting the sheet plain would read as the filter having broken something.
 */
export function outlinesAfterUnlinked(
    unlinkedOn: boolean,
    outlinesOn: boolean,
): boolean {
    return unlinkedOn ? true : outlinesOn;
}

/**
 * Whether an overlay is taken out of the tab order and the accessibility tree.
 *
 * CSS alone cannot do this: `opacity: 0` + `pointer-events: none` still leaves a control focusable,
 * so a keyboard user could tab onto an invisible rectangle — no focus ring, no indication of where
 * they are.
 *
 * Keyed on the GAP, not on "unlinked": the filter shows components with no code behind them, and the
 * sheet's private furniture and variant-set containers are neither.
 */
export function isInert(unlinkedOnly: boolean, hasGap: boolean): boolean {
    return unlinkedOnly && !hasGap;
}
