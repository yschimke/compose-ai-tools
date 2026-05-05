// Per-card render-error panel.
//
// Lifted verbatim from `behavior.ts`'s `buildErrorPanel`. Returns a
// detached DOM element so callers (currently `behavior.ts`'s `showFrame`
// / per-capture error handling) can `appendChild` it to the appropriate
// container. Once `<preview-card>` lands as a Lit element this file's
// shape will likely flip into a `TemplateResult`-returning helper or a
// `<render-error-panel>` Lit child — the structure is already
// self-contained, so the conversion is mechanical.
//
// When `renderError` is non-null the panel has structured detail:
// exception class, message, a clickable `file:line` frame, and a
// collapsible stack trace. Otherwise it falls back to the plain
// one-line message (the case for renderers that don't yet produce a
// sidecar, or when the sidecar was unreadable).
//
// Click on the frame button posts an `openSourceFile` message; the
// extension resolves the stack-trace basename to an absolute path via
// `workspace.findFiles`. The disclosure for the full trace is a
// native `<details>` — no JS state to manage, the browser handles the
// toggle.

import type { PreviewRenderError } from "../shared/types";
import type { VsCodeApi } from "../shared/vscode";

export function buildErrorPanel(
    vscode: VsCodeApi<unknown>,
    message: string | null | undefined,
    renderError: PreviewRenderError | null | undefined,
    className: string | null | undefined,
): HTMLElement {
    const panel = document.createElement("div");
    panel.className = "error-message render-error";
    panel.setAttribute("role", "alert");
    if (!renderError) {
        panel.textContent = message || "";
        return panel;
    }
    const cls =
        (renderError.exception || "").split(".").pop() ||
        renderError.exception ||
        "Error";
    const head = document.createElement("div");
    head.className = "render-error-class";
    head.textContent = cls;
    panel.appendChild(head);

    if (renderError.message) {
        const msg = document.createElement("div");
        msg.className = "render-error-msg";
        msg.textContent = renderError.message;
        panel.appendChild(msg);
    }

    const frame = renderError.topAppFrame;
    if (frame && frame.file) {
        const link = document.createElement("button");
        link.className = "render-error-frame";
        link.type = "button";
        const fnSuffix = frame.function ? " in " + frame.function : "";
        const lineSuffix = frame.line > 0 ? ":" + frame.line : "";
        link.textContent = frame.file + lineSuffix + fnSuffix;
        link.title = "Open " + frame.file + lineSuffix;
        link.addEventListener("click", () => {
            vscode.postMessage({
                command: "openSourceFile",
                fileName: frame.file,
                line: frame.line,
                // className lets the extension disambiguate same-named
                // files across modules — when the throw is in this
                // preview's own file, the class-derived path matches
                // and we pick the right one without a workspace-wide
                // first-hit guess.
                className: className || undefined,
            });
        });
        panel.appendChild(link);
    }

    if (renderError.stackTrace) {
        const details = document.createElement("details");
        details.className = "render-error-stack";
        const summary = document.createElement("summary");
        summary.textContent = "Stack trace";
        details.appendChild(summary);
        const pre = document.createElement("pre");
        pre.textContent = renderError.stackTrace;
        details.appendChild(pre);
        panel.appendChild(details);
    }
    return panel;
}
