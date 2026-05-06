// Per-card diff overlay (Side / Overlay / Onion modes).
//
// Lifted verbatim from `behavior.ts`'s `showDiffOverlay` / `buildDiffModeBar`
// / `renderPreviewDiffMode` / `buildDiffStack` / `buildPreviewDiffPane` /
// `computeDiffStats` / `applyDiffStats` cluster — same DOM shape, same
// CSS classes, same client-side pixel diff. The host hands in a config
// bundle so the module doesn't have to reach into the closed-over
// `state` / `vscode.setState` plumbing in `behavior.ts`.
//
// The overlay is a self-contained DOM subtree appended to the card's
// `.image-container`. Re-invoking `showDiffOverlay` on the same card
// removes any prior overlay first, so the message handlers in
// `behavior.ts` (`previewDiffReady` → payload, `previewDiffError` →
// errorMessage, the loading sentinel `payload=null && errorMessage=
// null`) can stay shape-identical to before.
//
// `computeDiffStats` is async — it loads both base64 PNGs into hidden
// `<img>`s, draws each to a canvas, and walks the `ImageData` buffers
// in parallel. Cheap on the typical preview size (< 0.5 megapixel);
// GIFs / very large captures will be slower but still bounded. A
// daemon-side pixel mode (with SSIM, etc.) can plug in here later via
// a different code path.

import { buildDiffModeBar, type DiffMode } from "../shared/diffModeBar";
import { computeDiffStats, type DiffStats } from "../shared/pixelDiff";
import type { VsCodeApi } from "../shared/vscode";

export type { DiffMode, DiffStats };

export interface DiffPayload {
    leftLabel: string;
    leftImage: string; // base64 PNG, no `data:` prefix
    rightLabel: string;
    rightImage: string;
}

export interface DiffOverlayConfig {
    vscode: VsCodeApi<unknown>;
    /** Read the persisted diff mode (Side/Overlay/Onion). Sticks across
     *  diff requests within the same session via `vscode.setState`. */
    getDiffMode(): DiffMode;
    /** Persist the diff mode after the user picks a different tab. */
    setDiffMode(mode: DiffMode): void;
}

/**
 * Show the diff overlay for [card]. Three call shapes:
 *
 *  - `payload != null, errorMessage == null` — render the diff body.
 *  - `payload == null, errorMessage != null` — render the error
 *    placeholder (`previewDiffError` message path).
 *  - `payload == null, errorMessage == null` — loading sentinel
 *    (`previewDiffReady` not yet received, but the user just clicked
 *    a diff button — show "Loading diff…" until the bytes arrive).
 *
 * Removes any previously-attached overlay on the same card before
 * appending — re-invocation idempotently swaps content.
 */
export function showDiffOverlay(
    card: HTMLElement,
    against: string,
    payload: DiffPayload | null,
    errorMessage: string | null,
    config: DiffOverlayConfig,
): void {
    const container = card.querySelector(".image-container");
    if (!container) return;
    const existing = container.querySelector(".preview-diff-overlay");
    if (existing) existing.remove();
    const overlay = document.createElement("div");
    overlay.className = "preview-diff-overlay";
    overlay.dataset.against = against;
    const close = document.createElement("button");
    close.className = "icon-button preview-diff-close";
    close.title = "Exit diff";
    close.setAttribute("aria-label", "Exit diff");
    close.innerHTML =
        '<i class="codicon codicon-close" aria-hidden="true"></i>';
    close.addEventListener("click", () => overlay.remove());
    overlay.appendChild(close);
    if (errorMessage) {
        const err = document.createElement("div");
        err.className = "preview-diff-error";
        err.textContent = errorMessage;
        overlay.appendChild(err);
        container.appendChild(overlay);
        return;
    }
    if (!payload) {
        const loading = document.createElement("div");
        loading.className = "preview-diff-loading";
        loading.textContent = "Loading diff…";
        overlay.appendChild(loading);
        container.appendChild(overlay);
        return;
    }
    const initialMode = config.getDiffMode();
    const header = document.createElement("div");
    header.className = "diff-header";
    const body = document.createElement("div");
    body.className = "preview-diff-body";
    const modeBar = buildDiffModeBar(initialMode, (mode) => {
        config.setDiffMode(mode);
        renderPreviewDiffMode(body, mode, payload);
    });
    const stats = document.createElement("div");
    stats.className = "diff-stats";
    stats.textContent = "computing…";
    header.appendChild(modeBar);
    header.appendChild(stats);
    overlay.appendChild(header);
    overlay.appendChild(body);
    container.appendChild(overlay);
    renderPreviewDiffMode(body, initialMode, payload);
    computeDiffStats(payload.leftImage, payload.rightImage).then((s) => {
        applyDiffStats(stats, s);
    });
}

