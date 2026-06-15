// Presenter for the history panel's semantics data-diff section (#1872). Pure: turns a
// `SemanticsDelta` (from the client-side `semanticsDiff` port) into display rows the webview renders
// beneath the pixel diff. No DOM — mirrors the `computeHistoryDiffBundleData` pattern so it's unit-
// testable without a browser.

import {
    type SemanticsDelta,
    type SemanticsNodeChange,
    type SemanticsNodeSummary,
} from "../shared/semanticsDiff";

export type SemanticsDiffRowKind = "added" | "removed" | "changed";

export interface SemanticsDiffFieldRow {
    field: string;
    from: string | null;
    to: string | null;
}

export interface SemanticsDiffRow {
    kind: SemanticsDiffRowKind;
    /** Stable node ref the change is keyed on. */
    ref: string;
    /** Human-readable label: ref plus testTag/role/text hints. */
    label: string;
    /** Field-level changes (only for `kind === "changed"`; empty for added/removed). */
    fields: SemanticsDiffFieldRow[];
}

export interface SemanticsDiffData {
    empty: boolean;
    addedCount: number;
    removedCount: number;
    changedCount: number;
    /** Removed first, then added, then changed — matches the CLI/daemon human ordering. */
    rows: SemanticsDiffRow[];
}

const EMPTY: SemanticsDiffData = {
    empty: true,
    addedCount: 0,
    removedCount: 0,
    changedCount: 0,
    rows: [],
};

/** Derives the display rows for a semantics delta. Null/empty delta → an empty data set. */
export function computeSemanticsDiffData(
    delta: SemanticsDelta | null | undefined,
): SemanticsDiffData {
    if (!delta) {
        return EMPTY;
    }
    const removed = delta.removed ?? [];
    const added = delta.added ?? [];
    const changed = delta.changed ?? [];
    const rows: SemanticsDiffRow[] = [];
    for (const node of removed) {
        rows.push({
            kind: "removed",
            ref: node.ref,
            label: describeSummary(node),
            fields: [],
        });
    }
    for (const node of added) {
        rows.push({
            kind: "added",
            ref: node.ref,
            label: describeSummary(node),
            fields: [],
        });
    }
    for (const change of changed) {
        rows.push({
            kind: "changed",
            ref: change.ref,
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

function describeSummary(node: SemanticsNodeSummary): string {
    const parts: string[] = [];
    if (node.testTag) {
        parts.push(`testTag=${node.testTag}`);
    }
    if (node.role) {
        parts.push(`role=${node.role}`);
    }
    const content = node.text ?? node.label;
    if (content) {
        parts.push(`text="${content}"`);
    }
    return parts.length === 0 ? node.ref : `${node.ref} (${parts.join(" ")})`;
}

function describeChange(change: SemanticsNodeChange): string {
    return change.anchor ? `${change.ref} (${change.anchor})` : change.ref;
}
