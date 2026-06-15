// Presenter for the history panel's theme data-diff section (#1872). Pure: turns a `ThemeDelta`
// (from the client-side `themeDiff` differ) into display rows the webview renders beneath the pixel
// diff. No DOM — mirrors `historyDiffSemanticsPresenter` so it's unit-testable without a browser.

import {
    type ThemeCategory,
    type ThemeDelta,
    type ThemeTokenChange,
    type ThemeTokenSummary,
} from "../shared/themeDiff";

export type ThemeDiffRowKind = "added" | "removed" | "changed";

export interface ThemeDiffRow {
    kind: ThemeDiffRowKind;
    category: ThemeCategory;
    /** Token name, e.g. `primary` / `bodyLarge` / `small`. */
    key: string;
    /** Resolved value for added/removed rows; null for changed (use from/to). */
    value: string | null;
    /** Previous → current value for changed rows; null for added/removed. */
    from: string | null;
    to: string | null;
}

export interface ThemeDiffData {
    empty: boolean;
    addedCount: number;
    removedCount: number;
    changedCount: number;
    /** Removed first, then added, then changed — matches the semantics section ordering. */
    rows: ThemeDiffRow[];
}

const EMPTY: ThemeDiffData = {
    empty: true,
    addedCount: 0,
    removedCount: 0,
    changedCount: 0,
    rows: [],
};

/** Derives the display rows for a theme delta. Null/empty delta → an empty data set. */
export function computeThemeDiffData(
    delta: ThemeDelta | null | undefined,
): ThemeDiffData {
    if (!delta) {
        return EMPTY;
    }
    const removed = delta.removed ?? [];
    const added = delta.added ?? [];
    const changed = delta.changed ?? [];
    const rows: ThemeDiffRow[] = [];
    for (const token of removed) {
        rows.push(summaryRow("removed", token));
    }
    for (const token of added) {
        rows.push(summaryRow("added", token));
    }
    for (const change of changed) {
        rows.push(changeRow(change));
    }
    return {
        empty: rows.length === 0,
        addedCount: added.length,
        removedCount: removed.length,
        changedCount: changed.length,
        rows,
    };
}

function summaryRow(
    kind: "added" | "removed",
    token: ThemeTokenSummary,
): ThemeDiffRow {
    return {
        kind,
        category: token.category,
        key: token.key,
        value: token.value,
        from: null,
        to: null,
    };
}

function changeRow(change: ThemeTokenChange): ThemeDiffRow {
    return {
        kind: "changed",
        category: change.category,
        key: change.key,
        value: null,
        from: change.from,
        to: change.to,
    };
}
