package ee.schimke.composeai.cli.serve

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

  /**
   * Ids whose session is **fully registered** — added only *after* [ServeSessionRegistry.register]
   * returns, so a second redeem never reports `Live` before the viewer's session actually exists.
   * Mutated only under [lock].
   */
  private val registered = HashSet<String>()

  /**
   * Serialises redeem/release **per service** so token expiry can't race a registration: a redeem
   * that materializes while an expiry fires either wins the lock and registers, or the release wins
   * and there's nothing to tear down. Redemption is a deliberate, rate-limited act (and the daemon
   * boot happens later, in the registry lease — not here), so one coarse lock is ample.
   */
  private val lock = Any()

  /**
   * Redeem [id] into (or back onto) its live session. Idempotent within the token's TTL.
   *
   * [preview] opens the session on a specific `@Preview` instead of the snippet's first — the
   * editor offers one link per discovered preview, and the redeemed session lists them all. It is
   * validated against the snippet's own set rather than trusted: an id the snippet never declared
   * would resolve to a viewer route the daemon cannot serve, so an unknown one falls back to the
   * first exactly as if it had been omitted.
   */
  /**
   * Whether [id] names a session **this playground** registered by redeeming a token.
   *
   * Read by [ServeHttpServer]'s top-level-site interceptor, which otherwise treats every registered
   * session that is not the site's catalog as a neighbour to 404. A redeemed session is neither: it
   * is the visitor's own just-compiled snippet, minted seconds earlier by this host's playground
   * under an unguessable token id, and the redirect that reaches it (`/<id>/p/<preview>`) is the
   * last step of the run the visitor started. Refusing it would leave "open live preview" dying
   * after every successful compile on a site hostname.
   */
  fun isRedeemedSession(id: String): Boolean = synchronized(lock) { id in registered }

  fun redeem(id: String, preview: String? = null): Outcome {
    val snippet = (tokenStore.get(id) ?: return Outcome.NotFound).snippet
    val previewId = preview?.takeIf { it in snippet.previewIds } ?: snippet.previewId
    synchronized(lock) {
      if (id in registered) return Outcome.Live(id, previewId)
      // Re-check under the lock: the token may have expired/been evicted between the get above and
      // here. If so, release() has already run (a no-op — we hadn't registered yet), and
      // registering
      // now would strand a session whose work dir is gone and which the token hook can never
      // release.
      val token = tokenStore.get(id) ?: return Outcome.NotFound
      val state = materialize(token.snippet) ?: return Outcome.Unavailable
      registry.register(id, state = state)
      // Mark registered ONLY now: a concurrent redeem that got here first is still holding the
      // lock,
      // so no one observes `Live` until the registry entry the viewer resolves actually exists.
      registered.add(id)
      return Outcome.Live(id, previewId)
    }
  }

  /**
   * Release the live session a token owned — unregister it (closing its daemon). Wired to
   * [PlaygroundTokenStore]'s removal hook, so a token's expiry/eviction tears down its session. A
   * no-op for a token that was never redeemed into a session. Takes [lock] so it can't interleave
   * with a redeem mid-registration.
   */
  fun release(id: String) {
    synchronized(lock) { if (registered.remove(id)) registry.unregister(id) }
  }
}
