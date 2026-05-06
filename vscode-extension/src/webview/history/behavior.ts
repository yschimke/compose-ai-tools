// Imperative behaviour for the read-only Preview History panel.
//
// Verbatim port of the previously-inline IIFE script in
// `src/historyPanel.ts` (the `<script nonce="...">` block in `getHtml()`).
// Now type-checked end-to-end against `HistoryToWebview` (from
// `shared/types`). Pieces still imperative — `renderTimeline` (~85 lines
// of `document.createElement`), the diff stack builder, the per-row event
// handlers — are slated to fold into Lit components in a follow-up.
//
// Runs once per webview load. Assumes `<history-app>` has already
// rendered its skeleton into light DOM, so `document.getElementById(...)`
// queries below resolve.

import { buildDiffModeBar, type DiffMode } from "../shared/diffModeBar";
import type {
    HistoryDiffSummary,
    HistoryEntry,
    HistoryToWebview,
} from "../shared/types";
import { getVsCodeApi } from "../shared/vscode";
import { cssEscape } from "./historyData";
import {
    fillExpansion as fillExpansionDom,
    type HistoryRowConfig,
    populateThumb,
    renderTimeline as renderTimelineDom,
} from "./historyTimeline";

/** Look up a known-present DOM element. Used for the static ids that
 *  `<history-app>` has already rendered into the light DOM. Throws so a
 *  missing template surfaces early. */
function requireElementById<T extends HTMLElement>(id: string): T {
    const el = document.getElementById(id);
    if (!el) throw new Error(`Required element #${id} not found`);
    return el as T;
}

