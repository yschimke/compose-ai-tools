// Bundled entry for the live "Compose Preview" webview panel.
//
// `<preview-app>` renders the panel skeleton via Lit's `html` template,
// then runs the imperative behaviour (filters, focus mode, carousel,
// diff overlays, interactive input, viewport tracking, message routing)
// once on `firstUpdated`. The behaviour remains a verbatim port from
// the previously-inline IIFE — see `behavior.ts`. Future commits can
// incrementally lift sub-trees (toolbar, focus controls, preview cards,
// diff overlay, focus inspector) into reactive sub-components.

import { LitElement, html, type TemplateResult } from "lit";
import { customElement } from "lit/decorators.js";
import { setupPreviewBehavior } from "./behavior";

@customElement("preview-app")
export class PreviewApp extends LitElement {
    // Render in light DOM so `media/preview.css` applies and so
    // `document.getElementById(...)` queries from `behavior.ts` resolve.
    protected createRenderRoot(): HTMLElement {
        return this;
    }

    protected render(): TemplateResult {
        return html`
            <div
                id="progress-bar"
                class="progress-bar"
                role="progressbar"
                aria-label="Refresh progress"
                aria-valuemin="0"
                aria-valuemax="100"
                aria-valuenow="0"
            >
                <div class="progress-label" id="progress-label"></div>
                <div class="progress-track">
                    <div class="progress-fill"></div>
                </div>
            </div>
            <div id="compile-errors" class="compile-errors" role="alert" hidden>
                <div class="compile-errors-header">
                    <i class="codicon codicon-error" aria-hidden="true"></i>
                    <span id="compile-errors-title">Compile errors</span>
                </div>
                <div id="compile-errors-list" class="compile-errors-list"></div>
                <div class="compile-errors-footnote">
                    Showing last successful render.
                </div>
            </div>
            <div
                class="toolbar"
                id="toolbar"
                role="toolbar"
                aria-label="Preview filters"
            >
                <div class="select-wrapper">
                    <select
                        id="filter-function"
                        title="Filter by function"
                        aria-label="Function filter"
                    >
                        <option value="all">All functions</option>
                    </select>
                    <i
                        class="codicon codicon-chevron-down select-chevron"
                        aria-hidden="true"
                    ></i>
                </div>
                <div class="select-wrapper">
                    <select
                        id="filter-group"
                        title="Filter by @Preview group"
                        aria-label="Group filter"
                    >
                        <option value="all">All groups</option>
                    </select>
                    <i
                        class="codicon codicon-chevron-down select-chevron"
                        aria-hidden="true"
                    ></i>
                </div>
                <div class="select-wrapper">
                    <select
                        id="layout-mode"
                        title="Layout"
                        aria-label="Layout mode"
                    >
                        <option value="grid">Grid</option>
                        <option value="flow">Flow</option>
                        <option value="column">Column</option>
                        <option value="focus">Focus</option>
                    </select>
                    <i
                        class="codicon codicon-chevron-down select-chevron"
                        aria-hidden="true"
                    ></i>
                </div>
            </div>

            <div
                id="message"
                class="message"
                role="status"
                aria-live="polite"
            ></div>
            <div id="focus-controls" class="focus-controls" hidden>
                <button
                    class="icon-button"
                    id="btn-prev"
                    title="Previous preview"
                    aria-label="Previous preview"
                >
                    <i
                        class="codicon codicon-arrow-left"
                        aria-hidden="true"
                    ></i>
                </button>
                <span id="focus-position" aria-live="polite"></span>
                <button
                    class="icon-button"
                    id="btn-next"
                    title="Next preview"
                    aria-label="Next preview"
                >
                    <i
                        class="codicon codicon-arrow-right"
                        aria-hidden="true"
                    ></i>
                </button>
                <button
                    class="icon-button"
                    id="btn-diff-head"
                    title="Diff vs last archived render (HEAD)"
                    aria-label="Diff vs HEAD"
                >
                    <i
                        class="codicon codicon-git-compare"
                        aria-hidden="true"
                    ></i>
                </button>
                <button
                    class="icon-button"
                    id="btn-diff-main"
                    title="Diff vs the latest render archived on main"
                    aria-label="Diff vs main"
                >
                    <i
                        class="codicon codicon-source-control"
                        aria-hidden="true"
                    ></i>
                </button>
                <button
                    class="icon-button"
                    id="btn-launch-device"
                    title="Launch on connected Android device"
                    aria-label="Launch on device"
                >
                    <i
                        class="codicon codicon-device-mobile"
                        aria-hidden="true"
                    ></i>
                </button>
                <button
                    class="icon-button"
                    id="btn-a11y-overlay"
                    title="Show accessibility overlay"
                    aria-label="Toggle accessibility overlay"
                    aria-pressed="false"
                >
                    <i class="codicon codicon-eye" aria-hidden="true"></i>
                </button>
                <button
                    class="icon-button"
                    id="btn-interactive"
                    title="Daemon not ready — live mode unavailable"
                    aria-label="Toggle live (interactive) mode"
                    aria-pressed="false"
                    disabled
                    hidden
                >
                    <i
                        class="codicon codicon-circle-large-outline"
                        aria-hidden="true"
                    ></i>
                </button>
                <button
                    class="icon-button"
                    id="btn-stop-interactive"
                    title="Stop live preview"
                    aria-label="Stop live preview"
                    hidden
                >
                    <i
                        class="codicon codicon-debug-stop"
                        aria-hidden="true"
                    ></i>
                </button>
                <button
                    class="icon-button"
                    id="btn-recording"
                    title="Record focused preview"
                    aria-label="Record focused preview"
                    aria-pressed="false"
                    disabled
                    hidden
                >
                    <i
                        class="codicon codicon-record-keys"
                        aria-hidden="true"
                    ></i>
                </button>
                <select
                    id="recording-format"
                    title="Recording format"
                    aria-label="Recording format"
                    hidden
                >
                    <option value="apng">APNG</option>
                    <option value="mp4">MP4</option>
                </select>
                <button
                    class="icon-button"
                    id="btn-exit-focus"
                    title="Exit focus mode"
                    aria-label="Exit focus mode"
                >
                    <i class="codicon codicon-close" aria-hidden="true"></i>
                </button>
            </div>
            <div
                id="preview-grid"
                class="preview-grid"
                role="list"
                aria-label="Preview cards"
            ></div>
            <div
                id="focus-inspector"
                class="focus-inspector"
                hidden
                aria-label="Focused preview data"
            ></div>
        `;
    }

    protected firstUpdated(): void {
        const initialEarlyFeatures = this.dataset.earlyFeatures === "true";
        setupPreviewBehavior(initialEarlyFeatures);
    }
}
