// `<data-tabs>` — tab row that appears below `<bundle-chip-bar>`.
// Each active bundle gets a tab with a label and a close `×`
// affordance; the `×` and the bundle chip's re-press are two redundant
// ways to dismiss (design doc § "Chip ↔ tab ↔ overlay state machine").
//
// The active tab's body is provided by the host via `setTabBody(id, el)`
// so each bundle can ship its own table/tree presenter without the
// shell needing to know. Tab bodies are kept mounted across switches
// so a slow-to-build presenter doesn't tear down on every click; only
// the active body is `[hidden]=false`.
//
// A trailing **`…More`** tab is always rendered in the last position,
// even when no bundle is active. It's the only non-closable tab and
// lists every currently-disabled bundle as a clickable row that emits
// `bundle-toggled` — re-using the chip-bar event so the host doesn't
// need new wiring (design doc § "UX shell", item 8: "Last tab is always
// `…More` / settings. Stable position so the user always knows where
// to find the extra filters and disabled kinds.").

import { LitElement, html, type TemplateResult } from "lit";
import { customElement, state } from "lit/decorators.js";
import type { BundleDescriptor, BundleId } from "../bundleRegistry";

export interface TabCloseDetail {
    id: BundleId;
}

export interface TabSelectDetail {
    /** `null` selects the `…More` tab (no bundle owns it). */
    id: BundleId | null;
}

/** Re-export of the chip-bar event's detail shape — the `…More` rows
 *  fire the same event so the host's existing `bundle-toggled` listener
 *  handles activation without new wiring. */
export interface BundleToggledDetail {
    id: BundleId;
}

/** Sentinel value for the `…More` tab's selection state. The host
 *  controller's `activeTab` is a `BundleId | null`; `null` is the
 *  `…More` tab. */
export const MORE_TAB = null;

@customElement("data-tabs")
export class DataTabs extends LitElement {
    @state() private bundles: readonly BundleDescriptor[] = [];
    @state() private activeBundles: readonly BundleId[] = [];
    @state() private activeTab: BundleId | null = null;
    /** Per-bundle body elements supplied by the host. */
    private bodies = new Map<BundleId, HTMLElement>();

    setState(snapshot: {
        bundles: readonly BundleDescriptor[];
        activeBundles: readonly BundleId[];
        activeTab: BundleId | null;
    }): void {
        this.bundles = snapshot.bundles;
        this.activeBundles = snapshot.activeBundles;
        this.activeTab = snapshot.activeTab;
    }

    /**
     * Register a body element for [id]. Re-registering replaces the
     * prior element. Passing `null` detaches.
     */
    setTabBody(id: BundleId, body: HTMLElement | null): void {
        const existing = this.bodies.get(id);
        if (existing && existing !== body) {
            existing.remove();
        }
        if (body) {
            this.bodies.set(id, body);
        } else {
            this.bodies.delete(id);
        }
        this.requestUpdate();
    }

    /** Visible for tests. */
    getActiveTab(): BundleId | null {
        return this.activeTab;
    }

    protected createRenderRoot(): HTMLElement {
        return this;
    }

    protected render(): TemplateResult {
        const ordered = this.bundles.filter((b) =>
            this.activeBundles.includes(b.id),
        );
        const moreSelected =
            this.activeTab === null ||
            !this.activeBundles.includes(this.activeTab);
        const disabled = this.bundles.filter(
            (b) => !this.activeBundles.includes(b.id),
        );
        return html`
            <div
                class="data-tabs"
                role="tablist"
                aria-label="Data extension tabs"
            >
                <div class="data-tab-strip">
                    ${ordered.map((b) => this.renderTabHandle(b))}
                    ${this.renderMoreHandle(moreSelected)}
                </div>
                <div class="data-tab-bodies">
                    ${ordered.map((b) => this.renderTabBody(b))}
                    ${this.renderMoreBody(disabled, moreSelected)}
                </div>
            </div>
        `;
    }