function renderPreviewDiffMode(
    body: HTMLElement,
    mode: DiffMode,
    payload: DiffPayload,
): void {
    body.innerHTML = "";
    if (mode === "side") {
        const grid = document.createElement("div");
        grid.className = "preview-diff-grid";
        grid.appendChild(
            buildPreviewDiffPane(payload.leftLabel, payload.leftImage),
        );
        grid.appendChild(
            buildPreviewDiffPane(payload.rightLabel, payload.rightImage),
        );
        body.appendChild(grid);
        return;
    }
    body.appendChild(buildDiffStack(mode, payload));
}

function buildDiffStack(
    mode: Exclude<DiffMode, "side">,
    payload: DiffPayload,
): HTMLElement {
    const wrapper = document.createElement("div");
    wrapper.className = "preview-diff-stack-wrapper";
    const stack = document.createElement("div");
    stack.className = "diff-stack";
    stack.dataset.mode = mode;
    const base = document.createElement("img");
    base.className = "diff-stack-base";
    base.alt = payload.leftLabel;
    base.src = "data:image/png;base64," + payload.leftImage;
    const top = document.createElement("img");
    top.className = "diff-stack-top";
    top.alt = payload.rightLabel;
    top.src = "data:image/png;base64," + payload.rightImage;
    stack.appendChild(base);
    stack.appendChild(top);
    wrapper.appendChild(stack);
    if (mode === "onion") {
        const slider = document.createElement("input");
        slider.type = "range";
        slider.min = "0";
        slider.max = "100";
        slider.value = "50";
        slider.className = "diff-stack-onion-slider";
        slider.setAttribute(
            "aria-label",
            "Onion-skin mix between " +
                payload.leftLabel +
                " and " +
                payload.rightLabel,
        );
        stack.style.setProperty("--diff-onion-mix", "0.5");
        slider.addEventListener("input", () => {
            stack.style.setProperty(
                "--diff-onion-mix",
                (Number(slider.value) / 100).toString(),
            );
        });
        wrapper.appendChild(slider);
    }
    const cap = document.createElement("div");
    cap.className = "diff-stack-caption";
    cap.textContent = payload.leftLabel + "  ◄  " + payload.rightLabel;
    wrapper.appendChild(cap);
    return wrapper;
}

function buildPreviewDiffPane(
    label: string,
    imageData: string | null,
): HTMLElement {
    const pane = document.createElement("div");
    pane.className = "preview-diff-pane";
    const cap = document.createElement("div");
    cap.className = "preview-diff-pane-label";
    cap.textContent = label;
    pane.appendChild(cap);
    if (imageData) {
        const img = document.createElement("img");
        img.src = "data:image/png;base64," + imageData;
        img.alt = label;
        pane.appendChild(img);
    } else {
        const empty = document.createElement("div");
        empty.className = "preview-diff-pane-empty";
        empty.textContent = "(no image)";
        pane.appendChild(empty);
    }
    return pane;
}

function applyDiffStats(el: HTMLElement, s: DiffStats): void {
    if ("error" in s) {
        el.textContent = s.error;
        el.removeAttribute("data-state");
        return;
    }
    if (!s.sameSize) {
        el.textContent =
            "sizes differ — " +
            s.leftW +
            "×" +
            s.leftH +
            " vs " +
            s.rightW +
            "×" +
            s.rightH;
        el.dataset.state = "size-mismatch";
        return;
    }
    if (s.diffPx === 0) {
        el.textContent = "identical · " + s.w + "×" + s.h;
        el.dataset.state = "identical";
        return;
    }
    const p = s.percent * 100;
    const pct = p < 0.01 ? p.toFixed(3) : p.toFixed(2);
    el.textContent =
        s.diffPx.toLocaleString() + " px (" + pct + "%) · " + s.w + "×" + s.h;
    el.dataset.state = "changed";
}
