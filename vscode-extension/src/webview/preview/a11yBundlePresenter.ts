// A11y bundle presenter — fills the "Accessibility" tab body in
// `<data-tabs>` using the shared `<data-table>` primitive. Combines
// `a11y/hierarchy` (one row per node) with `a11y/atf` (findings
// merged onto the matching row when bounds align) and
// `a11y/touchTargets` (per-target size + WCAG findings merged onto
// the matching row by nodeId, or surfaced as orphan rows when the
// target has no matching hierarchy node).
//
// The presenter is **stateless** — given the latest findings + nodes
// + touchTargets from `previewStore` / the per-card cache and the
// focused preview, it produces the table rows and the overlay boxes.
// The caller (host shell wiring in `main.ts`) is responsible for re-
// running this whenever the focused preview, the cache, or the
// bundle's enabled-kinds set changes.

import { html, type TemplateResult } from "lit";
import type { AccessibilityFinding, AccessibilityNode } from "../shared/types";
import type { DataTableColumn } from "./components/DataTable";
import type { OverlayBox } from "./components/BoxOverlay";
import { parseBounds } from "./cardData";

/**
 * Wire shape for `a11y/touchTargets` — mirrors
 * `AccessibilityTouchTargetsPayload` in `AccessibilityModels.kt`. A
 * target's `findings` are rule-name strings (e.g.
 * `"TouchTargetTooSmall"`); the presenter does not interpret them
 * beyond "non-empty list ⇒ warn-level row".
 */
export interface AccessibilityTouchTarget {
    nodeId: string;
    boundsInScreen: string;
    widthDp: number;
    heightDp: number;
    findings: readonly string[];
    overlappingNodeIds?: readonly string[];
}

export interface A11yRow {
    id: string;
    label: string;
    /** Label as it should appear in the legend / overlay tooltip.
     *  For merged nodes that the daemon emitted without a `label`
     *  (e.g. a `clickable` row whose only content is inner `Text`
     *  children) this is the concatenation of the immediately-
     *  following unmerged children's labels — the TalkBack-style
     *  merged announcement. Falls back to `label` otherwise, with
     *  the `(unlabelled)` placeholder applied for empty strings. */
    displayLabel: string;
    role: string;
    states: string;
    merged: boolean;
    findingCount: number;
    topFindingLevel: "error" | "warning" | "info" | null;
    boundsInScreen: string;
    bounds: { left: number; top: number; right: number; bottom: number } | null;
    /** Pre-formatted touch-target size, populated when this row has
     *  a corresponding `a11y/touchTargets` entry. `null` otherwise —
     *  the "Size" column renders a dash. */
    touchTargetSizeDp: string | null;
    /** Number of other targets this row's touch target overlaps with,
     *  per `AccessibilityTouchTarget.overlappingNodeIds`. `0` when the
     *  target has no overlap data or no other-target hits — the "Size"
     *  cell renders no overlap badge in that case. */
    touchTargetOverlapCount: number;
    /** Visual indent depth (`0` for top-level / merged nodes, `1`
     *  for unmerged children of the nearest preceding merged
     *  ancestor in emission order). The wire format
     *  (`AccessibilityNode`) is flat — `merged: false` is the
     *  daemon's only hint that a node sits underneath a focusable
     *  ancestor, so we use emission order as the heuristic parent.
     *  Orphan finding / touch-target rows always render at depth 0
     *  because they aren't bound to a hierarchy ancestor. */
    depth: 0 | 1;
}

export interface A11yBundleData {
    rows: readonly A11yRow[];
    overlay: readonly OverlayBox[];
}

const PALETTE = [
    "#f28b82",
    "#aecbfa",
    "#a8dab5",
    "#fdd663",
    "#d7aefb",
    "#fcad70",
    "#80cbc4",
    "#f6aea9",
];

export interface A11yBundleOptions {
    /** Show only `merged: true` nodes — the focusable / screen-reader
     *  stops. Unmerged children (e.g. inner `Text` inside a `Button`)
     *  duplicate their merged ancestor's bounds and clutter the
     *  overlay without adding new information for most reviews, but
     *  in the data-table they make the tree structure (which text
     *  rolls up into which focusable container) visible. Defaults
     *  to `false`; flip to `true` to drop unmerged rows from the
     *  table (the legend filters them out either way). */
    mergedOnly?: boolean;
}

