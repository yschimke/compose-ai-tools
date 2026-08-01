// `<bundle-legend>` — side panel beside the focused preview that
// surfaces per-overlay info the user needs to read at a glance:
// colour swatch (matching the overlay box on the preview image), a
// label, and a short subtitle. Generic across bundles — each bundle's
// refresh function can populate entries for its kind, and the legend
// renders the entries that belong to the currently-active tab.
//
// Visibility rules (gated by the host shell in `main.ts`):
//
//   - Only in focus layout — grid mode doesn't have a notion of "the"
//     preview to highlight against.
//   - Only when `composePreview.earlyFeatures.enabled` is true — the
//     legend rides the same gate as the rest of the bundle UI.
//   - Only when the active tab's bundle has supplied entries — empty
//     space next to the preview is just visual noise.
//
// Correlation contract (`id` shared across surfaces):
//
//   - Hovering an entry dispatches `legend-hovered` with the entry
//     id. Host wires that to the per-bundle `<box-overlay>`'s
//     `setActiveOverlayId` so the matching overlay box lights up on
//     the preview.
//   - Clicking an entry dispatches `legend-selected` with the entry
//     id. Host scrolls the matching row in the bundle's
//     `<data-table>` into view (host may also expand a detail panel).
//   - Hosts that drive the highlight from the other side (e.g. user
//     hovers an overlay box on the preview) call `setActiveEntryId`
//     to mirror the highlight on the legend.

import { LitElement, html, type TemplateResult } from "lit";
import { customElement, state } from "lit/decorators.js";
import { ref } from "lit/directives/ref.js";

export interface BundleLegendEntry {
    /** Same id used for `data-overlay-id` on the matching overlay box
     *  and `data-legend-id` on the matching `<data-table>` row, so
     *  hover / scroll correlation is symmetric across the three
     *  surfaces. */
    id: string;
    label: string;
    /** Short subtitle. For a11y this is `role · key states`; other
     *  bundles can put whatever short tag is useful (e.g. a perf
     *  bundle could show `12 recompositions`). Empty / omitted hides
     *  the subtitle row. */
    detail?: string;
    /** Drives the swatch accent. Matches the existing overlay
     *  palette (`error` / `warning` / `info`) so the colour the user
     *  sees on the preview matches the legend. */
    level: "error" | "warning" | "info";
    /** Optional explicit colour. Takes precedence over `level` for
     *  the swatch fill — used by bundles that paint individual
     *  palette colours (a11y info nodes) rather than a fixed level
     *  tint. */
    color?: string;
}

export interface BundleLegendHoveredDetail {
    /** `null` on `mouseleave` so the host can clear the active
     *  overlay highlight. */
    entryId: string | null;
}

export interface BundleLegendSelectedDetail {
    entryId: string;
}

@customElement("bundle-legend")
export class BundleLegend extends LitElement {
    /** Bundle id whose entries are currently being shown. Display-only
     *  metadata — the host decides which bundle owns the legend at
     *  any moment (typically the active tab). */
    @state() private bundleLabel = "";
    @state() private entries: readonly BundleLegendEntry[] = [];
    @state() private activeEntryId: string | null = null;
    /** Per-bundle entries, keyed by bundle id. Lets each bundle's
     *  refresh function set its entries without coordinating with
     *  the others; `showBundle` then surfaces one bundle at a time. */
    private entriesByBundle = new Map<string, readonly BundleLegendEntry[]>();
    private labelsByBundle = new Map<string, string>();

    /**
     * Stash [entries] for [bundleId]. Does not change which bundle
     * is currently displayed; call `showBundle` to swap.
     */
    setBundleEntries(
        bundleId: string,
        bundleLabel: string,
        entries: readonly BundleLegendEntry[],
    ): void {
        this.entriesByBundle.set(bundleId, entries);
        this.labelsByBundle.set(bundleId, bundleLabel);
        // If the host happened to be showing this bundle already,
        // mirror the update without waiting for an explicit
        // `showBundle` call.
        if (this.bundleLabel === bundleLabel) {
            this.entries = entries;
        }
    }

