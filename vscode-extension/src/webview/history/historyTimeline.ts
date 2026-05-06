// Timeline + row construction for the History panel.
//
// Step 1 of #858: row markup now lives in the `<history-row>` Lit
// component (`./components/HistoryRow.ts`).
//
// Step 2 of #858: selection state moved into the row. The row owns
// `selected` and dispatches `history-row-selection-change` on
// shift-click; the host listens on `timelineEl` to maintain the
// (max-2) queue and toggle the diff button.
//
// Step 3 of #858: the row also owns inline expansion state (image /
// diff). Plain click + action-button click now dispatch
// `history-row-expand` (bubbling) — the host listens once on
// `timelineEl`, collapses any other open row, and posts the matching
// `loadImage` / `requestDiff` vscode message. Extension responses
// (`imageReady` / `imageError` / `diffReady` / `diffPairError`) flow
// through `setImage` / `setImageError` / `setDiff` / `setDiffError`
// on the targeted row. The imperative `expandRow` / `requestRowDiff`
// / `fillExpansion` helpers and the `getExpandedId` / `setExpandedId`
// scalars are gone.
//
// What's left here:
//
//  - `renderTimeline`: declarative iteration over `entries`, swapping
//    out `<history-row>` children on the timeline container.
//
// Thumb byte cache: deleted from the config in favour of
// `historyStore` (`./historyStore.ts`). Rows subscribe directly via
// [StoreController] and re-render when their entry's bytes land.

import "./components/HistoryRow";

import type { HistoryEntry } from "../shared/types";
import type { VsCodeApi } from "../shared/vscode";
import { HistoryRow, type HistoryRowCallbacks } from "./components/HistoryRow";
import { findLatestMainHash } from "./historyData";
import type { HistoryDiffViewConfig } from "./historyDiffView";

export interface HistoryRowConfig {
    vscode: VsCodeApi<unknown>;
    /** `<div id="timeline">` — the parent container for all rows. */
    timelineEl: HTMLElement;
    /** Toolbar diff button — disabled state tracks the host-managed
     *  selection queue length (must be exactly 2 to enable). */
    btnDiffEl: HTMLButtonElement;
    /** Dedup set: ids we've already asked the extension to load.
     *  Cleared per `renderTimeline` so a fresh entry set retries. */
    thumbRequested: Set<string>;
    /** Lazy-load observer for the thumb column — `null` on environments
     *  without `IntersectionObserver`. Disconnected per render. */
    thumbObserver: IntersectionObserver | null;
    /** Diff-view config the row hands to `historyDiffView.ts.fillDiff`
     *  once a `diffReady` payload lands. The diff body still flows
     *  through that module's persisted-mode-bar + async pixel-diff
     *  machinery; the row provides the empty `.expanded.diff-expanded`
     *  shell and calls `fillDiff` from its `updated()` hook. */
    diffViewConfig: HistoryDiffViewConfig;
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
        row.diffViewConfig = config.diffViewConfig;
        config.timelineEl.appendChild(row);
    }
}
