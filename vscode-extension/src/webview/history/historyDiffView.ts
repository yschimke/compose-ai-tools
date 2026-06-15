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

import { computeA11yDiffData } from "../preview/historyDiffA11yPresenter";
import { computeSemanticsDiffData } from "../preview/historyDiffSemanticsPresenter";
import { computeThemeDiffData } from "../preview/historyDiffThemePresenter";
import { diffA11y, type A11yPayload } from "../shared/a11yDiff";
import { buildDiffModeBar, type DiffMode } from "../shared/diffModeBar";
import {
    applyDiffStats,
    computeDiffStats,
    type DiffStats,
} from "../shared/pixelDiff";
import { diffSemantics, type SemanticsPayload } from "../shared/semanticsDiff";
import {
    diffTheme,
    isThemePayload,
    type ThemeCategory,
} from "../shared/themeDiff";
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
    leftSemantics?: unknown,
    rightSemantics?: unknown,
    leftTheme?: unknown,
    rightTheme?: unknown,
    leftA11y?: unknown,
    rightA11y?: unknown,
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
    appendSemanticsDiffSection(expansion, leftSemantics, rightSemantics);
    appendThemeDiffSection(expansion, leftTheme, rightTheme);
    appendA11yDiffSection(expansion, leftA11y, rightA11y);
}

/**
 * Appends the semantics data-diff (#1872) below the pixel diff. Both entries' captured
 * `compose/semantics` trees are diffed client-side via the shared [diffSemantics] port; the section
 * is omitted entirely when either tree is missing (a render that didn't capture semantics) so the
 * pixel-only case is unchanged. Lives as a sibling of the pixel `body`, so the Side/Overlay/Onion
 * mode switches (which rewrite `body`) leave it intact.
 */
function appendSemanticsDiffSection(
    expansion: HTMLElement,
    leftSemantics: unknown,
    rightSemantics: unknown,
): void {
    if (
        !isSemanticsPayload(leftSemantics) ||
        !isSemanticsPayload(rightSemantics)
    ) {
        return;
    }
    let data: ReturnType<typeof computeSemanticsDiffData>;
    try {
        // Direction: base = the older "Previous" entry (right), head = "This entry" (left), so
        // `added` reads as nodes newly present in this entry and `removed` as nodes gone from it —
        // matching how the user reads "what changed in this entry vs the previous".
        data = computeSemanticsDiffData(
            diffSemantics(rightSemantics, leftSemantics),
        );
    } catch {
        return;
    }
    const section = document.createElement("div");
    section.className = "diff-semantics";
    const head = document.createElement("div");
    head.className = "diff-semantics-header";
    head.textContent = data.empty
        ? "Semantics · no changes"
        : `Semantics · ${data.addedCount} added · ${data.removedCount} removed · ${data.changedCount} changed`;
    section.appendChild(head);
    if (!data.empty) {
        const list = document.createElement("ul");
        list.className = "diff-semantics-list";
        for (const row of data.rows) {
            const li = document.createElement("li");
            li.className = `diff-semantics-row diff-semantics-${row.kind}`;
            const sigil =
                row.kind === "added" ? "+" : row.kind === "removed" ? "−" : "~";
            const label = document.createElement("span");
            label.className = "diff-semantics-label";
            label.textContent = `${sigil} ${row.label}`;
            li.appendChild(label);
            if (row.fields.length > 0) {
                const fields = document.createElement("ul");
                fields.className = "diff-semantics-fields";
                for (const field of row.fields) {
                    const fieldLi = document.createElement("li");
                    fieldLi.textContent = `${field.field}: ${field.from ?? "∅"} → ${field.to ?? "∅"}`;
                    fields.appendChild(fieldLi);
                }
                li.appendChild(fields);
            }
            list.appendChild(li);
        }
        section.appendChild(list);
    }
    expansion.appendChild(section);
}

/** Narrows an untyped wire value to a `{ root }` semantics payload. */
function isSemanticsPayload(value: unknown): value is SemanticsPayload {
    return (
        typeof value === "object" &&
        value !== null &&
        "root" in value &&
        typeof (value as { root: unknown }).root === "object" &&
        (value as { root: unknown }).root !== null
    );
}

const THEME_CATEGORY_LABEL: Record<ThemeCategory, string> = {
    color: "color",
    typography: "type",
    shape: "shape",
};

/**
 * Appends the theme data-diff (#1872) below the pixel diff (and the semantics section). Both
 * entries' captured `compose/theme` resolved tokens are diffed client-side via [diffTheme]; the
 * section is omitted entirely when either payload is missing (a render that didn't capture theme) so
 * the pixel-only case is unchanged. A sibling of the pixel `body`, so the mode switches leave it
 * intact.
 */
