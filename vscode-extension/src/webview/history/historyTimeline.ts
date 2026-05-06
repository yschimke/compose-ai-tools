// Timeline + row construction for the History panel.
//
// Step 1 of #858: row markup now lives in the `<history-row>` Lit
// component (`./components/HistoryRow.ts`). This module shrank to:
//
//  - `renderTimeline`: declarative iteration over `entries`, swapping
//    out `<history-row>` children on the timeline container.
//  - `toggleSelected`: still owns the (max-2) selection set since
//    that state lives in the host's `behavior.ts` closure for now.
//    Step 2 of #858 moves selection state into the row's `@state`.
//  - `expandRow` / `requestRowDiff` / `fillExpansion`: still
//    imperative because the inline `.expanded` sibling div is a
//    separate concern from row rendering and gets its own component
//    later in the issue.
//
// Thumb byte cache: deleted from the config in favour of
// `historyStore` (`./historyStore.ts`). Rows subscribe directly via
// [StoreController] and re-render when their entry's bytes land.

import "./components/HistoryRow";

import type { HistoryEntry } from "../shared/types";
import type { VsCodeApi } from "../shared/vscode";
import { HistoryRow, type HistoryRowCallbacks } from "./components/HistoryRow";
import { cssEscape, findLatestMainHash } from "./historyData";

export interface HistoryRowConfig {
    vscode: VsCodeApi<unknown>;
    /** `<div id="timeline">` — the parent container for all rows. */
    timelineEl: HTMLElement;
    /** Toolbar diff button — disabled state tracks `selectedIds.size === 2`. */
    btnDiffEl: HTMLButtonElement;
    /** Per-session selection set (max 2). Direct Set reference; mutated
     *  by `toggleSelected`. */
    selectedIds: Set<string>;
    /** Currently expanded row's id, or `null` when nothing is expanded.
     *  Mutated by `expandRow` (toggle) and `requestRowDiff` (replace). */
    getExpandedId(): string | null;
    setExpandedId(id: string | null): void;
    /** Dedup set: ids we've already asked the extension to load.
     *  Cleared per `renderTimeline` so a fresh entry set retries. */
    thumbRequested: Set<string>;
    /** Lazy-load observer for the thumb column — `null` on environments
     *  without `IntersectionObserver`. Disconnected per render. */
    thumbObserver: IntersectionObserver | null;
}

export function renderTimeline(
    entries: readonly HistoryEntry[],
    config: HistoryRowConfig,
): void {
    // Thumbnails not yet loaded for the new entry set should retry —
    // disconnect the old observer and rebuild against the new rows.
    if (config.thumbObserver) config.thumbObserver.disconnect();
    config.thumbRequested.clear();
    config.timelineEl.innerHTML = "";

    // Latest archived render on main for the currently scoped preview.
    // O(N) over the visible page; no extra request. Used for the
    // "vs main" indicator dot in each row.
    const mainHash = findLatestMainHash(entries);

    const callbacks: HistoryRowCallbacks = {
        onToggleSelected: (id, row) => toggleSelected(id, row, config),
        onExpand: (id, row) => expandRow(id, row, config),
        onDiffPrevious: (id, row) =>
            requestRowDiff(id, row, "previous", config),
        onDiffCurrent: (id, row) => requestRowDiff(id, row, "current", config),
        onThumbConnected: (thumbEl) => {
            // Defer the byte fetch to the host's IntersectionObserver
            // so off-screen rows don't hammer the daemon. The store
            // covers cache hits already.
            if (config.thumbObserver) config.thumbObserver.observe(thumbEl);
        },
    };

    for (const entry of entries) {
        const row = document.createElement("history-row") as HistoryRow;
        row.entry = entry;
        row.mainHash = mainHash;
        row.callbacks = callbacks;
        config.timelineEl.appendChild(row);
    }
}

/** Toggle [id] in/out of the (max-2) selection set, mirroring the row's
 *  `.selected` state. Drops the oldest selection when adding a third. */
export function toggleSelected(
    id: string,
    row: HistoryRow,
    config: HistoryRowConfig,
): void {
    if (config.selectedIds.has(id)) {
        config.selectedIds.delete(id);
        row.setSelected(false);
    } else {
        if (config.selectedIds.size >= 2) {
            // Drop oldest selection so we never have more than 2.
            const drop = [...config.selectedIds][0];
            config.selectedIds.delete(drop);
            const prev = config.timelineEl.querySelector<HistoryRow>(
                'history-row[data-id="' + cssEscape(drop) + '"]',
            );
            prev?.setSelected(false);
        }
        config.selectedIds.add(id);
        row.setSelected(true);
    }
    config.btnDiffEl.disabled = config.selectedIds.size !== 2;
}

/** Toggle the inline expansion (full-size image + actions) for [id].
 *  Collapses any other open expansion first. Posts `loadImage` so the
 *  extension streams the PNG bytes back asynchronously. */
export function expandRow(
    id: string,
    row: HistoryRow,
    config: HistoryRowConfig,
): void {
    const prev = config.timelineEl.querySelector(".expanded");
    if (prev) prev.remove();
    if (config.getExpandedId() === id) {
        config.setExpandedId(null);
        return;
    }
    config.setExpandedId(id);

    const expansion = document.createElement("div");
    expansion.className = "expanded";
    expansion.dataset.id = id;
    expansion.innerHTML = "<div>Loading…</div>";
    row.parentNode?.insertBefore(expansion, row.nextSibling);
    config.vscode.postMessage({ command: "loadImage", id });
}

/** Open an inline diff expansion against the previous / current entry.
 *  Replaces any open expansion (image OR diff) and asks the extension
 *  to compute the comparison. */
export function requestRowDiff(
    id: string,
    row: HistoryRow,
    against: "previous" | "current",
    config: HistoryRowConfig,
): void {
    const prev = config.timelineEl.querySelector(".expanded");
    if (prev) prev.remove();
    config.setExpandedId(id);
    const expansion = document.createElement("div");
    expansion.className = "expanded diff-expanded";
    expansion.dataset.id = id;
    expansion.dataset.against = against;
    expansion.innerHTML = "<div>Loading diff…</div>";
    row.parentNode?.insertBefore(expansion, row.nextSibling);
    config.vscode.postMessage({ command: "requestDiff", id, against });
}

/** Populate an open expansion with the streamed full-size image plus
 *  an "Open in Editor" action when the entry's previewMetadata
 *  surfaces a sourceFile. No-op when the matching `.expanded` is gone
 *  (user collapsed before the bytes arrived). */
export function fillExpansion(
    id: string,
    imageData: string,
    entry: HistoryEntry | undefined,
    config: HistoryRowConfig,
): void {
    const expansion = config.timelineEl.querySelector(
        '.expanded[data-id="' + cssEscape(id) + '"]',
    );
    if (!expansion) return;
    expansion.innerHTML = "";
    const img = document.createElement("img");
    img.src = "data:image/png;base64," + imageData;
    img.alt = (entry && entry.previewId) || id;
    expansion.appendChild(img);

    const actions = document.createElement("div");
    actions.className = "actions";
    const sourceFile =
        entry && entry.previewMetadata && entry.previewMetadata.sourceFile;
    if (sourceFile) {
        const open = document.createElement("button");
        open.textContent = "Open in Editor";
        open.addEventListener("click", () =>
            config.vscode.postMessage({ command: "openSource", sourceFile }),
        );
        actions.appendChild(open);
    }
    expansion.appendChild(actions);
}
