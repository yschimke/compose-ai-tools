// `<scope-chip>` — replaces the imperative scope chip in the History
// panel (was `<div id="scope-chip">` with `scopeChipEl.hidden` and
// `scopeChipLabelEl.textContent` writes from `behavior.ts`).
//
// Listens for `setScopeLabel` directly: when `label` is non-empty,
// shows the chip with that text; when null/empty, hides it.
//
// Title attribute matches the previous markup: explains why entries are
// narrowed and how to widen the scope.

import { LitElement, html, nothing, type TemplateResult } from "lit";
import { customElement, state } from "lit/decorators.js";

interface SetScopeLabelMessage {
    command: "setScopeLabel";
    label?: string | null;
}

type IncomingMessage = SetScopeLabelMessage | { command: string };

@customElement("scope-chip")
export class ScopeChip extends LitElement {
    @state() private label: string | null = null;

    private readonly onMessage = (
        event: MessageEvent<IncomingMessage>,
    ): void => {
        const msg = event.data;
        if (msg?.command === "setScopeLabel") {
            const m = msg as SetScopeLabelMessage;
            this.label = m.label && m.label.length > 0 ? m.label : null;
        }
    };

    // Light DOM so the panel-specific `.scope-chip` styles in
    // historyPanel.ts's host `<style>` block apply unchanged.
    protected createRenderRoot(): HTMLElement {
        return this;
    }

    connectedCallback(): void {
        super.connectedCallback();
        window.addEventListener("message", this.onMessage);
    }

    disconnectedCallback(): void {
        window.removeEventListener("message", this.onMessage);
        super.disconnectedCallback();
    }

    protected render(): TemplateResult {
        if (!this.label) {
            return html`${nothing}`;
        }
        return html`
            <div
                class="scope-chip"
                role="status"
                aria-live="polite"
                title="History narrowed because a single preview is selected in the live panel — change focus or filters there to widen."
            >
                <i class="codicon codicon-filter" aria-hidden="true"></i>
                <span>${this.label}</span>
            </div>
        `;
    }
}
