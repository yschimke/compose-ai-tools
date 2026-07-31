package ee.schimke.composeai.cli.serve

import java.util.concurrent.ConcurrentHashMap

/**
 * Stage-2 of the playground (`docs/design/PLAYGROUND.md` §2 + §5): redeem a `/pg/<token>`
 * capability into a **live, streamed, interactive** preview session, reusing the serve host's
 * existing live lane wholesale.
 *
 * Redemption is deliberately thin. A token already names a *compiled* snippet
 * ([PlaygroundTokenStore.PlaygroundSnippet] — classes on disk, resolved classpath, discovered
 * `@Preview`), so standing up its session is just:
 * 1. [materialize] the snippet into a resumable [ServeSessionState] — a `daemon-launch.json`
 *    descriptor over the snippet's own classes, with the backend (desktop CMP / Android
 *    Robolectric) and [ServeSessionState.liveSeatWeight] chosen by the token's mode;
 * 2. [ServeSessionRegistry.register] that state under `sessionId = token.id`;
 * 3. hand the browser the existing viewer at `/{token.id}/p/{previewId}` — its WebSocket
 *    (`/{token.id}/ws/{previewId}`), frame fan-out, `input` protocol, and **live-seat admission**
 *    (the registered state's `liveSeatWeight`) all work unchanged (see `ServeHttpServer`'s
 *    `serveStreamLane`).
 *
 * So there is **no new streaming/input protocol and no new WebSocket handler** — redemption is a
 * registry `register` + a redirect. [materialize] is injected so this orchestration (lookup, the
 * register-once gate, the fail-soft when no live backend exists, and releasing on token expiry) is
 * unit-testable without a real daemon; the production seam is
 * [ServeBundleDaemon.materializePlaygroundSnippet].
 *
 * Because a token is single-tenant and short-lived, the session it registers is released when the
 * token drops: wire [release] to [PlaygroundTokenStore]'s removal hook so an expired/evicted token
 * both deletes its work dir (the store) and unregisters + closes its live daemon (here).
 */
class PlaygroundRedeemService(
  private val tokenStore: PlaygroundTokenStore,
  private val registry: ServeSessionRegistry,
  /**
   * Materialize a compiled snippet into a resumable live-session state, or null when this host has
   * no live backend for the snippet's mode (e.g. the daemon sidecar / `android.jar` is absent) — in
   * which case redemption is a clean [Outcome.Unavailable] rather than a dead session.
   */
  private val materialize: (PlaygroundTokenStore.PlaygroundSnippet) -> ServeSessionState?,
) {

  /** The result of redeeming a `/pg/<token>` request. */
  sealed interface Outcome {
    /** The token is unknown or expired — answer a styled 404 that discloses neither. */
    data object NotFound : Outcome

    /** The token is live but this host can't stand up a live session (no daemon backend for it). */
    data object Unavailable : Outcome

    /**
     * Redeemed: a live session is registered under [sessionId]; send the browser to the viewer for
     * [previewId], whose WebSocket lane streams it.
     */
    data class Live(val sessionId: String, val previewId: String) : Outcome
  }

  /** Session ids this service registered, so a re-redeem within the TTL reuses the live session. */
  private val registered = ConcurrentHashMap.newKeySet<String>()

  /** Redeem [id] into (or back onto) its live session. Idempotent within the token's TTL. */
  fun redeem(id: String): Outcome {
    val token = tokenStore.get(id) ?: return Outcome.NotFound
    val previewId = token.snippet.previewId
    // `add` is the register-once gate: the winner materializes + registers; a concurrent (or later)
    // redeem of the same live token rides the session already standing.
    if (!registered.add(id)) return Outcome.Live(id, previewId)
    val state =
      materialize(token.snippet)
        ?: run {
          registered.remove(id)
          return Outcome.Unavailable
        }
    registry.register(id, state = state)
    return Outcome.Live(id, previewId)
  }

  /**
   * Release the live session a token owned — unregister it (closing its daemon). Wired to
   * [PlaygroundTokenStore]'s removal hook, so a token's expiry/eviction tears down its session. A
   * no-op for a token that was never redeemed into a session.
   */
  fun release(id: String) {
    if (registered.remove(id)) registry.unregister(id)
  }
}
