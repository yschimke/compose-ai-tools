// The rules behind `<cp-viewer-drawers>`, as pure functions over plain flags.
//
// Separated from the element for the same reason `zoom/viewport.ts` is: every decision the drawers
// make is a small piece of arithmetic over three inputs — the viewport band, what the visitor
// stored, and what the server's markup implies — and none of it needs a DOM to be right or wrong.
// Held in the element it would only be reachable through a browser, which is exactly how the
// viewer arrived at a state where a drawer's default could change and nothing failed until a page
// capture noticed weeks later.
//
// The three bands matter and are not the same question:
//
//   phone   (≤ 640px)  drawers are MODAL bottom sheets over the preview
//   middle  (641–1099) inline columns, nav hidden by default
//   wide    (≥ 1100px) inline columns, nav shown by default
//
// so "is the nav open" has a different answer in each, and `isWide` is not `!isMobile`.

/** Which viewport band the page is in. Both false is the middle band. */
export interface Viewport {
    /** `(max-width: 640px)` — drawers are bottom sheets here. */
    mobile: boolean;
    /** `(min-width: 1100px)` — the component list is shown by default here. */
    wide: boolean;
}

/** A stored fold preference: `"1"` open, `"0"` closed, `null` never expressed. */
export type FoldPref = string | null;

/**
 * The component nav's resting state.
 *
 * On a phone the answer is always CLOSED, whatever a desktop visit stored: an open bottom sheet is
 * a modal over the preview, never a resting state to restore. Off the phone a stored choice wins,
 * and with none the CSS default applies — shown wide, hidden in the middle band.
 *
 * This has to be resolved into an explicit class rather than left to CSS, because the server's
 * markup carries neither class: `classList.contains("cp-nav-open")` would read the wide band's
 * default as "closed" on the very width where it is open, and the toggle would be inert there.
 */
export function resolveNavOpen(viewport: Viewport, pref: FoldPref): boolean {
    if (viewport.mobile) return false;
    if (pref !== null) return pref === "1";
    return viewport.wide;
}

/**
 * The overrides drawer's resting state.
 *
 * Closed on a phone so the preview leads — the toggle row keeps it one tap away as a sheet, and
 * restoring a stored preference there would put a sheet back over the render, which is the rule
 * this breakpoint exists to state. Off the phone a stored choice wins; with none, `serverDefault`
 * stands, which is whatever `cp-controls-open` the markup shipped with.
 */
export function resolveControlsOpen(
    viewport: Viewport,
    pref: FoldPref,
    serverDefault: boolean,
): boolean {
    if (viewport.mobile) return false;
    if (pref !== null) return pref === "1";
    return serverDefault;
}

/**
 * Whether a drawer toggle should be remembered.
 *
 * A phone stores NOTHING about the drawers. Both are modal sheets there — opened for one thing and
 * dismissed — so remembering one open would restore the sheet on the next page, and every
 * component you picked would arrive covered and need dismissing. The sheets also close each other
 * on a phone, which would store a state the visitor never chose. The in-page folds are ordinary
 * rows rather than sheets, and keep their memory at every width.
 */
export function shouldPersistDrawer(viewport: Viewport): boolean {
    return !viewport.mobile;
}

/**
 * Opening one sheet closes the other, on a phone only, so they never stack over the preview.
 * Returns the class to close, or null when both may stay open (any width above the phone).
 */
export function drawerToClose(
    viewport: Viewport,
    opening: DrawerClass,
): DrawerClass | null {
    if (!viewport.mobile) return null;
    return opening === "cp-nav-open" ? "cp-controls-open" : "cp-nav-open";
}

/** The two drawer state classes, as they appear on `.cp-viewer`. */
export type DrawerClass = "cp-nav-open" | "cp-controls-open";

/** The toggle button that drives each drawer. */
export function toggleIdFor(drawer: DrawerClass): string {
    return drawer === "cp-nav-open" ? "cp-nav-toggle" : "cp-controls-toggle";
}

/**
 * Per-CATALOG storage key, the way `cp-theme:<catalog>` and `cp-tab:<catalog>` already are.
 * `localStorage` is per-origin and one host serves many catalogs under different base paths, so an
 * unscoped key would let folding this catalog's thirty-state axis fold a normally-inline axis on
 * every unrelated catalog beside it.
 */
export function foldKey(scope: string, id: string): string {
    return `cp-fold:${scope}.${id}`;
}