export function computeA11yBundleData(
    nodes: readonly AccessibilityNode[],
    findings: readonly AccessibilityFinding[],
    touchTargets: readonly AccessibilityTouchTarget[] = [],
    options: A11yBundleOptions = {},
): A11yBundleData {
    const mergedOnly = options.mergedOnly ?? false;
    const rows: A11yRow[] = [];
    const overlay: OverlayBox[] = [];
    const findingsByBoundsKey = groupFindingsByBoundsKey(findings);
    const matchedKeys = new Set<string>();
    // Pre-compute the merged announcement label for each merged node
    // by walking its run of immediately-following `merged: false`
    // children and concatenating their text — the wire format is flat
    // so emission order is our only handle on parent/child. This is
    // what TalkBack would read aloud for the focusable, and it's what
    // the legend wants in place of the daemon's empty `label` on
    // containers like `clickable` rows whose own text lives in
    // unmerged Text children.
    const mergedChildLabels = collectMergedChildLabels(nodes);
    // Index touch targets by bounds string — the daemon emits both
    // the hierarchy and the targets keyed on the same source bounds,
    // so we can merge per-node without exposing the daemon-internal
    // nodeId (which the hierarchy nodes don't carry in this surface).
    const targetByBounds = new Map<string, AccessibilityTouchTarget>();
    for (const t of touchTargets) {
        if (t && typeof t.boundsInScreen === "string") {
            targetByBounds.set(boundsKey(t.boundsInScreen), t);
        }
    }
    const consumedTargets = new Set<AccessibilityTouchTarget>();

    nodes.forEach((node, idx) => {
        // The daemon serializes `AccessibilityNode` with
        // `encodeDefaults = false`, so `merged: true` (the Kotlin
        // default) is omitted from the wire JSON and lands here as
        // `undefined`. Treat anything other than an explicit `false`
        // as merged — otherwise the `mergedOnly` filter below drops
        // every TalkBack stop on the wire, leaving the bundle empty
        // even when a hierarchy with 12 nodes arrived.
        const isMerged = node.merged !== false;
        // Index-stable id so the `mergedOnly` filter below doesn't
        // renumber ids across renders — the row's overlay box, the
        // data-table row, and the legend entry all share the index-
        // based id. The matched-bounds bookkeeping for orphan
        // findings runs unconditionally further down, so skipping
        // here doesn't cause a finding on an unmerged child to leak
        // out as an orphan row.
        if (mergedOnly && !isMerged) return;
        const id = "a11y-" + idx;
        const bounds = parseBounds(node.boundsInScreen);
        const matchingFindings = bounds
            ? (findingsByBoundsKey.get(boundsKey(node.boundsInScreen)) ?? [])
            : [];
        const target = targetByBounds.get(boundsKey(node.boundsInScreen));
        if (target) consumedTargets.add(target);
        const targetFindingCount = target?.findings?.length ?? 0;
        const top = mergeLevel(
            topLevel(matchingFindings),
            targetFindingCount > 0 ? "warning" : null,
        );
        const rawLabel = node.label ?? "";
        const inheritedLabel =
            isMerged && !rawLabel ? (mergedChildLabels.get(idx) ?? "") : "";
        const displayLabel = rawLabel || inheritedLabel || "(unlabelled)";
        const row: A11yRow = {
            id,
            label: rawLabel || "(unlabelled)",
            displayLabel,
            role: node.role ?? "",
            states: node.states?.join(", ") ?? "",
            merged: isMerged,
            findingCount: matchingFindings.length + targetFindingCount,
            topFindingLevel: top,
            boundsInScreen: node.boundsInScreen,
            bounds,
            touchTargetSizeDp: target ? formatTargetSize(target) : null,
            touchTargetOverlapCount: target?.overlappingNodeIds?.length ?? 0,
            // Unmerged nodes sit under the nearest preceding merged
            // ancestor in emission order; render them indented so
            // the structure is visible without inventing parent ids.
            depth: isMerged ? 0 : 1,
        };
        rows.push(row);
        // Only merged nodes draw overlay boxes — unmerged children
        // share (or sit inside) their merged ancestor's bounds, so
        // adding their boxes just stacks duplicates on the preview
        // and clutters the screen-reader focus map. They still show
        // in the table so the tree structure is visible.
        if (bounds && isMerged) {
            overlay.push({
                id,
                bounds,
                level: top ?? "info",
                color: top ? undefined : PALETTE[idx % PALETTE.length],
                tooltip: tooltipFor(node, target),
            });
        }
    });

    // Append findings that didn't match a hierarchy node so we don't
    // silently drop them — they show in the table with no overlay box.
    // Empty / blank bounds keys are not real bounds, so they don't
    // enter `matchedKeys` and a finding with blank bounds never
    // short-circuits as "matched" against a node that also happened
    // to have blank bounds (would silently hide accessibility issues).
    nodes.forEach((n) => {
        const key = boundsKey(n.boundsInScreen);
        if (key) matchedKeys.add(key);
    });
    findings.forEach((f, idx) => {
        const fBounds = f.boundsInScreen ?? "";
        const fKey = boundsKey(fBounds);
        if (fKey && matchedKeys.has(fKey)) return;
        const id = "a11y-finding-orphan-" + idx;
        const bounds = parseBounds(fBounds);
        const level = normLevel(f.level);
        const orphanLabel = f.viewDescription || "(no element)";
        rows.push({
            id,
            label: orphanLabel,
            displayLabel: orphanLabel,
            role: f.type,
            states: "",
            merged: true,
            findingCount: 1,
            topFindingLevel: level,
            boundsInScreen: fBounds,
            bounds,
            touchTargetSizeDp: null,
            touchTargetOverlapCount: 0,
            depth: 0,
        });
        if (bounds) {
            overlay.push({
                id,
                bounds,
                level,
                tooltip: f.level + " · " + f.type + " · " + f.message,
            });
        }
    });

    // Touch targets that didn't match any hierarchy node — surface
    // them as orphan rows so the subscription isn't silently dropped.
    // Same intuition as orphan ATF findings above: better to show "we
    // got data here that we can't correlate" than to hide it.
    touchTargets.forEach((t, idx) => {
        if (!t || consumedTargets.has(t)) return;
        const findingsCount = t.findings?.length ?? 0;
        if (findingsCount === 0) return;
        const tKey = boundsKey(t.boundsInScreen);
        // If the target's bounds already match a hierarchy node we
        // know about, the merge path above handled it; this branch is
        // for the residual case where the bounds string differs.
        if (tKey && matchedKeys.has(tKey)) return;
        const id = "a11y-touchtarget-orphan-" + idx;
        const bounds = parseBounds(t.boundsInScreen ?? "");
        const orphanTargetLabel = t.nodeId
            ? "node " + t.nodeId
            : "(touch target)";
        rows.push({
            id,
            label: orphanTargetLabel,
            displayLabel: orphanTargetLabel,
            role: "TouchTarget",
            states: t.findings.join(", "),
            merged: true,
            findingCount: findingsCount,
            topFindingLevel: "warning",
            boundsInScreen: t.boundsInScreen ?? "",
            bounds,
            touchTargetSizeDp: formatTargetSize(t),
            touchTargetOverlapCount: t.overlappingNodeIds?.length ?? 0,
            depth: 0,
        });
        if (bounds) {
            overlay.push({
                id,
                bounds,
                level: "warning",
                tooltip:
                    "Touch target · " +
                    formatTargetSize(t) +
                    " · " +
                    t.findings.join(", "),
            });
        }
    });

    return { rows, overlay };
}

