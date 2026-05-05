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
import { getVsCodeApi } from "../shared/vscode";
import "./components/CompileErrorsBanner";
import "./components/FilterToolbar";
import "./components/MessageBanner";
import "./components/PreviewGrid";
import "./components/ProgressBar";

@customElement("preview-app")
export class PreviewApp extends LitElement {
    // Render in light DOM so `media/preview.css` applies and so
    // `document.getElementById(...)` queries from `behavior.ts` resolve.
    protected createRenderRoot(): HTMLElement {
        return this;
    }

    protected render(): TemplateResult {
        return html`
            <progress-bar></progress-bar>
            <compile-errors-banner></compile-errors-banner>
            <filter-toolbar></filter-toolbar>

            <message-banner></message-banner>
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
            <preview-grid
                id="preview-grid"
                role="list"
                aria-label="Preview cards"
            ></preview-grid>
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
        // Tell the extension we exist. The host posts `setPreviews` /
        // `setModules` / etc. as soon as it has data — but `postMessage`
        // silently drops messages while the webview view is unresolved
        // (panel hidden when the extension activated on `onLanguage:kotlin`).
        // Replying to this signal is the host's cue to republish the latest
        // stateful messages so the grid isn't permanently empty.
        getVsCodeApi().postMessage({ command: "webviewReady" });
    }
}
