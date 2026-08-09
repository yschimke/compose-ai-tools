/**
 * rc-settle.mjs — capture a page once its pixels have stopped moving *and* the frame is one worth
 * scoring.
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
 *
 * ## Convergence alone is not enough, and #3558 is why
 *
 * A quiet window says the page stopped changing. It does not say the page ever *started*: a
 * document whose text has not resolved yet paints its shapes and nothing else, and a frame that is
 * blank now and blank in 500 ms satisfies every convergence test there is. That is what the
 * CMP/Wasm parity check was scoring — the five text-only documents in the `remote-m3` corpus
 * captured with **no text at all**, at a mismatch that is stable, reproducible, and completely
 * wrong. Which documents landed that way depended on where they sat in the run and on how fast the
 * machine was, so the same commit scored `VariableWidthRemote` at 0.19% or 2.45% and the advisory
 * check reported improvements and regressions on PRs that changed nothing a renderer can see.
 *
 * So the caller can hand in an [expectation]: a predicate the settled frame has to satisfy before
 * the loop will accept it. The parity lane's is "there is ink here", asked only of documents whose
 * baked reference has ink — a claim the driver can make from data it already has, rather than a
 * sleep tuned to whichever machine happened to run the measurement.
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
 * `expectation`, when given, is asked of the converged frame and can veto it; the loop then waits
 * another window and asks again, until `timeoutMs`. It is checked only at that point rather than per
 * capture because a predicate worth writing has to decode the PNG, and this loop takes tens of
 * screenshots per document.
 *
 * A document that never converges — an animation — is legitimate, so `timeoutMs` returns the last
 * capture rather than throwing: judging it is the caller's job, and the parity lane has a baked
 * reference to judge it against. A frame the expectation rejected right up to the timeout comes
 * back the same way, `settled: false`, so a caller that cares can refuse to score it.
 *
 * @param page a Playwright page, or anything with a compatible `screenshot()` returning a Buffer
 * @returns `{ buffer, settleMs, settled }` — `settled` is false when `timeoutMs` cut it short
 */
export async function settledScreenshot(page, options = {}) {
  const {
    quietMs = 500,
    timeoutMs = 5_000,
    screenshotOptions = { omitBackground: true },
    expectation = null,
    now = () => performance.now(),
  } = options;
  const startedAt = now();
  let previous = await page.screenshot(screenshotOptions);
  let lastChangedAt = now();
  for (;;) {
    const next = await page.screenshot(screenshotOptions);
    if (next.equals(previous)) {
      if (now() - lastChangedAt >= quietMs) {
        if (!expectation || expectation(next)) {
          return { buffer: next, settleMs: now() - startedAt, settled: true };
        }
        // Converged on a frame the caller will not accept. Give the page another full window
        // before asking again — the work being waited on is a font parse or a layout pass, not
        // something a tighter poll would find sooner, and re-decoding every capture is the one
        // thing that would slow it down.
        lastChangedAt = now();
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
