// The compare table's row filter and its status line.
//
// The search box is shared with the format-compare view, so one filter covers both — which is why
// this takes the query as an argument rather than reading an input. The `?preview=` narrowing is
// the other half: arriving from a viewer's "compare" link pins the table to that preview, and it
// composes with whatever is typed rather than replacing it.

import { BAKED, NO_REFERENCE } from "./model.js";

/** One row's searchable text and the previews it covers, both as the server emitted them. */
export interface FilterRow {
    /** `data-hay`, already lowercased by the server. */
    hay: string;
    /** `data-preview-ids`. */
    previewIds: string;
}

export interface FilterResult {
    keep: boolean[];
    visible: number;
    empty: boolean;
}

export function filterRows(
    rows: FilterRow[],
    query: string,
    preview: string,
): FilterResult {
    const text = query.trim().toLowerCase();
    const pinned = preview.toLowerCase();
    let visible = 0;
    const keep = rows.map((row) => {
        const kept =
            (!text || row.hay.includes(text)) &&
            (!pinned || row.previewIds.toLowerCase().includes(pinned));
        if (kept) visible++;
        return kept;
    });
    return { keep, visible, empty: visible === 0 };
}

export function countLabel(visible: number): string {
    return `${visible} ${visible === 1 ? "comparison" : "comparisons"}`;
}

/**
 * What the control row says about the reference in force.
 *
 * The AndroidX Java lane replays numbers the offline run computed with pixelmatch; every other
 * diffed here, without pixelmatch's anti-aliasing pass. Those are not interchangeable — the
 * in-browser number reads a touch higher on text-heavy previews — so the line says which one is on
 * screen rather than letting a reader assume they are the same measurement.
 */
export function statusFor(reference: string, shortLabel: string): string {
    if (reference === NO_REFERENCE) return "";
    if (reference === BAKED)
        return "showing the build-time pixel diffs against AndroidX Java";
    return (
        `diffing in your browser against ${shortLabel} — no anti-aliasing pass, so ` +
        "text-heavy previews read slightly higher than the build-time numbers"
    );
}
