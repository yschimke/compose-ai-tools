/**
 * rc-clock.mjs — the wall clock a Remote Compose parity capture is measured against.
 *
 * ## The bug this fixes (#4431)
 *
 * A Remote Compose document can animate with no animation attached to anything: `remote-m3`'s
 * indeterminate circular progress builds its sweep from a float expression over the player-supplied
 * `CONTINUOUS_SEC` (#4264), and the player loads that variable from the **wall clock** — seconds
 * into the local hour, re-read at the top of every frame (`RcPlayerState.loadSystemVariables`). So
 * the arc's pose in a capture is whatever the second hand happened to say, and the CMP/Wasm parity
 * row for it scored 0.05%–0.94% across five pull requests that could not reach the render path,
 * reporting improvements and regressions on every one of them against a 0.25 pp gate.
 *
 * Convergence cannot fix it: a rotating arc never stops moving, so `rc-settle.mjs` runs its full
 * timeout and hands back a frame from an arbitrary phase (it says as much — "a document that never
 * converges is legitimate, judging it is the caller's job"). This is the caller judging it.
 *
 * ## How it is fixed
 *
 * Playwright's `clock.setFixedTime` makes `Date.now()` and `new Date()` return a fixed instant while
 * **leaving every timer running** — `setTimeout`, `requestAnimationFrame` and `performance.now()`
 * are untouched, so the player still boots, still paces frames and still settles. The Kotlin/Wasm
 * player reads the wall clock through `Date.now()` (`kotlin.time.Clock.System`), so a page pinned
 * this way loads the same `CONTINUOUS_SEC` on every frame, the sweep holds one pose, and the
 * capture is reproducible run to run — the same shape as #3547's `PreviewClock`, which pinned the
 * Android preview wall clock for the same reason.
 *
 * A pinned clock also makes the capture *cheaper*: a document that used to burn the full 5 s settle
 * timeout now converges in a quiet window like every other row.
 *
 * ## Why this instant
 *
 * [PARITY_CLOCK_EPOCH_MS] is 2024-01-01T10:10:00Z — the same date and time-of-day
 * `renderers/android`'s `PreviewClock` pins Android renders to, so the two lanes tell the same time.
 * It is pinned as an absolute instant rather than a local time-of-day because what has to be
 * reproducible here is the *pixels*, not a formatted string, and an instant is the only form that
 * does not move with the runner's zone.
 *
 * The lane's browser contexts are also given
 * [PARITY_CLOCK_TIMEZONE] so the *calendar* is pinned alongside the instant. Without that, an
 * instant is only half a pin: a runner in a non-UTC zone reads the same moment as a different local
 * hour, and a document that paints `TIME_IN_HR`, a weekday or a formatted date renders something
 * the baked Android reference — which pins the local *time-of-day* — never drew. `CONTINUOUS_SEC`
 * happens to survive that (every IANA offset is a whole number of minutes, so the
 * second-into-the-hour barely moves), which is exactly why the zone has to be pinned deliberately
 * rather than left to the fixture that noticed.
 */

/**
 * The instant every browser-side parity capture is taken at: 2024-01-01T10:10:00Z.
 *
 * A literal rather than a computed `Date.UTC(...)` so the value is greppable and cannot drift.
 */
export const PARITY_CLOCK_EPOCH_MS = 1_704_103_800_000;

/** Human-readable form of [PARITY_CLOCK_EPOCH_MS], for logs and page headers. */
export const PARITY_CLOCK_ISO = "2024-01-01T10:10:00.000Z";

/**
 * The zone the lane's browser contexts run in, so [PARITY_CLOCK_EPOCH_MS] is read as the local
 * `2024-01-01 10:10` that `PreviewClock` renders the Android references at — on any machine, not
 * just a UTC runner. Passed as Playwright's `timezoneId` at context creation.
 */
export const PARITY_CLOCK_TIMEZONE = "UTC";

/**
 * Freeze `page`'s wall clock at [PARITY_CLOCK_EPOCH_MS].
 *
 * Must be called **before the page navigates**: Playwright installs the fake `Date` as an init
 * script, and a document that has already loaded keeps the real one.
 *
 * @param page a Playwright page
 * @param epochMillis the instant to pin to; overridable so a test can pin two pages differently
 */
export async function pinWallClock(page, epochMillis = PARITY_CLOCK_EPOCH_MS) {
  await page.clock.setFixedTime(epochMillis);
}
