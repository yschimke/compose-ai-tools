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
            consumerCount: Array.isArray(ref.consumers)
                ? ref.consumers.length
                : 0,
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
