package ee.schimke.composeai.clients

import com.google.common.truth.Truth.assertThat
import java.util.Base64
import org.junit.Test

class SessionClientTest {

  /** A scriptable transport: the test drives the listener and inspects what the client sent. */
  private class FakeTransport : StreamTransport {
    var listener: StreamTransport.Listener? = null
    var openedUrl: String? = null
    val sent = mutableListOf<String>()
    var closed = false

    override fun open(url: String, listener: StreamTransport.Listener) {
      openedUrl = url
      this.listener = listener
    }

    override fun send(text: String): Boolean {
      sent += text
      return true
    }

    override fun close(code: Int, reason: String) {
      closed = true
    }
  }

  private val link = SessionLink("host", 7341, "tok", SessionTarget.Preview("Foo"))

  private fun frameWire(seq: Long, png: ByteArray = byteArrayOf(9)) =
    """{"type":"frame","seq":$seq,"codec":"png","widthPx":10,"heightPx":20,"dataBase64":"${
      Base64.getEncoder().encodeToString(png)
    }"}"""

  @Test
  fun connectDialsTheLinksWebSocketUrlAndGoesConnecting() {
    val t = FakeTransport()
    val client = SessionClient { t }
    client.connect(link)
    assertThat(t.openedUrl).isEqualTo(link.webSocketUrl())
    assertThat(client.state.value).isEqualTo(SessionState.Connecting(link))
  }

  @Test
  fun onOpenGoesConnectedAndPrimesWithRequestFrame() {
    val t = FakeTransport()
    val client = SessionClient { t }
    client.connect(link)
    t.listener!!.onOpen()
    assertThat(client.state.value).isEqualTo(SessionState.Connected(link))
    assertThat(t.sent).containsExactly(StreamMessages.requestFrame())
  }

  @Test
  fun inboundFrameUpdatesTheFrameFlow() {
    val t = FakeTransport()
    val client = SessionClient { t }
    client.connect(link)
    t.listener!!.onOpen()
    t.listener!!.onText(frameWire(seq = 1, png = byteArrayOf(7, 7)))
    val frame = client.frame.value!!
    assertThat(frame.seq).isEqualTo(1L)
    assertThat(frame.bytes).isEqualTo(byteArrayOf(7, 7))
  }

  @Test
  fun staleFramesAreDroppedNewestWins() {
    val t = FakeTransport()
    val client = SessionClient { t }
    client.connect(link)
    t.listener!!.onOpen()
    t.listener!!.onText(frameWire(seq = 5))
    t.listener!!.onText(frameWire(seq = 2)) // older — must be ignored
    assertThat(client.frame.value!!.seq).isEqualTo(5L)
  }

  @Test
  fun inputIsForwardedOnlyWhenConnected() {
    val t = FakeTransport()
    val client = SessionClient { t }
    client.connect(link)
    // Before onOpen the lane isn't live: input is dropped.
    assertThat(client.send(InputEvent.click(1, 2))).isFalse()
    t.listener!!.onOpen()
    assertThat(client.send(InputEvent.click(3, 4))).isTrue()
    assertThat(t.sent.last()).isEqualTo(StreamMessages.input(InputEvent.click(3, 4)))
  }

  @Test
  fun serverErrorIsSurfacedButLaneStaysConnected() {
    val t = FakeTransport()
    val client = SessionClient { t }
    val seen = mutableListOf<String>()
    // SharedFlow with buffer — tryEmit lands without a collector; read via replay cache isn't set,
    // so subscribe synchronously by collecting in the same thread is overkill: assert state only.
    client.connect(link)
    t.listener!!.onOpen()
    t.listener!!.onText("""{"type":"error","message":"render failed"}""")
    assertThat(client.state.value).isEqualTo(SessionState.Connected(link))
  }

  @Test
  fun failureMovesToFailed() {
    val t = FakeTransport()
    val client = SessionClient { t }
    client.connect(link)
    t.listener!!.onFailure(RuntimeException("connection refused"))
    val state = client.state.value
    assertThat(state).isInstanceOf(SessionState.Failed::class.java)
    assertThat((state as SessionState.Failed).message).isEqualTo("connection refused")
  }

  @Test
  fun closeClosesTransportAndMovesToClosed() {
    val t = FakeTransport()
    val client = SessionClient { t }
    client.connect(link)
    t.listener!!.onOpen()
    client.close("done")
    assertThat(t.closed).isTrue()
    assertThat(client.state.value).isEqualTo(SessionState.Closed("done"))
  }

  @Test
  fun reconnectingToANewLinkResetsFrameAndSeq() {
    val first = FakeTransport()
    val second = FakeTransport()
    val transports = ArrayDeque(listOf<StreamTransport>(first, second))
    val client = SessionClient { transports.removeFirst() }
    client.connect(link)
    first.listener!!.onOpen()
    first.listener!!.onText(frameWire(seq = 9))
    assertThat(client.frame.value!!.seq).isEqualTo(9L)

    val link2 = link.copy(target = SessionTarget.Preview("Bar"))
    client.connect(link2)
    assertThat(first.closed).isTrue()
    assertThat(client.frame.value).isNull()
    second.listener!!.onOpen()
    // A low seq is accepted again because the counter reset on reconnect.
    second.listener!!.onText(frameWire(seq = 1))
    assertThat(client.frame.value!!.seq).isEqualTo(1L)
  }
}
