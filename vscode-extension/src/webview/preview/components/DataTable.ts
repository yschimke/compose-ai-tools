// `<data-table>` — generic table primitive used by every per-cluster
// bundle presenter. Column defs, row click → overlay highlight via
// `data-overlay-id`, header `Copy JSON` button, optional secondary
// actions slot. Bodies that need a different shape (tree, swatch grid)
// build their own primitive instead — this one's only purpose is to
// look identical across bundles.

import { LitElement, html, nothing, type TemplateResult } from "lit";
import { customElement, property, state } from "lit/decorators.js";

export interface DataTableColumn<TRow> {
    /** Header text. */
    header: string;
    /** Optional css class applied to `<th>` and each `<td>`. */
    cellClass?: string;
    /** Render the cell. Return a string for plain text, or a
     *  `TemplateResult` for richer DOM (badges, swatches, links). */
    render(row: TRow, rowIndex: number): TemplateResult | string;
}

export interface RowSelectedDetail<TRow> {
    overlayId: string | null;
    row: TRow;
    index: number;
}

export interface CopyJsonDetail {
    payload: unknown;
}

@customElement("data-table")
export class DataTable<TRow = unknown> extends LitElement {
    /** Table title shown in the header. */
    @property({ type: String }) heading = "";
    /** Optional one-line summary right of the heading (e.g. "12 nodes"). */
    @property({ type: String }) summary = "";

    @state() private columns: readonly DataTableColumn<TRow>[] = [];
    @state() private rows: readonly TRow[] = [];
    @state() private hoveredOverlayId: string | null = null;
    /** Stable id per row used to correlate with `<box-overlay>`. */
    private overlayId: (row: TRow, index: number) => string = (_row, index) =>
        "row-" + index;
    /** Whole-payload JSON shipped via `Copy JSON`. Defaults to `rows`. */
    private jsonPayload: () => unknown = () => this.rows;

    setColumns(cols: readonly DataTableColumn<TRow>[]): void {
        this.columns = cols;
    }

    setRows(rows: readonly TRow[]): void {
        this.rows = rows;
    }

    setOverlayId(fn: (row: TRow, index: number) => string): void {
        this.overlayId = fn;
    }

    setJsonPayload(fn: () => unknown): void {
        this.jsonPayload = fn;
    }

    /**
     * Externally driven highlight — kept so the overlay-side hover
     * (a box on the card) can light up the matching row. The opposite
     * direction (row hover → overlay) is handled internally by
     * dispatching `row-selected`.
     */
    setHoveredOverlayId(id: string | null): void {
        this.hoveredOverlayId = id;
    }

    protected createRenderRoot(): HTMLElement {
        return this;
    }

    protected render(): TemplateResult {
        return html`
            <div class="data-table-shell">
                <div class="data-table-header">
                    <div class="data-table-title">
                        <span class="data-table-heading">${this.heading}</span>
                        ${this.summary
                            ? html`<span class="data-table-summary"
                                  >· ${this.summary}</span
                              >`
                            : nothing}
                    </div>
                    <div class="data-table-actions">
                        <slot name="actions"></slot>
                        <button
                            type="button"
                            class="data-table-copy"
                            title="Copy JSON to clipboard"
                            aria-label="Copy JSON"
                            @click=${this.onCopy}
                        >
                            <i
                                class="codicon codicon-copy"
                                aria-hidden="true"
                            ></i>
                            <span>Copy JSON</span>
                        </button>
                    </div>
                </div>
                ${this.rows.length === 0
                    ? html`<div class="data-table-empty">No rows.</div>`
                    : this.renderTable()}
            </div>
        `;
    }

    private renderTable(): TemplateResult {
        return html`
            <table class="data-table-grid">
                <thead>
                    <tr>
                        ${this.columns.map(
                            (c) =>
                                html`<th class=${c.cellClass ?? ""}>
                                    ${c.header}
                                </th>`,
                        )}
                    </tr>
                </thead>
                <tbody>
                    ${this.rows.map((row, idx) => this.renderRow(row, idx))}
                </tbody>
            </table>
        `;
    }

    private renderRow(row: TRow, idx: number): TemplateResult {
        const id = this.overlayId(row, idx);
        const active = this.hoveredOverlayId === id;
        return html`
            <tr
                class=${active
                    ? "data-table-row data-table-row-active"
                    : "data-table-row"}
                data-legend-id=${id}
                @mouseenter=${() => this.onRowHover(row, idx, id)}
                @mouseleave=${() => this.onRowHover(row, idx, null)}
            >
                ${this.columns.map(
                    (c) =>
                        html`<td class=${c.cellClass ?? ""}>
                            ${c.render(row, idx)}
                        </td>`,
                )}
            </tr>
        `;
    }

    private onRowHover(
        row: TRow,
        index: number,
        overlayId: string | null,
    ): void {
        this.hoveredOverlayId = overlayId;
        this.dispatchEvent(
            new CustomEvent<RowSelectedDetail<TRow>>("row-selected", {
                detail: { overlayId, row, index },
                bubbles: true,
                composed: true,
            }),
        );
    }

    private onCopy(): void {
        const payload = this.jsonPayload();
        // The host wires the actual clipboard write — webview origin
        // can't rely on `navigator.clipboard` in every VS Code build.
        this.dispatchEvent(
            new CustomEvent<CopyJsonDetail>("copy-json", {
                detail: { payload },
                bubbles: true,
                composed: true,
            }),
        );
    }
}
