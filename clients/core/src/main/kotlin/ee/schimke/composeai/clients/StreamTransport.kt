package ee.schimke.composeai.clients

/**
 * The minimal WebSocket the [SessionClient] state machine drives, abstracted so the machine is
 * tested headlessly with a fake and runs over OkHttp ([OkHttpStreamTransport]) on device. One
 * transport instance models one connection attempt: [open] dials, [send] pushes a text frame, and
 * [close] tears down.
 *
 * Implementations report lifecycle + inbound text back through the [Listener] handed to [open]. All
 * listener callbacks may arrive on a transport-owned thread; [SessionClient] marshals them onto its
 * own state without assuming a dispatcher.
 */
interface StreamTransport {
  fun open(url: String, listener: Listener)

  /** Send one text frame. Returns false if the socket is already gone (caller need not retry). */
  fun send(text: String): Boolean

  /** Best-effort close. Idempotent. */
  fun close(code: Int = NORMAL_CLOSURE, reason: String = "client closed")

  interface Listener {
    fun onOpen()

    fun onText(text: String)

    /** Clean close (server-initiated or echoing our [close]). */
    fun onClosed(code: Int, reason: String)

    /** Abnormal termination — connect refused, dropped socket, handshake rejected. */
    fun onFailure(error: Throwable)
  }

  /** A factory so [SessionClient] can mint a fresh transport per connection attempt. */
  fun interface Factory {
    fun create(): StreamTransport
  }

  companion object {
    const val NORMAL_CLOSURE: Int = 1000
  }
}
