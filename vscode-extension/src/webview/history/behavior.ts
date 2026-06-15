// Imperative behaviour for the read-only Preview History panel.
//
// Originally a verbatim port of the inline IIFE script in
// `src/historyPanel.ts`'s `getHtml()`. Type-checked end-to-end against
// `HistoryToWebview` (from `shared/types`). Step 1 of #858 lifted the
// per-row markup into the `<history-row>` Lit component
// (`./components/HistoryRow.ts`) and routed thumb bytes through
// `historyStore`. Step 2 moved selection state into the row itself —
// this module just listens for `history-row-selection-change` to
// keep a (max-2) queue for the diff button.
//
// Step 3 of #858: inline expansion state is now also row-owned. The
// row dispatches `history-row-expand` (bubbling) on plain-click /
// diff-button-click; the listener below collapses any other open row
// and posts the matching `loadImage` / `requestDiff`. Extension
// responses (`imageReady` / `imageError` / `diffReady` /
// `diffPairError`) route to the targeted row via `setImage` /
// `setImageError` / `setDiff` / `setDiffError`. The host no longer
// holds an `expandedId` scalar.
//
// Runs once per webview load. Assumes `<history-app>` has already
// rendered its skeleton into light DOM, so `document.getElementById(...)`
// queries below resolve.

