// Bundled entry for the read-only "Preview History" webview panel.
//
// Phase 1 (this commit) only registers the custom element; the existing
// inline HTML+JS in `src/historyPanel.ts` still drives the panel. Phase 2
// will lift that markup and behaviour into this component.

import { LitElement, html, type TemplateResult } from "lit";
import { customElement } from "lit/decorators.js";

@customElement("history-app")
export class HistoryApp extends LitElement {
    // Render in light DOM so the existing `media/preview.css` and the
    // `document.getElementById(...)` queries in the panel script keep
    // working without per-element selector changes.
    protected createRenderRoot(): HTMLElement {
        return this;
    }

    protected render(): TemplateResult {
        return html`<slot></slot>`;
    }
}
