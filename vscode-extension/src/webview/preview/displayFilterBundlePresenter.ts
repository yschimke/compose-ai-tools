// Display-filter bundle presenter — fills the "Display" tab body with
// one row per post-process colour-matrix variant the daemon emits.
// `DisplayFilters.kt` declares the enum of filters; the bundle
// registry's `displayfilter/*` kinds are placeholders for the per-
// filter on/off toggles users pick from the Configure… expander.
//
// The presenter is **stateless** — given the set of subscribed filter
// ids it returns the row shape `<data-table>` consumes. There is no
// overlay layer because display filters are an image-level transform;
// users pick a filter to swap the whole card image, not a region.
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
    /** Wire kind, e.g. `displayfilter/grayscale`. */
    kind: string;
    /** Tail of the kind — the human-readable filter id. */
    filterId: string;
    /** Bundle label from the registry; used as the row's "name". */
    label: string;
}

export interface DisplayFilterBundleData {
    rows: readonly DisplayFilterRow[];
}

export interface DisplayFilterEntry {
    kind: string;
    label: string;
}

/**
 * Build one row per filter the bundle catalogue advertises. The
 * presenter does not subscribe to the daemon-side filter PNGs — the
 * point of the tab is the user-facing toggle UI, the actual filtered
 * image rendering is daemon-side once that plumbing lands (see file
 * header TODO).
 */
export function computeDisplayFilterBundleData(
    entries: readonly DisplayFilterEntry[],
): DisplayFilterBundleData {
    const rows: DisplayFilterRow[] = entries.map((e) => {
        const slash = e.kind.lastIndexOf("/");
        const filterId = slash >= 0 ? e.kind.slice(slash + 1) : e.kind;
        return {
            id: "displayfilter-" + filterId,
            kind: e.kind,
            filterId,
            label: e.label,
        };
    });
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
    // filter id. Once the daemon-side display-filter producer emits
    // a per-preview PNG path (see DisplayFilterDataProducer.kt), the
    // swatch will swap for an actual thumbnail.
    return html`
        <span
            class="displayfilter-thumb"
            data-filter=${row.filterId}
            aria-hidden="true"
            >${row.filterId.slice(0, 2).toUpperCase()}</span
        >
    `;
}