import { requireElementById } from "../shared/domRefs";
import type { HistoryEntry, HistoryToWebview } from "../shared/types";
import { getVsCodeApi } from "../shared/vscode";
import type { HistoryRow } from "./components/HistoryRow";
import { cssEscape } from "./historyData";
import {
    type HistoryDiffViewConfig,
    showDiff as showDiffDom,
} from "./historyDiffView";
import { setThumb } from "./historyStore";
import {
    type HistoryRowConfig,
    renderTimeline as renderTimelineDom,
} from "./historyTimeline";

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
    const btnSourceRefEl =
        requireElementById<HTMLButtonElement>("btn-source-ref");
    // Scope chip is owned by `<scope-chip>` — see
    // `components/ScopeChip.ts`. It listens for `setScopeLabel` directly.

    let entries: HistoryEntry[] = [];
    // The active history source's ref (null = local). Tracked so the "Source"
    // filter is only reset when the source genuinely changes (#1872), not on
    // every routine `setSourceRef` re-post. `undefined` until the first push.
    let activeSourceRef: string | null | undefined = undefined;
    // (max-2) selection queue (oldest first). Each `<history-row>` owns
    // its own `selected` state and dispatches
    // `history-row-selection-change` on shift-click; this list is the
    // host's aggregate view of which rows are currently picked, used to
    // gate the diff button and to evict the oldest pick when a third
    // row is shift-clicked.
    const selectedOrder: string[] = [];
    // Thumbnail bytes live in `historyStore` so `<history-row>` can
    // subscribe and re-render reactively. `thumbRequested` here just
    // dedupes the per-session fetch — the IntersectionObserver fires
    // once per row scroll-into-view and we skip ids we've already
    // asked the extension to load.
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
    btnSourceRefEl.addEventListener("click", () => {
        vscode.postMessage({ command: "selectSourceRef" });
    });
    btnDiffEl.addEventListener("click", () => {
        if (selectedOrder.length === 2) {
            vscode.postMessage({
                command: "diff",
                fromId: selectedOrder[0],
                toId: selectedOrder[1],
            });
        }
    });
    timelineEl.addEventListener(
        "history-row-selection-change",
        (event: CustomEvent<{ id: string; selected: boolean }>) => {
            const { id, selected } = event.detail;
            if (selected) {
                // Evict the oldest pick when a third row joins so we
                // never exceed two selections. The row dispatched this
                // event already, so its `.selected` is true; we only
                // need to flip the evictee back.
                if (selectedOrder.length >= 2) {
                    const drop = selectedOrder.shift();
                    if (drop !== undefined) {
                        const prev = timelineEl.querySelector<HistoryRow>(
                            'history-row[data-id="' + cssEscape(drop) + '"]',
                        );
                        if (prev) prev.selected = false;
                    }
                }
                selectedOrder.push(id);
            } else {
                const idx = selectedOrder.indexOf(id);
                if (idx !== -1) selectedOrder.splice(idx, 1);
            }
            btnDiffEl.disabled = selectedOrder.length !== 2;
        },
    );
    timelineEl.addEventListener(
        "history-row-expand",
        (
            event: CustomEvent<{
                id: string;
                kind: "image" | "diff";
                against?: "previous" | "current";
            }>,
        ) => {
            const { id, kind, against } = event.detail;
            // Only one expansion at a time. Walk all rows and collapse
            // the others; the dispatching row has already flipped its
            // own `_expandedKind` so we leave it alone.
            const rows = timelineEl.querySelectorAll<HistoryRow>("history-row");
            for (const row of rows) {
                if (row.dataset.id !== id) row.collapse();
            }
            if (kind === "image") {
                vscode.postMessage({ command: "loadImage", id });
            } else if (kind === "diff" && against) {
                vscode.postMessage({ command: "requestDiff", id, against });
            }
        },
    );
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

    /** Look up the `<history-row>` for [id] in the timeline. Returns
     *  null when the row was removed (entry filtered out, panel
     *  re-rendered between request and response). */
    function findRow(id: string): HistoryRow | null {
        return timelineEl.querySelector<HistoryRow>(
            'history-row[data-id="' + cssEscape(id) + '"]',
        );
    }

    // Diff sub-views (per-row diff expansion + inline two-way banner)
    // live in `./historyDiffView.ts`.
    const diffViewConfig: HistoryDiffViewConfig = { vscode, timelineEl };

    // Timeline row markup lives in the `<history-row>` Lit component
    // (`./components/HistoryRow.ts`); selection + expansion state live
    // there too and bubble up via the `history-row-selection-change`
    // and `history-row-expand` listeners wired above. The config now
    // exposes only the static DOM handles plus the lazy-load observer
    // and the diff-view config the row hands to `fillDiff`.
    const rowConfig: HistoryRowConfig = {
        vscode,
        timelineEl,
        btnDiffEl,
        thumbRequested,
        thumbObserver,
        diffViewConfig,
    };
    function renderTimeline(): void {
        renderTimelineDom(entries, rowConfig);
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
            case "entriesPruned": {
                const removed = new Set(msg.removedIds);
                if (removed.size === 0) {
                    break;
                }
                const before = entries.length;
                entries = entries.filter((e) => !(e.id && removed.has(e.id)));
                if (entries.length !== before) {
                    if (entries.length === 0) {
                        setMessage("No history yet for this preview / module.");
                    }
                    populateBranchFilter(entries);
                    renderTimeline();
                    applyFilters();
                }
                break;
            }
            case "showMessage":
                setMessage(msg.text || "");
                break;
            case "setScopeLabel":
                // Handled directly by `<scope-chip>` — it listens on `window`
                // for this command. Listed here so the discriminated-union
                // exhaustiveness check holds.
                break;
            case "setSourceRef": {
                // Reflect the active history source in the toolbar button: the
                // local working tree, or a pushed reporting branch (#1872).
                const label = msg.label ?? "Local";
                btnSourceRefEl.textContent = label;
                btnSourceRefEl.classList.toggle("active", msg.ref != null);
                btnSourceRefEl.title =
                    msg.ref != null
                        ? `Viewing reporting branch ${label} — click to change source`
                        : "Choose history source: the local working tree or a pushed reporting branch";
                // Switching the underlying source flips the entries' source kind
                // (git for a reporting branch, fs for local). A stale "Source"
                // filter (fs/git) would then hide the entire new dataset and the
                // panel would look empty, so reset it to "all" when the source
                // actually changes — but leave a deliberate filter alone across
                // routine refreshes (which re-post the same ref).
                if (msg.ref !== activeSourceRef) {
                    activeSourceRef = msg.ref;
                    filterSourceEl.value = "all";
                }
                break;
            }
            case "imageReady": {
                const row = findRow(msg.id);
                if (row) row.setImage(msg.imageData, msg.entry);
                break;
            }
            case "imageError": {
                const row = findRow(msg.id);
                if (row) row.setImageError(msg.message || "(no detail)");
                break;
            }
            case "thumbReady":
                // Writing into `historyStore` re-renders the matching
                // `<history-row>` via its StoreController subscription;
                // no manual DOM patch needed.
                setThumb(msg.id, msg.imageData);
                break;
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
            case "diffReady": {
                const row = findRow(msg.id);
                if (row)
                    row.setDiff(
                        msg.against,
                        msg.leftLabel,
                        msg.leftImage,
                        msg.rightLabel,
                        msg.rightImage,
                        msg.leftSemantics,
                        msg.rightSemantics,
                        msg.leftTheme,
                        msg.rightTheme,
                    );
                break;
            }
            case "diffPairError": {
                const row = findRow(msg.id);
                if (row)
                    row.setDiffError(msg.against, msg.message || "(no detail)");
                break;
            }
        }
    });
}