    private renderTabHandle(b: BundleDescriptor): TemplateResult {
        const selected = b.id === this.activeTab;
        return html`
            <div
                class=${selected
                    ? "data-tab-handle data-tab-handle-active"
                    : "data-tab-handle"}
                role="tab"
                aria-selected=${selected ? "true" : "false"}
                data-bundle=${b.id}
            >
                <button
                    type="button"
                    class="data-tab-label"
                    @click=${() => this.onSelect(b.id)}
                >
                    <i
                        class=${"codicon codicon-" + b.icon}
                        aria-hidden="true"
                    ></i>
                    <span>${b.label}</span>
                </button>
                <button
                    type="button"
                    class="data-tab-close"
                    aria-label=${`Close ${b.label} tab`}
                    title=${`Close ${b.label}`}
                    @click=${(e: Event) => this.onClose(e, b.id)}
                >
                    <i class="codicon codicon-close" aria-hidden="true"></i>
                </button>
            </div>
        `;
    }

    private renderMoreHandle(selected: boolean): TemplateResult {
        // No close button — `…More` is the stable trailing tab; the
        // design doc makes it the only non-closable tab so the user
        // always has somewhere to find disabled bundles.
        return html`
            <div
                class=${selected
                    ? "data-tab-handle data-tab-handle-more data-tab-handle-active"
                    : "data-tab-handle data-tab-handle-more"}
                role="tab"
                aria-selected=${selected ? "true" : "false"}
                data-bundle="__more"
            >
                <button
                    type="button"
                    class="data-tab-label"
                    @click=${() => this.onSelect(null)}
                    title="Show disabled bundles"
                >
                    <i class="codicon codicon-ellipsis" aria-hidden="true"></i>
                    <span>More</span>
                </button>
            </div>
        `;
    }

    private renderTabBody(b: BundleDescriptor): TemplateResult {
        const body = this.bodies.get(b.id);
        const selected = b.id === this.activeTab;
        return html`
            <div
                class="data-tab-body"
                role="tabpanel"
                data-bundle=${b.id}
                ?hidden=${!selected}
            >
                ${body ?? this.placeholderBody(b)}
            </div>
        `;
    }

    private renderMoreBody(
        disabled: readonly BundleDescriptor[],
        selected: boolean,
    ): TemplateResult {
        return html`
            <div
                class="data-tab-body data-tab-body-more"
                role="tabpanel"
                data-bundle="__more"
                ?hidden=${!selected}
            >
                <div class="data-tab-more-header">Disabled bundles</div>
                ${disabled.length === 0
                    ? html`
                          <div class="data-table-empty">Everything's on.</div>
                      `
                    : html`
                          <div class="data-tab-more-list">
                              ${disabled.map((b) => this.renderMoreRow(b))}
                          </div>
                      `}
            </div>
        `;
    }

    private renderMoreRow(b: BundleDescriptor): TemplateResult {
        return html`
            <button
                type="button"
                class="data-tab-more-row"
                data-bundle=${b.id}
                title=${`Enable ${b.label}`}
                aria-label=${`Enable ${b.label}`}
                @click=${() => this.onMoreActivate(b.id)}
            >
                <i class=${"codicon codicon-" + b.icon} aria-hidden="true"></i>
                <span class="data-tab-more-row-label">${b.label}</span>
                <span class="data-tab-more-row-hint">click to enable</span>
            </button>
        `;
    }

    private placeholderBody(b: BundleDescriptor): TemplateResult {
        return html`
            <div class="data-tab-placeholder">${b.label} is loading…</div>
        `;
    }

    private onSelect(id: BundleId | null): void {
        this.dispatchEvent(
            new CustomEvent<TabSelectDetail>("tab-selected", {
                detail: { id },
                bubbles: true,
                composed: true,
            }),
        );
    }

    private onClose(evt: Event, id: BundleId): void {
        evt.stopPropagation();
        this.dispatchEvent(
            new CustomEvent<TabCloseDetail>("tab-closed", {
                detail: { id },
                bubbles: true,
                composed: true,
            }),
        );
    }

    private onMoreActivate(id: BundleId): void {
        // Re-use the chip-bar's `bundle-toggled` event so the host's
        // existing controller wiring activates the bundle. Once the
        // controller fires `bundle-state-changed`, `setState` re-renders
        // and the bundle's own tab takes over.
        this.dispatchEvent(
            new CustomEvent<BundleToggledDetail>("bundle-toggled", {
                detail: { id },
                bubbles: true,
                composed: true,
            }),
        );
    }
}
