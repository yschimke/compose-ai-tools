package ee.schimke.composeai.clients

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives one streamed-frame session end to end: dial a [SessionLink]'s WebSocket lane, surface the
 * connection [state] and the latest [frame] as flows the UI paints, and forward pointer/key
 * [send]/overrides back to the server. This is the whole "present the service as if it's a complete
 * app, forwarding events and showing updates" engine — the Android/Wear shells add only a Compose
 * canvas and touch handling on top.
 *
 * Transport-agnostic ([StreamTransport.Factory]) so it's unit-tested with a fake socket and runs
 * over OkHttp on device. Thread-safe: transport callbacks land on a transport-owned thread and are
 * folded into thread-safe `StateFlow`s; the public methods are safe to call from any thread.
 */
class SessionClient(private val transportFactory: StreamTransport.Factory) {

  private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
  val state: StateFlow<SessionState> = _state.asStateFlow()

  /** Newest frame to paint, or null before the first one. Replaces in place (newest-wins). */
  private val _frame = MutableStateFlow<StreamFrame?>(null)
  val frame: StateFlow<StreamFrame?> = _frame.asStateFlow()

  /** Non-fatal server `error` messages (bad override, render failure) — the lane stays open. */
  private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 16)
  val errors: SharedFlow<String> = _errors.asSharedFlow()

  private val lastSeq = AtomicLong(Long.MIN_VALUE)

  @Volatile private var transport: StreamTransport? = null
  @Volatile private var current: SessionLink? = null

  /**
   * Connect to [link]. Tears down any existing connection first, so a tapped link always replaces
   * the live session. Returns immediately; observe [state] for progress.
   */
  fun connect(link: SessionLink) {
    closeTransport(StreamTransport.NORMAL_CLOSURE, "switching session")
    lastSeq.set(Long.MIN_VALUE)
    _frame.value = null
    current = link
    _state.value = SessionState.Connecting(link)
    val t = transportFactory.create()
    transport = t
    t.open(link.webSocketUrl(), TransportCallbacks(link))
  }

  /** Forward one pointer/key event into the held composition. No-op unless connected. */
  fun send(event: InputEvent): Boolean = sendText(StreamMessages.input(event))

  /** Replace the display overrides (device, theme, font scale, …) and pull a fresh frame. */
  fun setOverrides(overrides: Map<String, String>): Boolean =
    sendText(StreamMessages.setOverrides(overrides))

  /** Ask the server to re-render and push a frame at the current overrides. */
  fun requestFrame(): Boolean = sendText(StreamMessages.requestFrame())

  /** Close the session. Idempotent; moves [state] to [SessionState.Closed]. */
  fun close(reason: String = "client closed") {
    closeTransport(StreamTransport.NORMAL_CLOSURE, reason)
    current = null
    _state.value = SessionState.Closed(reason)
  }

  private fun sendText(text: String): Boolean {
    if (_state.value !is SessionState.Connected) return false
    return transport?.send(text) ?: false
  }

  private fun closeTransport(code: Int, reason: String) {
    transport?.let {
      transport = null
      runCatching { it.close(code, reason) }
    }
  }

  private inner class TransportCallbacks(private val link: SessionLink) : StreamTransport.Listener {
    override fun onOpen() {
      // Guard against a stale transport reconnecting after the user moved on.
      if (current != link) return
      _state.value = SessionState.Connected(link)
      // Prime the lane: the spike only pushes on demand, so ask for the first paint immediately.
      requestFrame()
    }

    override fun onText(text: String) {
      if (current != link) return
      when (val msg = StreamMessages.parseServer(text)) {
        is StreamMessages.ServerMessage.Frame -> acceptFrame(msg.frame)
        is StreamMessages.ServerMessage.Error -> _errors.tryEmit(msg.message)
        is StreamMessages.ServerMessage.Unknown -> Unit // forward-compat: ignore quietly
      }
    }

    override fun onClosed(code: Int, reason: String) {
      if (current != link) return
      _state.value = SessionState.Closed(reason.ifBlank { "server closed (code $code)" })
    }

    override fun onFailure(error: Throwable) {
      if (current != link) return
      _state.value = SessionState.Failed(error.message ?: "connection failed", error)
    }
  }

  /** Apply newest-wins seq dedup: drop a frame older than one already painted. */
  private fun acceptFrame(frame: StreamFrame) {
    while (true) {
      val prev = lastSeq.get()
      if (frame.seq < prev) return
      if (lastSeq.compareAndSet(prev, frame.seq)) break
    }
    _frame.value = frame
  }
}
