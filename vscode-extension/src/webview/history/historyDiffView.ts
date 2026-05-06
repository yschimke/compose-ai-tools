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
// The pixel-diff helper lives in `webview/shared/pixelDiff.ts` — the
// preview panel's diff overlay reaches for the same algorithm.

import { buildDiffModeBar, type DiffMode } from "../shared/diffModeBar";
import { computeDiffStats, type DiffStats } from "../shared/pixelDiff";
import type { HistoryDiffSummary } from "../shared/types";
import type { VsCodeApi } from "../shared/vscode";
import { cssEscape } from "./historyData";

export type { DiffStats };

interface DiffPayload {
    leftLabel: string;
    leftImage: string;
    rightLabel: string;
    rightImage: string;
}

interface PersistedHistoryState {
    diffMode?: DiffMode;
}

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
