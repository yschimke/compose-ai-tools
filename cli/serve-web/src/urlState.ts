// Typed view of the `window.cpUrlState` global that `url-state.js` publishes.
//
// NOT a port of that file. Every legacy enhancement script (`viewer.js`,
// `catalog-live.js`, the inline `catalogFilterScript`, …) reads the global at
// IIFE time, so whoever owns it has to be loaded and evaluated before all of
// them. Moving ownership into this bundle would make that load order a property
// of two build systems instead of one line in `ServeWeb.document`, and would do
// it in the same change that introduces the toolchain. `url-state.js` keeps
// owning the global; this wrapper just gives the Lit side types and a null-safe
// handle for the window where the global is genuinely absent (a page that
// declares no URL-owned state does not load it).
//
// Port it once more than one component here needs it — at that point the
// ordering question is worth answering properly, and `url-state.js` becomes a
// re-export shim rather than the source.

export interface UrlState {
    /** `""` for an absent param, so missing and empty read the same. */
    get(name: string): string;
    /** Discrete choice — earns its own history entry. */
    push(values: Record<string, string | null | undefined>): void;
    /** Continuous input — collapses into the current entry. */
    replace(values: Record<string, string | null | undefined>): void;
    sync(
        values: Record<string, string | null | undefined>,
        owned: (name: string) => boolean,
        replace: boolean,
    ): void;
    onPop(callback: () => void): void;
}

declare global {
    interface Window {
        cpUrlState?: UrlState;
    }
}

/** The page's URL-state handle, or `null` on a page that never loaded it. */
export function urlState(): UrlState | null {
    return window.cpUrlState ?? null;
}