export function setupHistoryBehavior(): void {
    const vscode = getVsCodeApi();
    const messageEl = requireElementById<HTMLElement>("message");
    const timelineEl = requireElementById<HTMLElement>("timeline");
    const filterSourceEl =
        requireElementById<HTMLSelectElement>("filter-source");
    const filterBranchEl =
        requireElementById<HTMLSelectElement>("filter-branch");
    const btnRefreshEl = requireElementById<HTMLButtonElement>("btn-refresh");
    const btnDiffEl = requireElementById<HTMLButtonElement>("btn-diff");
    // Scope chip is owned by `<scope-chip>` — see
    // `components/ScopeChip.ts`. It listens for `setScopeLabel` directly.

    let entries: HistoryEntry[] = [];
    const selectedIds = new Set<string>();
    let expandedId: string | null = null;
    // Thumbnail cache + dedup so each entry's PNG is fetched at most once
    // per panel session even if scrolling brings the same row back into
    // view repeatedly.
    const thumbCache = new Map<string, string>();
    const thumbRequested = new Set<string>();
    const thumbObserver: IntersectionObserver | null =
        "IntersectionObserver" in window
            ? new IntersectionObserver(
                  (items) => {
                      for (const item of items) {
                          if (!item.isIntersecting) continue;
                          const el = item.target;
                          if (!(el instanceof HTMLElement)) continue;
                          const id = el.dataset.id;
                          if (!id || thumbRequested.has(id)) continue;
                          thumbRequested.add(id);
                          vscode.postMessage({ command: "loadThumb", id });
                      }
                  },
                  { root: null, rootMargin: "64px", threshold: 0 },
              )
            : null;

    btnRefreshEl.addEventListener("click", () => {
        vscode.postMessage({ command: "refresh" });
    });
    btnDiffEl.addEventListener("click", () => {
        const ids = [...selectedIds];
        if (ids.length === 2) {
            vscode.postMessage({
                command: "diff",
                fromId: ids[0],
                toId: ids[1],
            });
        }
    });
    filterSourceEl.addEventListener("change", applyFilters);
    filterBranchEl.addEventListener("change", applyFilters);

    function setMessage(text: string): void {
        if (text) {
            messageEl.textContent = text;
            messageEl.style.display = "block";
            timelineEl.innerHTML = "";
        } else {
            messageEl.style.display = "none";
        }
    }

    function applyFilters(): void {
        const sourceVal = filterSourceEl.value;
        const branchVal = filterBranchEl.value;
        timelineEl.querySelectorAll<HTMLElement>(".row").forEach((row) => {
            const matchSource =
                sourceVal === "all" || row.dataset.sourceKind === sourceVal;
            const matchBranch =
                branchVal === "all" || row.dataset.branch === branchVal;
            row.style.display = matchSource && matchBranch ? "" : "none";
        });
    }

    function populateBranchFilter(es: readonly HistoryEntry[]): void {
        const branches = new Set<string>();
        for (const e of es) {
            const b = e.git && e.git.branch;
            if (b) branches.add(b);
        }
        const prev = filterBranchEl.value;
        filterBranchEl.innerHTML = "";
        const allOpt = document.createElement("option");
        allOpt.value = "all";
        allOpt.textContent = "All branches";
        filterBranchEl.appendChild(allOpt);
        for (const b of [...branches].sort()) {
            const opt = document.createElement("option");
            opt.value = b;
            opt.textContent = b;
            filterBranchEl.appendChild(opt);
        }
        if ([...filterBranchEl.options].some((o) => o.value === prev)) {
            filterBranchEl.value = prev;
        }
    }

    // Timeline row construction lives in `./historyTimeline.ts` — see
    // `renderTimeline`, `toggleSelected`, `expandRow`, `requestRowDiff`,
    // `fillExpansion`, `populateThumb`. The config exposes the static DOM
    // handles plus thin getter/setter pairs over `expandedId` so the lifted
    // module doesn't need closure access to this scope.
    const rowConfig: HistoryRowConfig = {
        vscode,
        timelineEl,
        btnDiffEl,
        selectedIds,
        getExpandedId: () => expandedId,
        setExpandedId: (next) => {
            expandedId = next;
        },
        thumbCache,
        thumbRequested,
        thumbObserver,
    };
    function renderTimeline(): void {
        renderTimelineDom(entries, rowConfig);
    }

    interface DiffPayload {
        leftLabel: string;
        leftImage: string;
        rightLabel: string;
        rightImage: string;
    }

    interface PersistedHistoryState {
        diffMode?: DiffMode;
    }

    function fillDiff(
        id: string,
        against: "previous" | "current",
        leftLabel: string,
        leftImage: string,
        rightLabel: string,
        rightImage: string,
    ): void {
        const expansion = timelineEl.querySelector<HTMLElement>(
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
        const stored: PersistedHistoryState =
            (vscode.getState() as PersistedHistoryState | undefined) ?? {};
        const initialMode: DiffMode =
            stored.diffMode === "overlay" || stored.diffMode === "onion"
                ? stored.diffMode
                : "side";
        const header = document.createElement("div");
        header.className = "diff-header";
        const body = document.createElement("div");
        body.className = "diff-body";
        const modeBar = buildDiffModeBar(initialMode, (mode) => {
            const cur: PersistedHistoryState =
                (vscode.getState() as PersistedHistoryState | undefined) ?? {};
            cur.diffMode = mode;
            vscode.setState(cur);
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

    type DiffStats =
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

    // Client-side pixel diff helpers — duplicated from the live panel
    // so the History panel webview is self-contained. Same algorithm.
    function computeDiffStats(
        leftBase64: string,
        rightBase64: string,
    ): Promise<DiffStats> {
        return new Promise<DiffStats>((resolve) => {
            const left = new Image();
            const right = new Image();
            let loaded = 0;
            const onErr = (): void =>
                resolve({ error: "image failed to load" });
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

    function applyDiffStats(el: HTMLElement, s: DiffStats | null): void {
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
            s.diffPx.toLocaleString() +
            " px (" +
            pct +
            "%) · " +
            s.w +
            "×" +
            s.h;
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
            grid.appendChild(
                buildDiffPane(payload.leftLabel, payload.leftImage),
            );
            grid.appendChild(
                buildDiffPane(payload.rightLabel, payload.rightImage),
            );
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

    function showDiff(
        fromId: string,
        toId: string,
        result: HistoryDiffSummary | null,
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
                (result.ssim != null
                    ? " · ssim=" + result.ssim.toFixed(3)
                    : "");
        }
        timelineEl.insertBefore(block, timelineEl.firstChild);
        // Auto-clear after 12s so the panel doesn't accumulate stale diffs.
        setTimeout(() => block.remove(), 12_000);
    }

    window.addEventListener("message", (event: MessageEvent) => {
        const msg = event.data as HistoryToWebview;
        switch (msg.command) {
            case "setEntries":
                entries = msg.result.entries || [];
                if (entries.length === 0) {
                    setMessage("No history yet for this preview / module.");
                } else {
                    setMessage("");
                    populateBranchFilter(entries);
                    renderTimeline();
                    applyFilters();
                }
                break;
            case "entryAdded":
                if (msg.entry) {
                    entries.unshift(msg.entry);
                    populateBranchFilter(entries);
                    renderTimeline();
                    applyFilters();
                }
                break;
            case "showMessage":
                setMessage(msg.text || "");
                break;
            case "setScopeLabel":
                // Handled directly by `<scope-chip>` — it listens on `window`
                // for this command. Listed here so the discriminated-union
                // exhaustiveness check holds.
                break;
            case "imageReady":
                fillExpansionDom(msg.id, msg.imageData, msg.entry, rowConfig);
                break;
            case "imageError": {
                const expansion = timelineEl.querySelector<HTMLElement>(
                    '.expanded[data-id="' + cssEscape(msg.id) + '"]',
                );
                if (expansion)
                    expansion.textContent =
                        "Failed to load image: " +
                        (msg.message || "(no detail)");
                break;
            }
            case "thumbReady": {
                thumbCache.set(msg.id, msg.imageData);
                const thumbEl = timelineEl.querySelector<HTMLElement>(
                    '.thumb[data-id="' + cssEscape(msg.id) + '"]',
                );
                if (thumbEl) populateThumb(thumbEl, msg.imageData);
                break;
            }
            case "thumbError":
                // Drop the dedup so a future re-render can retry. Leave
                // the gray box in place; surfacing per-entry errors at
                // the thumb scale would just be noisy.
                thumbRequested.delete(msg.id);
                break;
            case "diffResult":
                showDiff(msg.fromId, msg.toId, msg.result);
                break;
            case "diffError":
                showDiff(msg.fromId, msg.toId, null);
                break;
            case "diffReady":
                fillDiff(
                    msg.id,
                    msg.against,
                    msg.leftLabel,
                    msg.leftImage,
                    msg.rightLabel,
                    msg.rightImage,
                );
                break;
            case "diffPairError": {
                const expansion = timelineEl.querySelector(
                    '.expanded[data-id="' +
                        cssEscape(msg.id) +
                        '"][data-against="' +
                        cssEscape(msg.against) +
                        '"]',
                );
                if (expansion)
                    expansion.textContent =
                        "Diff unavailable: " + (msg.message || "(no detail)");
                break;
            }
        }
    });
}
