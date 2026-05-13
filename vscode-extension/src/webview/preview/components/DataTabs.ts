// `<data-tabs>` — tab row that appears below `<bundle-chip-bar>` when
// at least one bundle is active. Each tab has a label and a close `×`
// affordance; the `×` and the bundle chip's re-press are two redundant
// ways to dismiss (design doc § "Chip ↔ tab ↔ overlay state machine").
//
// The active tab's body is provided by the host via `setTabBody(id, el)`
// so each bundle can ship its own table/tree presenter without the
// shell needing to know. Tab bodies are kept mounted across switches
// so a slow-to-build presenter doesn't tear down on every click; only
// the active body is `[hidden]=false`.
//
// When `activeBundles` is empty the component renders nothing — the
// preview panel returns to its plain-preview resting state.

import { LitElement, html, nothing, type TemplateResult } from "lit";
import { customElement, state } from "lit/decorators.js";
import type { BundleDescriptor, BundleId } from "../bundleRegistry";

export interface TabCloseDetail {
    id: BundleId;
}

export interface TabSelectDetail {
    id: BundleId;
}

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
        if (this.activeBundles.length === 0) {
            return html`${nothing}`;
        }
        const ordered = this.bundles.filter((b) =>
            this.activeBundles.includes(b.id),
        );
        return html`
            <div
                class="data-tabs"
                role="tablist"
                aria-label="Data extension tabs"
            >
                <div class="data-tab-strip">
                    ${ordered.map((b) => this.renderTabHandle(b))}
                </div>
                <div class="data-tab-bodies">
                    ${ordered.map((b) => this.renderTabBody(b))}
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

    private placeholderBody(b: BundleDescriptor): TemplateResult {
        return html`
            <div class="data-tab-placeholder">${b.label} is loading…</div>
        `;
    }

    private onSelect(id: BundleId): void {
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
}
