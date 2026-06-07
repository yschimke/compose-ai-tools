// Shared Playwright drive for both specs: open a fixture, wait for the
// `<preview-app>` boot to land, then replay the fixture's `actions`
// through real locators.
//
// Locators are the upgrade over the old in-page `runAction` /
// `querySelector().click()` loop: the locator auto-waits for the element
// to attach before dispatching, so an action that targets a control the
// bundle hasn't mounted yet retries instead of throwing "selector matched
// no element" on the first frame. Failures carry Playwright's resolution
// diagnostics + a trace.
//
// We dispatch via `dispatchEvent("click")` rather than `locator.click()`:
// the panel's controls are programmatic toggles, and the fixtures'
// freshly-painted `fade-in` preview images overlap them, so a real
// pointer click trips Playwright's "another element intercepts pointer
// events" actionability guard. `dispatchEvent` fires the same `click` the
// bundle's handlers listen for — identical to the old in-page `el.click()`
// — without hit-testing the overlay.

/**
 * Navigate to the scenario page for a fixture/theme and wait until the
 * page-side boot flips `harness.booted` (bundle injected, fixture
 * messages + interactive-availability replayed, first settle done). The
 * `driver=playwright` flag tells `scenario.html` to stop after boot and
 * leave action replay to us, rather than running its own in-page loop.
 */
export async function gotoFixture(page, fixture, theme) {
    const url =
        `/preview-harness/scenario.html` +
        `?fixture=${encodeURIComponent(fixture)}` +
        `&theme=${encodeURIComponent(theme)}` +
        `&driver=playwright`;
    await page.goto(url);
    await page.waitForFunction(
        () => window.__composePreviewHarness?.booted === true,
        { timeout: 10_000 },
    );
}

/**
 * Replay the fixture's scripted `actions` via locators, settling DOM
 * after each. Today the only action is `{ click: "<selector>" }`.
 */
export async function replayActions(page, actions = []) {
    for (const action of actions) {
        if (action.click) {
            await page.locator(action.click).first().dispatchEvent("click");
            await settle(page);
            continue;
        }
        throw new Error("unknown action: " + JSON.stringify(action));
    }
}

/** Flush the page-side rAF + image-decode settle the bundle exposes. */
export async function settle(page) {
    await page.evaluate(() => window.__composePreviewHarness.settle());
}

/**
 * Attach console/pageerror forwarding so a broken bundle surfaces in the
 * test output (and the trace) instead of failing silently as a blank
 * screenshot. Mirrors the listeners the old standalone scripts wired up.
 */
export function wireDiagnostics(page, label) {
    page.on("pageerror", (err) =>
        console.error(`[${label}] pageerror:`, err.message),
    );
    page.on("console", (msg) => {
        if (msg.type() === "error") {
            console.error(`[${label}] console:`, msg.text());
        }
    });
}
