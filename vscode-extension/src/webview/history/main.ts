// Bundled entry for the read-only "Preview History" webview panel.
//
// `<history-app>` renders the panel skeleton via Lit's `html` template,
// then runs the imperative behaviour (timeline rendering, filters,
// diff overlays, message routing) once on `firstUpdated`. The behaviour
// remains a verbatim port from the previously-inline IIFE — see
// `behavior.ts`. Future commits can incrementally lift sub-trees
// (timeline rows, diff overlay, scope chip) into reactive
// sub-components.

import { LitElement, html, type TemplateResult } from "lit";
import { customElement } from "lit/decorators.js";
import { setupHistoryBehavior } from "./behavior";
import "./components/ScopeChip";

@customElement("history-app")
export class HistoryApp extends LitElement {
    // Render in light DOM so `media/preview.css` and the host HTML's
    // `<style>` block apply, and so `document.getElementById(...)`
    // queries from `behavior.ts` resolve.
    protected createRenderRoot(): HTMLElement {
        return this;
    }

    protected render(): TemplateResult {
        return html`
            <div class="toolbar" role="toolbar" aria-label="History filters">
                <select id="filter-source" title="Source">
                    <option value="all">All sources</option>
                    <option value="fs">Local filesystem</option>
                    <option value="git">Git ref</option>
                </select>
                <select id="filter-branch" title="Branch">
                    <option value="all">All branches</option>
                </select>
                <button id="btn-refresh" title="Refresh">⟳</button>
                <button
                    id="btn-diff"
                    disabled
                    title="Pixel-diff two selected entries (metadata mode in current daemon)"
                >
                    Diff selected
                </button>
            </div>
            <scope-chip></scope-chip>
            <div id="message" class="message">Loading…</div>
            <div
                id="timeline"
                class="timeline"
                role="list"
                aria-label="History entries"
            ></div>
        `;
    }

    protected firstUpdated(): void {
        setupHistoryBehavior();
    }
}
