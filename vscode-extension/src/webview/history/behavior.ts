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

import type { HistoryEntry, HistoryToWebview } from "../shared/types";
import { getVsCodeApi } from "../shared/vscode";
import { cssEscape } from "./historyData";
import {
    fillDiff as fillDiffDom,
    type HistoryDiffViewConfig,
    showDiff as showDiffDom,
} from "./historyDiffView";
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

    // Diff sub-views (per-row diff expansion + inline two-way banner)
    // live in `./historyDiffView.ts`.
    const diffViewConfig: HistoryDiffViewConfig = { vscode, timelineEl };

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
                showDiffDom(msg.fromId, msg.toId, msg.result, diffViewConfig);
                break;
            case "diffError":
                showDiffDom(msg.fromId, msg.toId, null, diffViewConfig);
                break;
            case "diffReady":
                fillDiffDom(
                    msg.id,
                    msg.against,
                    msg.leftLabel,
                    msg.leftImage,
                    msg.rightLabel,
                    msg.rightImage,
                    diffViewConfig,
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
