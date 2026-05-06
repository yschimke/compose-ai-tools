// Pure predicates over the per-module daemon readiness Maps. Lifted out
// of `focusToolbar.ts` so they can be unit-tested under the host
// tsconfig — `focusToolbar.ts` itself holds the DOM-bound
// `FocusToolbarController` class and so can't be compiled there.

/**
 * Daemon-ready predicate for the focused module. The webview doesn't
 * know moduleId per preview today; we fall back to "any module ready"
 * because the extension-side panel is single-module-scoped (it only
 * ever holds one module's previews at a time) — so the readiness of
 * the sole module is also the readiness for whatever's focused. If the
 * panel ever shows multiple modules at once, swap this to lookup by
 * the focused card's `data-module-id`.
 */
export function isFocusedModuleReady(
    moduleDaemonReady: ReadonlyMap<string, boolean>,
): boolean {
    for (const ready of moduleDaemonReady.values()) {
        if (ready) return true;
    }
    return false;
}

/**
 * Whether the focused module supports full v2 live mode (with preserved
 * Compose state) vs the v1 fallback where pointer events round-trip
 * through the daemon but renders refresh from scratch. Same module-
 * scoping caveat as [isFocusedModuleReady]: returns true when *any*
 * module is both ready and supports interactive mode.
 */
export function isFocusedInteractiveSupported(
    moduleDaemonReady: ReadonlyMap<string, boolean>,
    moduleInteractiveSupported: ReadonlyMap<string, boolean>,
): boolean {
    for (const [moduleId, ready] of moduleDaemonReady.entries()) {
        if (ready && moduleInteractiveSupported.get(moduleId) === true)
            return true;
    }
    return false;
}
