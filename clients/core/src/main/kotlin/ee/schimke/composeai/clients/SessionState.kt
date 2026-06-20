package ee.schimke.composeai.clients

/**
 * The connection lifecycle a [SessionClient] moves through, surfaced as a `StateFlow` the app's UI
 * observes to draw connecting / live / error chrome over the streamed frames.
 */
sealed interface SessionState {
  /** Before [SessionClient.connect] — nothing wired up yet. */
  data object Idle : SessionState

  /** Dialing the WebSocket; no frame painted yet. */
  data class Connecting(val link: SessionLink) : SessionState

  /** Handshake done, the lane is open. The first frame may not have arrived yet. */
  data class Connected(val link: SessionLink) : SessionState

  /**
   * The lane closed cleanly (server `stop`, or the app called [SessionClient.close]). Terminal
   * unless the app reconnects.
   */
  data class Closed(val reason: String) : SessionState

  /**
   * The lane failed — transport error, rejected handshake (bad token → the serve route 404s the
   * upgrade), or a fatal protocol problem. [message] is user-facing; [cause] aids logs.
   */
  data class Failed(val message: String, val cause: Throwable? = null) : SessionState

  val isLive: Boolean
    get() = this is Connected
}
