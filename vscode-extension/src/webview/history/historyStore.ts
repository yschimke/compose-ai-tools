// Singleton state for the read-only "Preview History" panel.
//
// Migration scope (step 1 of #858): only the thumbnail byte cache.
// `<history-row>` subscribes to this store to pick up its thumb PNG
// once the extension streams it back via `thumbReady`. The rest of the
// panel state — selection, expansion, entries list, filters — still
// lives in `behavior.ts` closure scope until later steps of the issue
// migrate them.
//
// `thumbCache` is a `ReadonlyMap<string, string>`. The reference is
// REPLACED (never mutated in place) on every insert so the store's
// reference-equality change detection actually fires; per-row
// selectors then key off `state.thumbCache.get(entryId)` and re-render
// only when the matching entry's bytes change.

import { Store } from "../shared/store";

export interface HistoryState {
    /**
     * Thumbnail bytes by `HistoryEntry.id`. Populated by `thumbReady`
     * messages from the extension. Treated as immutable — writers MUST
     * allocate a new Map (`new Map(prev).set(id, bytes)`) so reference
     * comparison detects the change.
     */
    thumbCache: ReadonlyMap<string, string>;
}

const initialState: HistoryState = {
    thumbCache: new Map(),
};

export const historyStore = new Store<HistoryState>(initialState);

/** Insert a thumb byte string for [id] without mutating the existing
 *  Map reference. No-op when the bytes are already present unchanged. */
export function setThumb(id: string, imageData: string): void {
    const prev = historyStore.getState().thumbCache;
    if (prev.get(id) === imageData) return;
    const next = new Map(prev);
    next.set(id, imageData);
    historyStore.setState({ thumbCache: next });
}
