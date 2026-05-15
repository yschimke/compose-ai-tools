// `<bundle-row-detail>` — generic detail panel rendered below a
// bundle tab's `<data-table>` when the user clicks a row. The host
// supplies one or more sections (a short headline plus a list of
// label/value entries each); the component just renders the
// structure. Used by the a11y bundle today; any other bundle whose
// rows have richer-than-table info can wire the same way by
// listening for `<data-table>`'s `row-clicked` event and calling
// `setSections` here.
//
// The detail surface is intentionally a passive renderer — no row-
// state lives inside the component. Persistent click selection is
// owned by `<data-table>` (`selectedOverlayId`); the host calls
// `setSections([])` when the user re-clicks to deselect, and the
// component hides itself.

import { LitElement, html, type TemplateResult } from "lit";
import { customElement, state } from "lit/decorators.js";

export interface BundleRowDetailEntry {
    label: string;
    /** Plain text or a pre-built template (for code spans / links /
     *  swatches). The host decides; the component just slots it in. */
    value: string | TemplateResult;
}

export interface BundleRowDetailSection {
    /** Short section heading, e.g. `"Hierarchy"`, `"Findings"`. */
    heading: string;
    entries: readonly BundleRowDetailEntry[];
}

@customElement("bundle-row-detail")
export class BundleRowDetail extends LitElement {
    // `title` is already a public string on `HTMLElement` (tooltip
    // text); use a distinct private name to avoid the type clash.
    @state() private detailTitle = "";
    @state() private sections: readonly BundleRowDetailSection[] = [];

    setDetail(
        title: string,
        sections: readonly BundleRowDetailSection[],
    ): void {
        this.detailTitle = title;
        this.sections = sections;
    }

    clear(): void {
        this.detailTitle = "";
        this.sections = [];
    }

    protected createRenderRoot(): HTMLElement {
        return this;
    }

    protected render(): TemplateResult {
        if (this.sections.length === 0) {
            return html``;
        }
        return html`
            <div
                class="bundle-row-detail-panel"
                role="region"
                aria-label=${this.detailTitle || "Row detail"}
            >
                <header class="bundle-row-detail-header">
                    <span>${this.detailTitle || "Detail"}</span>
                </header>
                ${this.sections.map((s) => this.renderSection(s))}
            </div>
        `;
    }

    private renderSection(s: BundleRowDetailSection): TemplateResult {
        if (s.entries.length === 0) return html``;
        return html`
            <section class="bundle-row-detail-section">
                <h4 class="bundle-row-detail-section-heading">${s.heading}</h4>
                <dl class="bundle-row-detail-list">
                    ${s.entries.map(
                        (e) => html`
                            <div class="bundle-row-detail-entry">
                                <dt class="bundle-row-detail-key">
                                    ${e.label}
                                </dt>
                                <dd class="bundle-row-detail-value">
                                    ${e.value}
                                </dd>
                            </div>
                        `,
                    )}
                </dl>
            </section>
        `;
    }
}
