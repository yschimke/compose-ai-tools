// Build the `<bundle-row-detail>` sections shown when the user
// clicks an a11y row in the data table. Surfaces the full per-node
// info that the table can't fit in its columns — bounds, every
// matching ATF finding's message, touch-target rule findings, raw
// node-state strings. Pure on its inputs so it's straightforward to
// unit-test against the same fixture shapes the bundle presenter
// uses.

import { html } from "lit";
import type { AccessibilityFinding } from "../shared/types";
import type { AccessibilityTouchTarget, A11yRow } from "./a11yBundlePresenter";
import type {
    BundleRowDetailEntry,
    BundleRowDetailSection,
} from "./components/BundleRowDetail";

export function buildA11yRowDetail(
    row: A11yRow,
    findings: readonly AccessibilityFinding[],
    touchTargets: readonly AccessibilityTouchTarget[],
): readonly BundleRowDetailSection[] {
    const sections: BundleRowDetailSection[] = [];

    // ---- Element ------------------------------------------------------
    const elementEntries: BundleRowDetailEntry[] = [];
    if (row.role) elementEntries.push({ label: "Role", value: row.role });
    elementEntries.push({
        label: "Focus target",
        value: row.merged ? "yes (TalkBack stop)" : "no (child of merged)",
    });
    if (row.states) {
        elementEntries.push({
            label: "States",
            value: row.states,
        });
    }
    if (row.boundsInScreen) {
        elementEntries.push({
            label: "Bounds",
            value: html`<code>${row.boundsInScreen}</code>`,
        });
    }
    sections.push({ heading: "Element", entries: elementEntries });

    // ---- Findings (ATF) ----------------------------------------------
    // The bundle row carries `findingCount` but not the per-finding
    // messages — pull them from the source list by matching on
    // bounds, same join key `computeA11yBundleData` uses. Orphan
    // findings (no matching hierarchy node) are surfaced this way
    // too because the orphan row's own bounds match the finding.
    const matchedFindings = findings.filter(
        (f) =>
            !!row.boundsInScreen &&
            (f.boundsInScreen ?? "") === row.boundsInScreen,
    );
    if (matchedFindings.length > 0) {
        sections.push({
            heading: "Findings (ATF)",
            entries: matchedFindings.map((f) => ({
                label: f.level,
                value: html`<span class="row-detail-finding-message"
                        >${f.message}</span
                    >
                    <span class="row-detail-finding-type"
                        >· <code>${f.type}</code></span
                    >`,
            })),
        });
    }

    // ---- Touch target -------------------------------------------------
    const target = touchTargets.find(
        (t) =>
            !!row.boundsInScreen &&
            (t.boundsInScreen ?? "") === row.boundsInScreen,
    );
    if (target) {
        const targetEntries: BundleRowDetailEntry[] = [
            {
                label: "Size",
                value: row.touchTargetSizeDp ?? formatSize(target),
            },
        ];
        if (target.findings.length > 0) {
            targetEntries.push({
                label: "Issues",
                value: target.findings.join(", "),
            });
        }
        if (target.overlappingNodeIds && target.overlappingNodeIds.length > 0) {
            targetEntries.push({
                label: "Overlaps",
                value: html`<code
                    >${target.overlappingNodeIds.join(", ")}</code
                >`,
            });
        }
        sections.push({ heading: "Touch target", entries: targetEntries });
    }

    return sections;
}

function formatSize(t: AccessibilityTouchTarget): string {
    const w = Number.isFinite(t.widthDp) ? Math.round(t.widthDp) : 0;
    const h = Number.isFinite(t.heightDp) ? Math.round(t.heightDp) : 0;
    return `${w}×${h} dp`;
}