function collectMergedChildLabels(
    nodes: readonly AccessibilityNode[],
): Map<number, string> {
    const out = new Map<number, string>();
    for (let i = 0; i < nodes.length; i++) {
        const n = nodes[i];
        if (n.merged === false) continue;
        const childTexts: string[] = [];
        for (let j = i + 1; j < nodes.length; j++) {
            if (nodes[j].merged !== false) break;
            const childLabel = nodes[j].label;
            if (childLabel) childTexts.push(childLabel);
        }
        if (childTexts.length > 0) out.set(i, childTexts.join(" · "));
    }
    return out;
}

function mergeLevel(
    a: "error" | "warning" | "info" | null,
    b: "error" | "warning" | "info" | null,
): "error" | "warning" | "info" | null {
    if (a === "error" || b === "error") return "error";
    if (a === "warning" || b === "warning") return "warning";
    if (a === "info" || b === "info") return "info";
    return null;
}

function formatTargetSize(t: AccessibilityTouchTarget): string {
    const w = Number.isFinite(t.widthDp) ? Math.round(t.widthDp) : 0;
    const h = Number.isFinite(t.heightDp) ? Math.round(t.heightDp) : 0;
    return `${w}×${h} dp`;
}

export function a11yTableColumns(): readonly DataTableColumn<A11yRow>[] {
    return [
        {
            header: "",
            cellClass: "a11y-swatch-cell",
            render: (row) => html`
                <span
                    class="a11y-row-swatch"
                    data-level=${row.topFindingLevel ?? "info"}
                ></span>
            `,
        },
        {
            header: "Label",
            cellClass: "a11y-label-cell",
            render: (row) => {
                const stackClasses = ["a11y-label-stack"];
                if (row.depth > 0) stackClasses.push("a11y-label-stack-indent");
                stackClasses.push(
                    row.merged
                        ? "a11y-label-stack-merged"
                        : "a11y-label-stack-unmerged",
                );
                return html`
                    <div
                        class=${stackClasses.join(" ")}
                        data-depth=${row.depth}
                        data-merged=${row.merged ? "true" : "false"}
                    >
                        ${
                            row.depth > 0
                                ? html`<span
                                      class="a11y-tree-arm"
                                      aria-hidden="true"
                                      >↳</span
                                  >`
                                : html`<span
                                      class="a11y-tree-pip"
                                      aria-hidden="true"
                                      title="Merged (focusable / screen-reader stop)"
                                      >●</span
                                  >`
                        }
                        <div class="a11y-label-text">
                            ${
                                row.merged
                                    ? html`<strong>${row.label}</strong>`
                                    : html`<span
                                          class="a11y-label-unmerged-text"
                                          >${row.label}</span
                                      >`
                            }
                            ${
                                row.role
                                    ? html`<span class="a11y-row-role"
                                          >${row.role}</span
                                      >`
                                    : ""
                            }
                        </div>
                    </div>
                `;
            },
        },
        {
            header: "States",
            render: (row) => row.states || "—",
        },
        {
            header: "Size",
            cellClass: "a11y-size-cell",
            render: (row) => {
                if (!row.touchTargetSizeDp) return "—";
                if (row.touchTargetOverlapCount === 0) {
                    return row.touchTargetSizeDp;
                }
                const overlapTitle =
                    row.touchTargetOverlapCount === 1
                        ? "Overlaps 1 other touch target"
                        : `Overlaps ${row.touchTargetOverlapCount} other touch targets`;
                return html`
                    ${row.touchTargetSizeDp}
                    <span
                        class="a11y-overlap-badge"
                        title=${overlapTitle}
                        data-overlap-count=${row.touchTargetOverlapCount}
                        >↔${row.touchTargetOverlapCount}</span
                    >
                `;
            },
        },
        {
            header: "Findings",
            cellClass: "a11y-findings-cell",
            render: (row) =>
                row.findingCount === 0
                    ? "—"
                    : html`<span
                          class="a11y-findings-badge"
                          data-level=${row.topFindingLevel ?? "info"}
                          >${row.findingCount}</span
                      >`,
        },
    ];
}

