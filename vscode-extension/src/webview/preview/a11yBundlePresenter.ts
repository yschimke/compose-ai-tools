// A11y bundle presenter — fills the "Accessibility" tab body in
// `<data-tabs>` using the shared `<data-table>` primitive. Combines
// `a11y/hierarchy` (one row per node) with `a11y/atf` (findings
// merged onto the matching row when bounds align).
//
// The presenter is **stateless** — given the latest findings + nodes
// from `previewStore` and the focused preview, it produces the table
// rows and the overlay boxes. The caller (host shell wiring in
// `main.ts`) is responsible for re-running this whenever the focused
// preview, the cache, or the bundle's enabled-kinds set changes.

import { html, type TemplateResult } from "lit";
import type { AccessibilityFinding, AccessibilityNode } from "../shared/types";
import type { DataTableColumn } from "./components/DataTable";
import type { OverlayBox } from "./components/BoxOverlay";
import { parseBounds } from "./cardData";

export interface A11yRow {
    id: string;
    label: string;
    role: string;
    states: string;
    merged: boolean;
    findingCount: number;
    topFindingLevel: "error" | "warning" | "info" | null;
    boundsInScreen: string;
    bounds: { left: number; top: number; right: number; bottom: number } | null;
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

export function computeA11yBundleData(
    nodes: readonly AccessibilityNode[],
    findings: readonly AccessibilityFinding[],
): A11yBundleData {
    const rows: A11yRow[] = [];
    const overlay: OverlayBox[] = [];
    const findingsByBoundsKey = groupFindingsByBoundsKey(findings);
    const matchedKeys = new Set<string>();

    nodes.forEach((node, idx) => {
        const id = "a11y-" + idx;
        const bounds = parseBounds(node.boundsInScreen);
        const matchingFindings = bounds
            ? (findingsByBoundsKey.get(boundsKey(node.boundsInScreen)) ?? [])
            : [];
        const top = topLevel(matchingFindings);
        const row: A11yRow = {
            id,
            label: node.label || "(unlabelled)",
            role: node.role ?? "",
            states: node.states?.join(", ") ?? "",
            merged: node.merged,
            findingCount: matchingFindings.length,
            topFindingLevel: top,
            boundsInScreen: node.boundsInScreen,
            bounds,
        };
        rows.push(row);
        if (bounds) {
            overlay.push({
                id,
                bounds,
                level: top ?? "info",
                color: top ? undefined : PALETTE[idx % PALETTE.length],
                tooltip: tooltipFor(node),
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
        rows.push({
            id,
            label: f.viewDescription || "(no element)",
            role: f.type,
            states: "",
            merged: true,
            findingCount: 1,
            topFindingLevel: level,
            boundsInScreen: fBounds,
            bounds,
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

    return { rows, overlay };
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
            render: (row) => html`
                <div class="a11y-label-stack">
                    <strong>${row.label}</strong>
                    ${row.role
                        ? html`<span class="a11y-row-role">${row.role}</span>`
                        : ""}
                </div>
            `,
        },
        {
            header: "States",
            render: (row) => row.states || "—",
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

function tooltipFor(node: AccessibilityNode): string {
    const parts: string[] = [];
    if (node.label) parts.push(node.label);
    if (node.role) parts.push(node.role);
    if (node.states?.length) parts.push(node.states.join(", "));
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
