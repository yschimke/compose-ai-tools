// Resources bundle presenter — fills the "Resources" tab body with
// one row per `(resourceType, resourceName, packageName)` triple the
// daemon emits via the `resources/used` data product. The wire shape
// mirrors `ResourcesUsedDataProduct.kt`:
//
//   { references: [{ resourceType, resourceName, packageName,
//                    resolvedValue, resolvedFile, consumers }, ...] }
//
// resolvedValue is non-null for values like strings / colors / dimens;
// resolvedFile is non-null for binary assets (drawable / mipmap / raw)
// where the resource resolves to a file on disk. This presenter feeds
// the `<data-table>`-based bundle UI; click-through deep-linking on the
// resolved file/value cells lives in the table renderer.
//
// No overlay layer: a resource is not bound to a single rectangle on
// the canvas (a string is used by N text nodes; a drawable by M image
// nodes). Hover correlation with `compose/semantics` consumer bounds
// is a follow-up.

import { html, type TemplateResult } from "lit";
import type { DataTableColumn } from "./components/DataTable";

export interface ResourceUsedRow {
    /** Stable id for `<data-table>` row hover / overlay correlation. */
    id: string;
    resourceType: string;
    resourceName: string;
    packageName: string;
    /** Pre-formatted resolved value (string / color / dimen). `null`
     *  when the resource is a binary file. */
    resolvedValue: string | null;
    /** Filesystem path the resource resolved to. `null` for values. */
    resolvedFile: string | null;
    /** Number of node references that consumed this resource. */
    consumerCount: number;
    /** Per-row consumer node ids sourced from
     *  `ref.consumers[].nodeId`. Empty when the daemon attached no
     *  consumers. The host uses this for the hover overlay: hovering
     *  a row paints a transient layer over those nodes on the focused
     *  card via `buildSemanticsBoundsMap` + `consumerOverlayBoxes`
     *  (shared with the Theming bundle hover). */
    consumerNodeIds: readonly string[];
}

export interface ResourcesBundleData {
    rows: readonly ResourceUsedRow[];
}

/**
 * Defensive parse — the daemon payload shape can drift across
 * versions, and presenters in this tree treat unknown / malformed
 * fields as "skip the row" rather than crash the panel.
 */
export function computeResourcesBundleData(
    payload: unknown,
): ResourcesBundleData {
    if (!payload || typeof payload !== "object") return { rows: [] };
    const references = (payload as { references?: unknown }).references;
    if (!Array.isArray(references)) return { rows: [] };
    const rows: ResourceUsedRow[] = [];
    for (let i = 0; i < references.length; i++) {
        const item = references[i];
        if (!item || typeof item !== "object") continue;
        const ref = item as Record<string, unknown>;
        const resourceType =
            typeof ref.resourceType === "string" ? ref.resourceType : "";
        const resourceName =
            typeof ref.resourceName === "string" ? ref.resourceName : "";
        if (!resourceType || !resourceName) continue;
        const consumerNodeIds = extractConsumerNodeIds(ref.consumers);
        rows.push({
            id: "resource-" + i + "-" + resourceType + "-" + resourceName,
            resourceType,
            resourceName,
            packageName:
                typeof ref.packageName === "string" ? ref.packageName : "",
            resolvedValue:
                typeof ref.resolvedValue === "string"
                    ? ref.resolvedValue
                    : null,
            resolvedFile:
                typeof ref.resolvedFile === "string" ? ref.resolvedFile : null,
            consumerCount: consumerNodeIds.length,
            consumerNodeIds,
        });
    }
    return { rows };
}

export function resourcesTableColumns(): readonly DataTableColumn<ResourceUsedRow>[] {
    return [
        {
            header: "Type",
            cellClass: "resource-type-cell",
            render: (row) => html`<code>${row.resourceType}</code>`,
        },
        {
            header: "Name",
            cellClass: "resource-name-cell",
            render: (row) => row.resourceName,
        },
        {
            header: "Package",
            cellClass: "resource-package-cell",
            render: (row) =>
                row.packageName ? html`<code>${row.packageName}</code>` : "—",
        },
        {
            header: "Resolved",
            cellClass: "resource-resolved-cell",
            render: (row) => renderResolved(row),
        },
        {
            header: "Uses",
            cellClass: "resource-uses-cell",
            render: (row) => String(row.consumerCount),
        },
    ];
}

function renderResolved(row: ResourceUsedRow): TemplateResult | string {
    if (row.resolvedValue !== null) {
        return html`<span class="resource-resolved-value"
            >${row.resolvedValue}</span
        >`;
    }
    if (row.resolvedFile !== null) {
        return html`<code class="resource-resolved-file"
            >${row.resolvedFile}</code
        >`;
    }
    return "—";
}

/** Extract a unique, in-order list of consumer node ids from the
 *  daemon's `consumers: [{ nodeId: string }, ...]` array. The wire
 *  shape can shift across daemon versions — defensive narrowing
 *  drops anything that isn't a `{ nodeId: string }`. Duplicates are
 *  removed so the overlay paints one box per node even if the
 *  daemon double-lists the same consumer. */
function extractConsumerNodeIds(value: unknown): readonly string[] {
    if (!Array.isArray(value)) return [];
    const seen = new Set<string>();
    const out: string[] = [];
    for (const entry of value) {
        if (!entry || typeof entry !== "object") continue;
        const id = (entry as { nodeId?: unknown }).nodeId;
        if (typeof id !== "string" || id.length === 0) continue;
        if (seen.has(id)) continue;
        seen.add(id);
        out.push(id);
    }
    return out;
}