function topLevel(
    findings: readonly AccessibilityFinding[],
): "error" | "warning" | "info" | null {
    if (findings.length === 0) return null;
    let best: "error" | "warning" | "info" = "info";
    for (const f of findings) {
        const l = normLevel(f.level);
        if (l === "error") return "error";
        if (l === "warning") best = "warning";
    }
    return best;
}

function normLevel(s: string): "error" | "warning" | "info" {
    const lower = (s || "").toLowerCase();
    if (lower === "error") return "error";
    if (lower === "warning" || lower === "warn") return "warning";
    return "info";
}

function tooltipFor(
    node: AccessibilityNode,
    target: AccessibilityTouchTarget | undefined,
): string {
    const parts: string[] = [];
    if (node.label) parts.push(node.label);
    if (node.role) parts.push(node.role);
    if (node.states?.length) parts.push(node.states.join(", "));
    if (target) {
        parts.push(formatTargetSize(target));
        if (target.findings.length > 0) parts.push(target.findings.join(", "));
    }
    return parts.join(" · ");
}

function boundsKey(s: string): string {
    return (s || "").trim();
}

function groupFindingsByBoundsKey(
    findings: readonly AccessibilityFinding[],
): Map<string, AccessibilityFinding[]> {
    const out = new Map<string, AccessibilityFinding[]>();
    for (const f of findings) {
        const key = boundsKey(f.boundsInScreen ?? "");
        if (!key) continue;
        const list = out.get(key) ?? [];
        list.push(f);
        out.set(key, list);
    }
    return out;
}
