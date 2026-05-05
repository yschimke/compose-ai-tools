// `<compile-errors-banner>` — replaces the imperative compile-error
// banner that used to live in `behavior.ts` (`setCompileErrors` /
// `clearCompileErrors`).
//
// Subscribes to `setCompileErrors` and `clearCompileErrors` messages on
// the window directly so the banner is independent of the rest of the
// preview-app behaviour. While the banner is showing, it adds the
// `compile-stale` class to `#preview-grid` so cards dim alongside the
// banner — matches the previous behaviour exactly.
//
// Posts `openCompileError` on row click; the extension opens the file
// at `path:line:column`.

import { LitElement, html, nothing, type TemplateResult } from "lit";
import { customElement, state } from "lit/decorators.js";
import { repeat } from "lit/directives/repeat.js";
import type { CompileError } from "../../shared/types";
import { getVsCodeApi } from "../../shared/vscode";
import { PreviewGrid } from "./PreviewGrid";

interface SetCompileErrorsMessage {
    command: "setCompileErrors";
    errors: CompileError[];
}

interface ClearCompileErrorsMessage {
    command: "clearCompileErrors";
}

type IncomingMessage =
    | SetCompileErrorsMessage
    | ClearCompileErrorsMessage
    | { command: string };

@customElement("compile-errors-banner")
export class CompileErrorsBanner extends LitElement {
    @state() private errors: CompileError[] = [];

    private readonly onMessage = (
        event: MessageEvent<IncomingMessage>,
    ): void => {
        const msg = event.data;
        if (!msg) return;
        if (msg.command === "setCompileErrors") {
            this.errors = (msg as SetCompileErrorsMessage).errors ?? [];
        } else if (msg.command === "clearCompileErrors") {
            this.errors = [];
        }
    };

    // Light DOM so the existing `media/preview.css` rules
    // (`.compile-errors`, `.compile-error-row`, etc.) apply unchanged.
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

    protected updated(): void {
        // Cross-component side effect: dim the preview grid while the
        // banner is showing. The grid owns its own class list (incl. the
        // layout-X modifier), so we hand it the desired flag rather than
        // toggling the class directly — matches the imperative
        // `setCompileErrors` / `clearCompileErrors` behaviour but goes
        // through the typed component API.
        const grid = document.querySelector("preview-grid");
        if (grid instanceof PreviewGrid) {
            grid.setCompileStale(this.errors.length > 0);
        }
    }

    protected render(): TemplateResult {
        if (this.errors.length === 0) {
            // `nothing` collapses the element to empty children but the
            // host element stays in the tree; matches the previous
            // `[hidden]` behaviour without needing the attribute.
            return html`${nothing}`;
        }
        const count = this.errors.length;
        const title =
            count === 1 ? "1 compile error" : `${count} compile errors`;
        return html`
            <div class="compile-errors" role="alert">
                <div class="compile-errors-header">
                    <i class="codicon codicon-error" aria-hidden="true"></i>
                    <span class="compile-errors-title">${title}</span>
                </div>
                <div class="compile-errors-list">
                    ${repeat(
                        this.errors,
                        (e) => `${e.path}:${e.line}:${e.column}`,
                        (e) => this.renderRow(e),
                    )}
                </div>
                <div class="compile-errors-footnote">
                    Showing last successful render.
                </div>
            </div>
        `;
    }

    private renderRow(e: CompileError): TemplateResult {
        const loc = `${e.file}:${e.line}:${e.column}`;
        return html`
            <button
                type="button"
                class="compile-error-row"
                title=${`Open ${loc}`}
                @click=${() => this.openError(e)}
            >
                <span class="compile-error-loc">${loc}</span>
                <span class="compile-error-msg">${e.message}</span>
            </button>
        `;
    }

    private openError(e: CompileError): void {
        getVsCodeApi().postMessage({
            command: "openCompileError",
            sourceFile: e.path,
            line: e.line,
            column: e.column,
        });
    }
}
