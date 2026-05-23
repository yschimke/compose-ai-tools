// `<focus-inspector>` — the slim side panel rendered below the focused
// preview card. Owns two orthogonal sub-sections:
//
//   1. **Render-error banner** — surfaces the per-card render error
//      when one is present. Driven by the `renderError` /
//      `renderErrorDetail` properties (sourced from
//      `card.dataset.renderError*`, stamped by
//      `messageHandlers.handleErrorMessage`).
//   2. **History panel** — diff buttons (HEAD / main), rendered only
//      when the History bundle chip is active. The chip is the gate;
//      the panel stays out of the DOM otherwise so the focus area
//      below the preview is empty until the user opts in.
//
// Host wiring sets the properties via `applyFocusInspectorState(el, …)`
// — a thin helper that maps a focused-card pointer to the per-property
// shape. When the card argument is `null` (or `earlyFeatures` is off)
// the element renders nothing and hides itself.

import { LitElement, html, type TemplateResult, nothing } from "lit";
import { customElement, property, state } from "lit/decorators.js";

export type FocusDiffTarget = "head" | "main";

@customElement("focus-inspector")
export class FocusInspector extends LitElement {
    @property({ attribute: false }) renderError: string | null = null;
    @property({ attribute: false }) renderErrorDetail: string | null = null;
    @property({ attribute: false }) historyActive = false;
    @property({ attribute: false }) onRequestDiff?: (
        against: FocusDiffTarget,
    ) => void;
    @state() private historyOpen = false;

    protected createRenderRoot(): HTMLElement {
        return this;
    }

    protected render(): TemplateResult {
        const showError = !!this.renderError;
        const showHistory = this.historyActive;
        if (!showError && !showHistory) {
            return html``;
        }
        return html`
            ${showError ? this.renderErrorBanner() : nothing}
            ${showHistory ? this.renderHistoryPanel() : nothing}
        `;
    }

    private renderErrorBanner(): TemplateResult {
        return html`
            <section class="focus-panel focus-error-panel" role="alert">
                <div class="focus-error-row" data-kind="local/render/error">
                    <i class="codicon codicon-error" aria-hidden="true"></i>
                    <div class="focus-error-body">
                        <div class="focus-error-title">Render failed</div>
                        <div class="focus-error-message">
                            ${this.renderError}
                        </div>
                        ${this.renderErrorDetail
                            ? html`<div class="focus-error-detail">
                                  ${this.renderErrorDetail}
                              </div>`
                            : nothing}
                    </div>
                </div>
            </section>
        `;
    }

    private renderHistoryPanel(): TemplateResult {
        return html`
            <details
                class="focus-panel focus-history-panel"
                ?open=${this.historyOpen}
                @toggle=${this.onHistoryToggle}
            >
                <summary class="focus-panel-header focus-history-summary">
                    <i class="codicon codicon-history" aria-hidden="true"></i>
                    <span>History</span>
                    <i
                        class="codicon codicon-chevron-down focus-summary-chevron"
                        aria-hidden="true"
                    ></i>
                </summary>
                <div class="focus-actions">
                    ${this.renderActionButton(
                        "git-compare",
                        "HEAD",
                        "Diff vs last archived render",
                        "head",
                    )}
                    ${this.renderActionButton(
                        "source-control",
                        "main",
                        "Diff vs latest archived main render",
                        "main",
                    )}
                </div>
            </details>
        `;
    }

    private renderActionButton(
        icon: string,
        label: string,
        title: string,
        against: FocusDiffTarget,
    ): TemplateResult {
        return html`
            <button
                type="button"
                class="focus-action"
                title=${title}
                @click=${() => this.onRequestDiff?.(against)}
            >
                <i class="codicon codicon-${icon}" aria-hidden="true"></i>
                <span>${label}</span>
            </button>
        `;
    }

    private onHistoryToggle = (ev: Event): void => {
        this.historyOpen = (ev.currentTarget as HTMLDetailsElement).open;
    };
}

/**
 * Push state from the focused card onto a `<focus-inspector>` element.
 * Pass `card = null` (or set `earlyFeatures` to false) to clear the
 * inspector and hide its host element.
 */
export function applyFocusInspectorState(
    el: FocusInspector,
    card: HTMLElement | null,
    opts: {
        earlyFeatures: boolean;
        historyActive: boolean;
    },
): void {
    const visible = !!card && opts.earlyFeatures;
    el.hidden = !visible;
    if (!visible || !card) {
        el.renderError = null;
        el.renderErrorDetail = null;
        el.historyActive = false;
        return;
    }
    el.renderError = card.dataset.renderError ?? null;
    el.renderErrorDetail = card.dataset.renderErrorDetail ?? null;
    el.historyActive = opts.historyActive;
}

declare global {
    interface HTMLElementTagNameMap {
        "focus-inspector": FocusInspector;
    }
}
