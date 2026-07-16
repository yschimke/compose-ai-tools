package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.StreamCodec
import java.io.File
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ServeLiveSessionTest {

  private val previewId = "com.example.Red"

  private fun newRenderRoot(): File =
    java.nio.file.Files.createTempDirectory("serve-live").toFile().also { it.deleteOnExit() }

  private fun host(session: FakeRenderSession): ServeRenderHost =
    ServeRenderHost(session, listOf(ServePreview(previewId, "Red")), renderTimeoutSeconds = 30)

  private fun typeOf(text: String): String =
    Json.parseToJsonElement(text).jsonObject.getValue("type").jsonPrimitive.content

  @Test
  fun `tryStart returns null when streaming is unsupported`() {
    val session = FakeRenderSession(newRenderRoot()) // streaming = false
    host(session).use { h ->
      assertNull(ServeLiveSession.tryStart(h, previewId, emptyMap(), send = {}))
    }
  }

  @Test
  fun `tryStart forwards the daemon's original failure to onUnavailable`() {
    val session = FakeRenderSession(newRenderRoot()) // streaming = false → streamStart throws
    host(session).use { h ->
      var reason: String? = null
      assertNull(
        ServeLiveSession.tryStart(
          h,
          previewId,
          emptyMap(),
          send = {},
          onUnavailable = { reason = it },
        )
      )
      // The daemon's own exception message is carried through, not swallowed into a log.
      assertEquals("streaming not supported", reason)
    }
  }

  @Test
  fun `tryStart forwards a no-held-session reason to onUnavailable`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true, heldSession = false)
    host(session).use { h ->
      var reason: String? = null
      assertNull(
        ServeLiveSession.tryStart(
          h,
          previewId,
          emptyMap(),
          send = {},
          onUnavailable = { reason = it },
        )
      )
      assertTrue(
        reason?.contains("could not hold an interactive session") == true,
        "expected a held-session reason, got: $reason",
      )
    }
  }

  @Test
  fun `daemon-pushed frames are forwarded as frame messages`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      val sent = CopyOnWriteArrayList<String>()
      assertNotNull(ServeLiveSession.tryStart(h, previewId, emptyMap(), send = sent::add))
      val fsid = assertNotNull(session.lastFrameStreamId)
      val payload = Base64.getEncoder().encodeToString("xy".toByteArray())

      session.emitStreamFrame(fsid, seq = 5, payloadBase64 = payload)

      assertEquals(1, sent.size)
      val obj = Json.parseToJsonElement(sent[0]).jsonObject
      assertEquals("frame", obj.getValue("type").jsonPrimitive.content)
      assertEquals("5", obj.getValue("seq").jsonPrimitive.content)
      assertEquals(payload, obj.getValue("dataBase64").jsonPrimitive.content)
    }
  }

  @Test
  fun `requested codec is forwarded to stream start and webp frames keep their codec`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      val sent = CopyOnWriteArrayList<String>()
      assertNotNull(
        ServeLiveSession.tryStart(h, previewId, emptyMap(), StreamCodec.WEBP, null, sent::add)
      )
      assertEquals(StreamCodec.WEBP, session.lastCodec)
      session.emitStreamFrame(
        assertNotNull(session.lastFrameStreamId),
        seq = 1,
        payloadBase64 = "AA",
        codec = StreamCodec.WEBP,
      )
      val obj = Json.parseToJsonElement(sent.last()).jsonObject
      assertEquals("webp", obj.getValue("codec").jsonPrimitive.content)
    }
  }

  @Test
  fun `unchanged heartbeat frames (no payload) are not forwarded`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      val sent = CopyOnWriteArrayList<String>()
      assertNotNull(ServeLiveSession.tryStart(h, previewId, emptyMap(), send = sent::add))
      session.emitStreamFrame(
        assertNotNull(session.lastFrameStreamId),
        seq = 0,
        payloadBase64 = null,
      )
      assertTrue(sent.isEmpty())
    }
  }

  @Test
  fun `input messages dispatch interactive input`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      val live = assertNotNull(ServeLiveSession.tryStart(h, previewId, emptyMap(), send = {}))
      live.onClientMessage("""{"type":"input","kind":"click","pixelX":10,"pixelY":20}""")
      assertEquals(1, session.interactiveInputs.size)
      val input = session.interactiveInputs[0]
      assertEquals(InteractiveInputKind.CLICK, input.kind)
      assertEquals(10, input.pixelX)
      assertEquals(20, input.pixelY)
    }
  }

  @Test
  fun `pointer drag, scroll and key inputs are forwarded with their fields`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      val live = assertNotNull(ServeLiveSession.tryStart(h, previewId, emptyMap(), send = {}))
      live.onClientMessage(
        """{"type":"input","kind":"pointerDown","pixelX":3,"pixelY":4,"pointerId":1}"""
      )
      live.onClientMessage(
        """{"type":"input","kind":"pointerMove","pixelX":7,"pixelY":9,"pointerId":1}"""
      )
      live.onClientMessage(
        """{"type":"input","kind":"pointerUp","pixelX":7,"pixelY":9,"pointerId":1}"""
      )
      live.onClientMessage("""{"type":"input","kind":"rotaryScroll","scrollDeltaY":-12.5}""")
      live.onClientMessage("""{"type":"input","kind":"keyDown","keyCode":"66"}""")

      val kinds = session.interactiveInputs.map { it.kind }
      assertEquals(
        listOf(
          InteractiveInputKind.POINTER_DOWN,
          InteractiveInputKind.POINTER_MOVE,
          InteractiveInputKind.POINTER_UP,
          InteractiveInputKind.ROTARY_SCROLL,
          InteractiveInputKind.KEY_DOWN,
        ),
        kinds,
      )
      assertEquals(1, session.interactiveInputs[0].pointerId)
      assertEquals(-12.5f, session.interactiveInputs[3].scrollDeltaY)
      assertEquals("66", session.interactiveInputs[4].keyCode)
    }
  }

  @Test
  fun `an unknown input kind yields an error and dispatches nothing`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      val sent = CopyOnWriteArrayList<String>()
      val live =
        assertNotNull(ServeLiveSession.tryStart(h, previewId, emptyMap(), send = sent::add))
      live.onClientMessage("""{"type":"input","kind":"telepathy"}""")
      assertEquals("error", typeOf(sent.last()))
      assertTrue(session.interactiveInputs.isEmpty())
    }
  }

  @Test
  fun `setOverrides restarts the held stream`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      val live = assertNotNull(ServeLiveSession.tryStart(h, previewId, emptyMap(), send = {}))
      val first = assertNotNull(session.lastFrameStreamId)
      assertEquals(1, session.streamStarts.get())

      live.onClientMessage("""{"type":"setOverrides","overrides":{"uiMode":"dark"}}""")

      assertEquals(2, session.streamStarts.get(), "new overrides should restart the stream")
      assertEquals(listOf(first), session.streamStops, "the previous stream should be stopped")
    }
  }

  @Test
  fun `two live sessions for the same preview share one daemon stream`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      val a = CopyOnWriteArrayList<String>()
      val b = CopyOnWriteArrayList<String>()
      assertNotNull(ServeLiveSession.tryStart(h, previewId, emptyMap(), send = a::add))
      assertNotNull(ServeLiveSession.tryStart(h, previewId, emptyMap(), send = b::add))

      assertEquals(1, session.streamStarts.get(), "two clients should ride one daemon stream/start")
      assertEquals(1, h.activeStreamCount())

      // One upstream frame fans out to both clients.
      session.emitStreamFrame(
        assertNotNull(session.lastFrameStreamId),
        seq = 4,
        payloadBase64 = "AA",
      )
      assertEquals(1, a.size)
      assertEquals(1, b.size)
    }
  }

  @Test
  fun `switch moves the connection to another preview`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    val blue = "com.example.Blue"
    ServeRenderHost(
        session,
        listOf(ServePreview(previewId, "Red"), ServePreview(blue, "Blue")),
        renderTimeoutSeconds = 30,
      )
      .use { h ->
        val live = assertNotNull(ServeLiveSession.tryStart(h, previewId, emptyMap(), send = {}))
        val firstFsid = assertNotNull(session.lastFrameStreamId)
        assertEquals(1, session.streamStarts.get())

        live.onClientMessage("""{"type":"switch","previewId":"$blue"}""")

        assertEquals(2, session.streamStarts.get(), "switch opens a stream for the new preview")
        assertEquals(
          listOf(firstFsid),
          session.streamStops,
          "the previous preview's stream is dropped",
        )
      }
  }

  @Test
  fun `switching to an unknown preview errors and keeps the current stream`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      val sent = CopyOnWriteArrayList<String>()
      val live =
        assertNotNull(ServeLiveSession.tryStart(h, previewId, emptyMap(), send = sent::add))
      val fsid = assertNotNull(session.lastFrameStreamId)

      live.onClientMessage("""{"type":"switch","previewId":"com.example.Missing"}""")

      assertEquals("error", typeOf(sent.last()))
      assertTrue(session.streamStops.isEmpty(), "a failed switch must not drop the working stream")
      // The original stream is still live.
      session.emitStreamFrame(fsid, seq = 1, payloadBase64 = "AA")
      assertEquals("frame", typeOf(sent.last()))
    }
  }

  @Test
  fun `closing the live session stops the stream`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      val live = assertNotNull(ServeLiveSession.tryStart(h, previewId, emptyMap(), send = {}))
      val fsid = assertNotNull(session.lastFrameStreamId)
      live.close()
      assertEquals(listOf(fsid), session.streamStops)
    }
  }
}
