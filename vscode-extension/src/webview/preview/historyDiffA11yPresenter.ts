// Presenter for the history panel's a11y data-diff section (#1872). Pure: turns an `A11yDelta`
// (from the client-side `a11yDiff` differ) into display rows the webview renders beneath the pixel
// diff. No DOM — mirrors `historyDiffSemanticsPresenter` / `historyDiffThemePresenter`.

import {
    type A11yDelta,
    type A11yNodeChange,
    type A11yNodeSummary,
} from "../shared/a11yDiff";

export type A11yDiffRowKind = "added" | "removed" | "changed";

export interface A11yDiffFieldRow {
    field: string;
    from: string | null;
    to: string | null;
}

export interface A11yDiffRow {
    kind: A11yDiffRowKind;
    /** Human-readable label: role/label hints (falls back to the match key). */
    label: string;
    /** Field-level changes (only for `kind === "changed"`; empty for added/removed). */
    fields: A11yDiffFieldRow[];
}

export interface A11yDiffData {
    empty: boolean;
    addedCount: number;
    removedCount: number;
    changedCount: number;
    /** Removed first, then added, then changed — matches the semantics/theme sections. */
    rows: A11yDiffRow[];
}

const EMPTY: A11yDiffData = {
    empty: true,
    addedCount: 0,
    removedCount: 0,
    changedCount: 0,
    rows: [],
};

/** Derives the display rows for an a11y delta. Null/empty delta → an empty data set. */
export function computeA11yDiffData(
    delta: A11yDelta | null | undefined,
): A11yDiffData {
    if (!delta) {
        return EMPTY;
    }
    const removed = delta.removed ?? [];
    const added = delta.added ?? [];
    const changed = delta.changed ?? [];
    const rows: A11yDiffRow[] = [];
    for (const node of removed) {
        rows.push({
            kind: "removed",
            label: describeSummary(node),
            fields: [],
        });
    }
    for (const node of added) {
        rows.push({ kind: "added", label: describeSummary(node), fields: [] });
    }
    for (const change of changed) {
        rows.push({
            kind: "changed",
            label: describeChange(change),
            fields: change.changes.map((c) => ({
                field: c.field,
                from: c.from,
                to: c.to,
            })),
        });
    }
    return {
        empty: rows.length === 0,
        addedCount: added.length,
        removedCount: removed.length,
        changedCount: changed.length,
        rows,
    };
}

function describeSummary(node: A11yNodeSummary): string {
    return describe(node.role, node.label, node.key);
}

function describeChange(change: A11yNodeChange): string {
    return describe(change.role, change.label, change.key);
}

function describe(
    role: string | null,
    label: string | null,
    key: string,
): string {
    const parts: string[] = [];
    if (role) {
        parts.push(`role=${role}`);
    }
    if (label) {
        parts.push(`label="${label}"`);
    }
    return parts.length === 0 ? key : parts.join(" ");
}
