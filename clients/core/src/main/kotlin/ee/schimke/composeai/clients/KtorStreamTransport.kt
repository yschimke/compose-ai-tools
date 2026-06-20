package ee.schimke.composeai.clients

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The on-device [StreamTransport]: a Ktor WebSocket client over the OkHttp engine ("OkHttp via
 * Ktor"). Ktor gives the coroutine WS API; OkHttp is the engine, so one stack runs on the JVM and
 * Android. A bad token makes the serve route 404 the upgrade handshake, surfacing here as
 * [StreamTransport.Listener.onFailure] → [SessionState.Failed].
 *
 * The callback-shaped [StreamTransport] contract is bridged onto Ktor's suspending session: [open]
 * launches a reader coroutine that pumps inbound text frames to the listener; [send] hands a text
 * frame to the session's outgoing channel without suspending the caller.
 */
class KtorStreamTransport(
  private val client: HttpClient = sharedClient,
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : StreamTransport {

  @Volatile private var session: DefaultClientWebSocketSession? = null
  @Volatile private var job: Job? = null

  override fun open(url: String, listener: StreamTransport.Listener) {
    job = scope.launch {
      try {
        client.webSocket(urlString = url) {
          session = this
          listener.onOpen()
          for (frame in incoming) {
            if (frame is Frame.Text) listener.onText(frame.readText())
          }
          // `incoming` drained → the peer closed. Report the negotiated reason.
          val reason = runCatching { closeReason.await() }.getOrNull()
          listener.onClosed(
            reason?.code?.toInt() ?: StreamTransport.NORMAL_CLOSURE,
            reason?.message.orEmpty(),
          )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        listener.onFailure(e)
      } finally {
        session = null
      }
    }
  }

  override fun send(text: String): Boolean {
    val s = session ?: return false
    return s.outgoing.trySend(Frame.Text(text)).isSuccess
  }

  override fun close(code: Int, reason: String) {
    val s = session
    session = null
    if (s != null) {
      scope.launch { runCatching { s.close(CloseReason(code.toShort(), reason)) } }
    }
    job?.cancel()
  }

  companion object {
    /** A lazily-built shared client with the WebSockets plugin + a keep-alive ping. */
    private val sharedClient: HttpClient by lazy {
      HttpClient(OkHttp) { install(WebSockets) { pingIntervalMillis = 20_000 } }
    }

    /** The default factory the apps hand to [SessionClient]. */
    val factory: StreamTransport.Factory = StreamTransport.Factory { KtorStreamTransport() }
  }
}
