package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
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
    host(session).use { h -> assertNull(ServeLiveSession.tryStart(h, previewId, emptyMap()) {}) }
  }

  @Test
  fun `daemon-pushed frames are forwarded as frame messages`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      val sent = CopyOnWriteArrayList<String>()
      assertNotNull(ServeLiveSession.tryStart(h, previewId, emptyMap(), sent::add))
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
  fun `unchanged heartbeat frames (no payload) are not forwarded`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      val sent = CopyOnWriteArrayList<String>()
      assertNotNull(ServeLiveSession.tryStart(h, previewId, emptyMap(), sent::add))
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
      val live = assertNotNull(ServeLiveSession.tryStart(h, previewId, emptyMap()) {})
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
      val live = assertNotNull(ServeLiveSession.tryStart(h, previewId, emptyMap()) {})
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
      val live = assertNotNull(ServeLiveSession.tryStart(h, previewId, emptyMap(), sent::add))
      live.onClientMessage("""{"type":"input","kind":"telepathy"}""")
      assertEquals("error", typeOf(sent.last()))
      assertTrue(session.interactiveInputs.isEmpty())
    }
  }

  @Test
  fun `setOverrides restarts the held stream`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      val live = assertNotNull(ServeLiveSession.tryStart(h, previewId, emptyMap()) {})
      val first = assertNotNull(session.lastFrameStreamId)
      assertEquals(1, session.streamStarts.get())

      live.onClientMessage("""{"type":"setOverrides","overrides":{"uiMode":"dark"}}""")

      assertEquals(2, session.streamStarts.get(), "new overrides should restart the stream")
      assertEquals(listOf(first), session.streamStops, "the previous stream should be stopped")
    }
  }

  @Test
  fun `closing the live session stops the stream`() {
    val session = FakeRenderSession(newRenderRoot(), streaming = true)
    host(session).use { h ->
      val live = assertNotNull(ServeLiveSession.tryStart(h, previewId, emptyMap()) {})
      val fsid = assertNotNull(session.lastFrameStreamId)
      live.close()
      assertEquals(listOf(fsid), session.streamStops)
    }
  }
}