function appendThemeDiffSection(
    expansion: HTMLElement,
    leftTheme: unknown,
    rightTheme: unknown,
): void {
    if (!isThemePayload(leftTheme) || !isThemePayload(rightTheme)) {
        return;
    }
    let data: ReturnType<typeof computeThemeDiffData>;
    try {
        // base = older "Previous" (right), head = "This entry" (left): `added` reads as tokens newly
        // present and `changed` as previous → current, matching the semantics direction.
        data = computeThemeDiffData(diffTheme(rightTheme, leftTheme));
    } catch {
        return;
    }
    const section = document.createElement("div");
    section.className = "diff-theme";
    const head = document.createElement("div");
    head.className = "diff-theme-header";
    head.textContent = data.empty
        ? "Theme · no changes"
        : `Theme · ${data.addedCount} added · ${data.removedCount} removed · ${data.changedCount} changed`;
    section.appendChild(head);
    if (!data.empty) {
        const list = document.createElement("ul");
        list.className = "diff-theme-list";
        for (const row of data.rows) {
            const li = document.createElement("li");
            li.className = `diff-theme-row diff-theme-${row.kind}`;
            const sigil =
                row.kind === "added" ? "+" : row.kind === "removed" ? "−" : "~";
            const label = document.createElement("span");
            label.className = "diff-theme-label";
            const badge = THEME_CATEGORY_LABEL[row.category];
            label.textContent =
                row.kind === "changed"
                    ? `${sigil} [${badge}] ${row.key}`
                    : `${sigil} [${badge}] ${row.key} = ${row.value ?? ""}`;
            li.appendChild(label);
            if (row.kind === "changed") {
                const change = document.createElement("div");
                change.className = "diff-theme-change";
                change.textContent = `${row.from ?? "∅"} → ${row.to ?? "∅"}`;
                li.appendChild(change);
            }
            list.appendChild(li);
        }
        section.appendChild(list);
    }
    expansion.appendChild(section);
}

/**
 * Appends the a11y data-diff (#1872) below the pixel diff (and the semantics / theme sections). Both
 * entries' captured `a11y/hierarchy` node lists are diffed client-side via [diffA11y]; the section
 * is omitted when either payload is missing (a render that didn't capture a11y) so the pixel-only
 * case is unchanged.
 */
function appendA11yDiffSection(
    expansion: HTMLElement,
    leftA11y: unknown,
    rightA11y: unknown,
): void {
    if (!isA11yPayload(leftA11y) || !isA11yPayload(rightA11y)) {
        return;
    }
    let data: ReturnType<typeof computeA11yDiffData>;
    try {
        // base = older "Previous" (right), head = "This entry" (left), matching the other sections.
        data = computeA11yDiffData(diffA11y(rightA11y, leftA11y));
    } catch {
        return;
    }
    const section = document.createElement("div");
    section.className = "diff-a11y";
    const head = document.createElement("div");
    head.className = "diff-a11y-header";
    head.textContent = data.empty
        ? "Accessibility · no changes"
        : `Accessibility · ${data.addedCount} added · ${data.removedCount} removed · ${data.changedCount} changed`;
    section.appendChild(head);
    if (!data.empty) {
        const list = document.createElement("ul");
        list.className = "diff-a11y-list";
        for (const row of data.rows) {
            const li = document.createElement("li");
            li.className = `diff-a11y-row diff-a11y-${row.kind}`;
            const sigil =
                row.kind === "added" ? "+" : row.kind === "removed" ? "−" : "~";
            const label = document.createElement("span");
            label.className = "diff-a11y-label";
            label.textContent = `${sigil} ${row.label}`;
            li.appendChild(label);
            if (row.fields.length > 0) {
                const fields = document.createElement("ul");
                fields.className = "diff-a11y-fields";
                for (const field of row.fields) {
                    const fieldLi = document.createElement("li");
                    fieldLi.textContent = `${field.field}: ${field.from ?? "∅"} → ${field.to ?? "∅"}`;
                    fields.appendChild(fieldLi);
                }
                li.appendChild(fields);
            }
            list.appendChild(li);
        }
        section.appendChild(list);
    }
    expansion.appendChild(section);
}

/** Narrows an untyped wire value to a `{ nodes }` a11y/hierarchy payload. */
function isA11yPayload(value: unknown): value is A11yPayload {
    return (
        typeof value === "object" &&
        value !== null &&
        "nodes" in value &&
        Array.isArray((value as { nodes: unknown }).nodes)
    );
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
