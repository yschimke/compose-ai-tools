// Diff sub-views for the History panel — the inline `.expanded.diff-expanded`
// surface that opens when a user clicks "Diff vs previous" / "Diff vs current"
// on a row, plus the auto-clearing inline `.diff-inline` banner shown for
// shift-click two-way diffs from the toolbar.
//
// Lifted verbatim from `behavior.ts`'s `fillDiff` / `computeDiffStats` /
// `applyDiffStats` / `renderHistoryDiffMode` / `buildHistoryDiffStack` /
// `buildDiffPane` / `showDiff` cluster. Closes the history-side
// extraction started in #818 (pure helpers + tests) and #820 (timeline
// + row construction). After this PR `history/behavior.ts` is just
// orchestration — message dispatch, filter / scope plumbing, the
// initial render kickoff.
//
// The pixel-diff helper (`computeDiffStats`) is duplicated semantically
// from the live preview panel's `diffOverlay.ts`. Keeping the two
// copies for now since the History panel is webview-self-contained
// (no Lit dependency); a future shared module under
// `webview/shared/pixelDiff.ts` could collapse them once the
// component story stabilises.

import { buildDiffModeBar, type DiffMode } from "../shared/diffModeBar";
import type { HistoryDiffSummary } from "../shared/types";
import type { VsCodeApi } from "../shared/vscode";
import { cssEscape } from "./historyData";

interface DiffPayload {
    leftLabel: string;
    leftImage: string;
    rightLabel: string;
    rightImage: string;
}