    /**
     * Drop a bundle's entries entirely — wired to the bundle's
     * chip-off / deactivation path so dismissing the bundle clears
     * its legend slice. Hides the legend if the dropped bundle was
     * the one being shown.
     */
    clearBundle(bundleId: string): void {
        const wasShowing =
            this.labelsByBundle.get(bundleId) === this.bundleLabel;
        this.entriesByBundle.delete(bundleId);
        this.labelsByBundle.delete(bundleId);
        if (wasShowing) {
            this.bundleLabel = "";
            this.entries = [];
        }
    }

    /**
     * Switch the visible legend slice to [bundleId]. Pass `null` to
     * hide the legend entirely (no active tab, or active tab is a
     * bundle that doesn't supply legend entries). Returns the number
     * of entries now showing so the host can toggle the panel's
     * `hidden` flag without reaching into private state.
     */
    showBundle(bundleId: string | null): number {
        if (bundleId === null) {
            this.bundleLabel = "";
            this.entries = [];
            return 0;
        }
        this.bundleLabel = this.labelsByBundle.get(bundleId) ?? "";
        const next = this.entriesByBundle.get(bundleId) ?? [];
        this.entries = next;
        return next.length;
    }

    /**
     * Drive the highlight from an external source — e.g. the user
     * hovers an overlay box on the preview and the host calls this
     * to mirror the highlight on the legend side.
     */
    setActiveEntryId(id: string | null): void {
        this.activeEntryId = id;
    }

    protected createRenderRoot(): HTMLElement {
        return this;
    }

    protected render(): TemplateResult {
        if (this.entries.length === 0) {
            return html``;
        }
        return html`
            <div
                class="bundle-legend-panel"
                role="region"
                aria-label=${`${this.bundleLabel || "Bundle"} legend`}
            >
                <header class="bundle-legend-header">
                    <span>${this.bundleLabel || "Legend"}</span>
                    <span class="bundle-legend-count"
                        >${this.entries.length}</span
                    >
                </header>
                <ol class="bundle-legend-list">
                    ${this.entries.map((e) => this.renderEntry(e))}
                </ol>
            </div>
        `;
    }

    private renderEntry(e: BundleLegendEntry): TemplateResult {
        const active = e.id === this.activeEntryId;
        const applySwatchStyle = (el: Element | undefined): void => {
            // VS Code's webview CSP rejects inline `style=` attributes
            // (see #1124 BoxOverlay / ProgressBar fix). Set custom
            // properties via the CSSOM in a ref callback so the
            // swatch colour can come from the entry's own palette
            // pick rather than a fixed-set `data-level` mapping.
            if (!el) return;
            const sw = el as HTMLSpanElement;
            if (e.color) {
                sw.style.setProperty("--legend-swatch-color", e.color);
            } else {
                sw.style.removeProperty("--legend-swatch-color");
            }
        };
        return html`
            <li
                class=${
                    active
                        ? "bundle-legend-entry bundle-legend-entry-active"
                        : "bundle-legend-entry"
                }
                data-legend-id=${e.id}
                tabindex="0"
                role="button"
                @mouseenter=${() => this.onHover(e.id)}
                @mouseleave=${() => this.onHover(null)}
                @focus=${() => this.onHover(e.id)}
                @blur=${() => this.onHover(null)}
                @click=${() => this.onSelect(e.id)}
                @keydown=${(evt: KeyboardEvent) => this.onKeydown(evt, e.id)}
            >
                <span
                    ${ref(applySwatchStyle)}
                    class="bundle-legend-swatch"
                    data-level=${e.level}
                    aria-hidden="true"
                ></span>
                <div class="bundle-legend-text">
                    <strong class="bundle-legend-label">${e.label}</strong>
                    ${
                        e.detail
                            ? html`<span class="bundle-legend-detail"
                                  >${e.detail}</span
                              >`
                            : ""
                    }
                </div>
            </li>
        `;
    }

    private onHover(id: string | null): void {
        this.activeEntryId = id;
        this.dispatchEvent(
            new CustomEvent<BundleLegendHoveredDetail>("legend-hovered", {
                detail: { entryId: id },
                bubbles: true,
                composed: true,
            }),
        );
    }

    private onSelect(id: string): void {
        this.dispatchEvent(
            new CustomEvent<BundleLegendSelectedDetail>("legend-selected", {
                detail: { entryId: id },
                bubbles: true,
                composed: true,
            }),
        );
    }

    private onKeydown(evt: KeyboardEvent, id: string): void {
        if (evt.key === "Enter" || evt.key === " ") {
            evt.preventDefault();
            this.onSelect(id);
        }
    }
}
