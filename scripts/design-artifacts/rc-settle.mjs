/**
 * rc-settle.mjs — capture a page once its pixels have stopped moving.
 *
 * The CMP/Wasm player's `ready` marker means "three frames have gone through", which is a
 * *liveness* signal rather than a settlement one: Compose resolves the host font faces
 * asynchronously, so a text-bearing document draws once in a fallback face and again in the real
 * one, and the second draw can land after the third frame. The player's 1.5 s handoff tail used to
 * hide that; the parity lane dropped the tail for speed (#3466) and started capturing the
 * intermediate draw — worth ~2–4% mismatch on a button-with-a-label row and nothing at all on a row
 * with no text.
 *
 * Waiting for the pixels to converge asks the question the sleep only assumed an answer to, and it
 * costs each document what it actually needs. A screenshot drives its own compositor frame, so the
 * loop makes progress on its own rather than waiting on `requestAnimationFrame` pacing.
 */

/**
 * Screenshot `page` until it stops changing, and report how long that took.
 *
 * The stop condition is a quiet *window*, not a single repeated capture: font resolution settles in
 * stages, and two captures ~30 ms apart can agree on an intermediate one. Measured over the
 * 27-document `remote-m3` corpus, a single repeat left the render run-dependent; a 250 ms window
 * still left 4 of 27 rows (the variable-font ones) landing on either of two stable draws from run to
 * run; at 500 ms two full runs came back **byte-for-byte identical on all 27**, matching what the
 * player's own 1,500 ms handoff tail produces. That reproducibility is the point — a parity lane
 * whose pixels depend on the run cannot tell a regression from noise — so the default buys it rather
 * than the last few seconds of wall clock. A redraw inside the window resets it.
 *
 * A document that never converges — an animation — is legitimate, so `timeoutMs` returns the last
 * capture rather than throwing: judging it is the caller's job, and the parity lane has a baked
 * reference to judge it against.
 *
 * @param page a Playwright page, or anything with a compatible `screenshot()` returning a Buffer
 * @returns `{ buffer, settleMs, settled }` — `settled` is false when `timeoutMs` cut it short
 */
export async function settledScreenshot(page, options = {}) {
  const {
    quietMs = 500,
    timeoutMs = 5_000,
    screenshotOptions = { omitBackground: true },
    now = () => performance.now(),
  } = options;
  const startedAt = now();
  let previous = await page.screenshot(screenshotOptions);
  let lastChangedAt = now();
  for (;;) {
    const next = await page.screenshot(screenshotOptions);
    if (next.equals(previous)) {
      if (now() - lastChangedAt >= quietMs) {
        return { buffer: next, settleMs: now() - startedAt, settled: true };
      }
    } else {
      lastChangedAt = now();
    }
    previous = next;
    if (now() - startedAt > timeoutMs) {
      return { buffer: previous, settleMs: now() - startedAt, settled: false };
    }
  }
}
