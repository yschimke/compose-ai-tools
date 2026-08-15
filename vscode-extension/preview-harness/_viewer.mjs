// Shared viewer-page helpers for the harness suites (`pages-snapshot`, `serve-lanes`).

/**
 * Open the viewer's controls drawer — the right-hand column holding the Overrides knobs and every
 * `details.cp-group` (Size, Locale, Overlays, Exploded 3D, …).
 *
 * It is CLOSED on load since #3893: `ServeWeb` emits `<div class="cp-viewer" …>` where it used to
 * emit `<div class="cp-viewer cp-controls-open" …>`, and `serve.css` has
 * `.cp-viewer:not(.cp-controls-open) .cp-controls { display: none; }`. Everything inside is
 * therefore `display: none` until something opens it — which is why a locator resolves to the
 * element and Playwright still reports `element is not visible`. Opening a `details` inside the
 * drawer does not help: the drawer is the outer thing that is hidden.
 *
 * Clicks the real toggle rather than poking the class on, because `viewer-drawers.js` does more
 * than flip it — `aria-expanded`, the mobile scrim, and the per-catalog fold preference all move
 * together. A shot taken after a class poke would show an open drawer above a toggle still
 * claiming to be closed, which is a state no visitor can produce.
 *
 * Idempotent, because that toggle is a toggle: clicking an already-open drawer closes it.
 */
export async function openControlsDrawer(page) {
    const viewer = page.locator(".cp-viewer");
    if (!(await viewer.count())) return;
    const open = await viewer
        .first()
        .evaluate((v) => v.classList.contains("cp-controls-open"));
    if (open) return;
    await page.click("#cp-controls-toggle");
    await page.waitForSelector(".cp-viewer.cp-controls-open");
}