interface PersistedHistoryState {
    diffMode?: DiffMode;
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

export interface HistoryDiffViewConfig {
    /** Untyped vscode handle — `fillDiff` casts the persisted state to
     *  `PersistedHistoryState` on read so callers don't have to thread
     *  the type parameter through. */
    vscode: VsCodeApi<unknown>;
    /** `<div id="timeline">` — diff fills target the matching expansion via
     *  `[data-id][data-against]`; `showDiff` prepends an inline banner. */
    timelineEl: HTMLElement;
}

/**
 * Populate an open `.expanded.diff-expanded` element with the streamed
 * diff payload. Wires up the Side / Overlay / Onion mode bar (persisted
 * via `vscode.setState`), kicks off the async pixel-diff stats, and
 * renders the matching mode body.
 */
export function fillDiff(
    id: string,
    against: "previous" | "current",
    leftLabel: string,
    leftImage: string,
    rightLabel: string,
    rightImage: string,
    config: HistoryDiffViewConfig,
): void {
    const expansion = config.timelineEl.querySelector<HTMLElement>(
        '.expanded[data-id="' +
            cssEscape(id) +
            '"][data-against="' +
            cssEscape(against) +
            '"]',
    );
    if (!expansion) return;
    expansion.innerHTML = "";
    const payload: DiffPayload = {
        leftLabel,
        leftImage,
        rightLabel,
        rightImage,
    };
    const stored =
        (config.vscode.getState() as PersistedHistoryState | undefined) ?? {};
    const initialMode: DiffMode =
        stored.diffMode === "overlay" || stored.diffMode === "onion"
            ? stored.diffMode
            : "side";
    const header = document.createElement("div");
    header.className = "diff-header";
    const body = document.createElement("div");
    body.className = "diff-body";
    const modeBar = buildDiffModeBar(initialMode, (mode) => {
        const cur =
            (config.vscode.getState() as PersistedHistoryState | undefined) ??
            {};
        cur.diffMode = mode;
        config.vscode.setState(cur);
        renderHistoryDiffMode(body, mode, payload);
    });
    const stats = document.createElement("div");
    stats.className = "diff-stats";
    stats.textContent = "computing…";
    header.appendChild(modeBar);
    header.appendChild(stats);
    expansion.appendChild(header);
    expansion.appendChild(body);
    renderHistoryDiffMode(body, initialMode, payload);
    computeDiffStats(payload.leftImage, payload.rightImage).then((s) => {
        applyDiffStats(stats, s);
    });
}

/**
 * Async client-side pixel diff between two base64 PNG images. Resolves
 * a typed `DiffStats` discriminated union — error / size mismatch /
 * pixel count + same-size dimensions. Doesn't reject: every failure
 * mode lands as a `{ error }` variant so callers don't have to wrap
 * the call in a try/catch.
 *
 * Duplicated from `webview/preview/diffOverlay.ts`; same algorithm.
 */
export function computeDiffStats(
    leftBase64: string,
    rightBase64: string,
): Promise<DiffStats> {
    return new Promise<DiffStats>((resolve) => {
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
                const ctx1 = c1.getContext("2d");
                if (!ctx1) {
                    resolve({ error: "canvas 2d context unavailable" });
                    return;
                }
                ctx1.drawImage(left, 0, 0);
                const d1 = ctx1.getImageData(0, 0, w, h).data;
                const c2 = document.createElement("canvas");
                c2.width = w;
                c2.height = h;
                const ctx2 = c2.getContext("2d");
                if (!ctx2) {
                    resolve({ error: "canvas 2d context unavailable" });
                    return;
                }
                ctx2.drawImage(right, 0, 0);
                const d2 = ctx2.getImageData(0, 0, w, h).data;
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
                        err instanceof Error
                            ? err.message
                            : "stats unavailable",
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

/**
 * Render the stats label for a completed `computeDiffStats`. Sets a
 * `data-state` attribute on [el] so the CSS can colour the label by
 * outcome (`identical`, `changed`, `size-mismatch`).
 */
export function applyDiffStats(el: HTMLElement, s: DiffStats | null): void {
    if (!s) {
        el.textContent = "";
        el.removeAttribute("data-state");
        return;
    }
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

function renderHistoryDiffMode(
    body: HTMLElement,
    mode: DiffMode,
    payload: DiffPayload,
): void {
    body.innerHTML = "";
    if (mode === "side") {
        const grid = document.createElement("div");
        grid.className = "diff-grid";
        grid.appendChild(buildDiffPane(payload.leftLabel, payload.leftImage));
        grid.appendChild(buildDiffPane(payload.rightLabel, payload.rightImage));
        body.appendChild(grid);
        return;
    }
    body.appendChild(buildHistoryDiffStack(mode, payload));
}

function buildHistoryDiffStack(
    mode: DiffMode,
    payload: DiffPayload,
): HTMLElement {
    const wrapper = document.createElement("div");
    wrapper.className = "diff-stack-wrapper";
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
            const v = Number(slider.value);
            stack.style.setProperty(
                "--diff-onion-mix",
                (Number.isFinite(v) ? v / 100 : 0.5).toString(),
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

function buildDiffPane(label: string, imageData: string): HTMLElement {
    const pane = document.createElement("div");
    pane.className = "diff-pane";
    const cap = document.createElement("div");
    cap.className = "diff-pane-label";
    cap.textContent = label;
    pane.appendChild(cap);
    if (imageData) {
        const img = document.createElement("img");
        img.src = "data:image/png;base64," + imageData;
        img.alt = label;
        pane.appendChild(img);
    } else {
        const empty = document.createElement("div");
        empty.className = "diff-pane-empty";
        empty.textContent = "(no image)";
        pane.appendChild(empty);
    }
    return pane;
}

/**
 * Show the inline two-way diff banner at the top of the timeline.
 * Triggered by the toolbar Diff button (shift-click two rows + click).
 * Auto-clears after 12s so stale diffs don't accumulate when the user
 * leaves the panel open.
 */
export function showDiff(
    _fromId: string,
    _toId: string,
    result: HistoryDiffSummary | null,
    config: HistoryDiffViewConfig,
): void {
    const block = document.createElement("div");
    block.className = "diff-inline";
    if (!result) {
        block.textContent = "Diff unavailable.";
    } else {
        const changed = result.pngHashChanged
            ? "pixels differ"
            : "bytes identical";
        block.textContent =
            "Diff (metadata): " +
            changed +
            (result.diffPx != null ? " · diffPx=" + result.diffPx : "") +
            (result.ssim != null ? " · ssim=" + result.ssim.toFixed(3) : "");
    }
    config.timelineEl.insertBefore(block, config.timelineEl.firstChild);
    // Auto-clear after 12s so the panel doesn't accumulate stale diffs.
    setTimeout(() => block.remove(), 12_000);
}
