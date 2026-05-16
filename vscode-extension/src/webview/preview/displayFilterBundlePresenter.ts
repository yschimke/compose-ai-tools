// Display-filter bundle presenter — fills the "Display" tab body with
// one row per post-process colour-matrix variant the daemon emits.
//
// The daemon advertises a single wire kind, `displayfilter/variants`,
// whose payload manifest enumerates every enabled filter:
//
//     { variants: [{ filter, path, mediaType }, ...] }
//
// (See `DisplayFilterDataProducer.VariantsManifest` and
// `DisplayFilterDataProductRegistry.kt`.) The presenter walks the
// manifest's `variants` array and produces one row per filter. It is
// **stateless** — given the parsed payload it returns the row shape
// `<data-table>` consumes. There is no overlay layer because display
// filters are an image-level transform; users pick a filter to swap
// the whole card image, not a region.
//
// Out of scope for v1: actually swapping the focused card's image
// when a row is clicked. The daemon-side override plumbing isn't
// wired yet — `<data-table>` already fires `row-selected` so host
// wiring can pick that up later. See TODO in `main.ts`.

import { html, type TemplateResult } from "lit";
import type { DataTableColumn } from "./components/DataTable";

export interface DisplayFilterRow {
    /** Stable id for `<data-table>` row hover / overlay correlation. */
    id: string;
    /** Filter id as it appears in the manifest (e.g. `grayscale`). */
    filterId: string;
    /** Human-readable filter label (falls back to `filterId`). */
    label: string;
    /** PNG path from the manifest, if present. */
    path?: string;
}

export interface DisplayFilterBundleData {
    rows: readonly DisplayFilterRow[];
}

/**
 * Parsed shape of the `displayfilter/variants` payload — narrow to
 * what the presenter reads. The daemon emits `mediaType` too; the
 * presenter ignores it for now (everything is image/png) but the
 * field is part of the wire contract.
 */
export interface DisplayFilterVariantEntry {
    filter: string;
    path?: string;
    label?: string;
}

export interface DisplayFilterVariantsPayload {
    variants?: readonly DisplayFilterVariantEntry[];
}

/**
 * Build one row per entry in the manifest. Returns an empty list
 * when the payload is missing or malformed; the bundle tab then
 * renders its empty-state hint rather than a half-built table.
 */
export function computeDisplayFilterBundleData(
    payload: DisplayFilterVariantsPayload | null | undefined,
): DisplayFilterBundleData {
    const variants = payload?.variants ?? [];
    const rows: DisplayFilterRow[] = variants.map((v) => ({
        id: "displayfilter-" + v.filter,
        filterId: v.filter,
        label: v.label ?? humanLabel(v.filter),
        path: v.path,
    }));
    return { rows };
}

export function displayFilterTableColumns(): readonly DataTableColumn<DisplayFilterRow>[] {
    return [
        {
            header: "",
            cellClass: "displayfilter-thumb-cell",
            render: (row) => renderThumbnail(row),
        },
        {
            header: "Filter",
            cellClass: "displayfilter-id-cell",
            render: (row) => html`<code>${row.filterId}</code>`,
        },
        {
            header: "Name",
            render: (row) => row.label,
        },
    ];
}

function renderThumbnail(row: DisplayFilterRow): TemplateResult {
    // Placeholder swatch labelled with the first two chars of the
    // filter id. Once the bundle wires the manifest's per-variant
    // PNG path into an `<img>`, the swatch will swap for an actual
    // thumbnail.
    return html`
        <span
            class="displayfilter-thumb"
            data-filter=${row.filterId}
            aria-hidden="true"
            >${row.filterId.slice(0, 2).toUpperCase()}</span
        >
    `;
}

function humanLabel(filterId: string): string {
    if (filterId.length === 0) return filterId;
    return filterId.charAt(0).toUpperCase() + filterId.slice(1);
}
