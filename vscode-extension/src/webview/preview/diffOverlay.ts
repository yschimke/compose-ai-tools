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

import type { VsCodeApi } from "../shared/vscode";

export type DiffMode = "side" | "overlay" | "onion";

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

function buildDiffModeBar(
    initialMode: DiffMode,
    onChange: (mode: DiffMode) => void,
): HTMLElement {
    const bar = document.createElement("div");
    bar.className = "diff-mode-bar";
    bar.setAttribute("role", "tablist");
    const modes: { id: DiffMode; label: string }[] = [
        { id: "side", label: "Side" },
        { id: "overlay", label: "Overlay" },
        { id: "onion", label: "Onion" },
    ];
    for (const m of modes) {
        const btn = document.createElement("button");
        btn.type = "button";
        btn.textContent = m.label;
        btn.dataset.mode = m.id;
        btn.setAttribute("role", "tab");
        btn.setAttribute(
            "aria-selected",
            m.id === initialMode ? "true" : "false",
        );
        if (m.id === initialMode) btn.classList.add("active");
        btn.addEventListener("click", () => {
            for (const b of bar.querySelectorAll<HTMLButtonElement>("button")) {
                b.classList.toggle("active", b.dataset.mode === m.id);
                b.setAttribute(
                    "aria-selected",
                    b.dataset.mode === m.id ? "true" : "false",
                );
            }
            onChange(m.id);
        });
        bar.appendChild(btn);
    }
    return bar;
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

export type DiffStats =
    | { error: string }
    | {
          sameSize: false;
          leftW: number;
          leftH: number;
          rightW: number;
          rightH: number;
      }
    | {
          sameSize: true;
          w: number;
          h: number;
          diffPx: number;
          total: number;
          percent: number;
      };

/**
 * Client-side pixel diff: load both base64 PNGs into `<img>`s, draw to
 * canvases, walk the `ImageData` buffers in parallel. Resolves to one
 * of three shapes — error, size-mismatch, or identical/changed stats.
 * Always resolves; never rejects.
 */
function computeDiffStats(
    leftBase64: string,
    rightBase64: string,
): Promise<DiffStats> {
    return new Promise((resolve) => {
        const left = new Image();
        const right = new Image();
        let loaded = 0;
        const onErr = (): void => resolve({ error: "image failed to load" });
        const onOk = (): void => {
            if (++loaded < 2) return;
            try {
                if (
                    left.naturalWidth !== right.naturalWidth ||
                    left.naturalHeight !== right.naturalHeight
                ) {
                    resolve({
                        sameSize: false,
                        leftW: left.naturalWidth,
                        leftH: left.naturalHeight,
                        rightW: right.naturalWidth,
                        rightH: right.naturalHeight,
                    });
                    return;
                }
                const w = left.naturalWidth;
                const h = left.naturalHeight;
                const c1 = document.createElement("canvas");
                c1.width = w;
                c1.height = h;
                c1.getContext("2d")!.drawImage(left, 0, 0);
                const d1 = c1.getContext("2d")!.getImageData(0, 0, w, h).data;
                const c2 = document.createElement("canvas");
                c2.width = w;
                c2.height = h;
                c2.getContext("2d")!.drawImage(right, 0, 0);
                const d2 = c2.getContext("2d")!.getImageData(0, 0, w, h).data;
                let diff = 0;
                const len = d1.length;
                for (let i = 0; i < len; i += 4) {
                    if (
                        d1[i] !== d2[i] ||
                        d1[i + 1] !== d2[i + 1] ||
                        d1[i + 2] !== d2[i + 2] ||
                        d1[i + 3] !== d2[i + 3]
                    )
                        diff++;
                }
                const total = w * h;
                resolve({
                    sameSize: true,
                    w,
                    h,
                    diffPx: diff,
                    total,
                    percent: total > 0 ? diff / total : 0,
                });
            } catch (err) {
                resolve({
                    error:
                        (err instanceof Error && err.message) ||
                        "stats unavailable",
                });
            }
        };
        left.onload = onOk;
        left.onerror = onErr;
        right.onload = onOk;
        right.onerror = onErr;
        left.src = "data:image/png;base64," + leftBase64;
        right.src = "data:image/png;base64," + rightBase64;
    });
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
