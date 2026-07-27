package ee.schimke.composeai.cli.serve

import java.io.File
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ServeStreamSessionTest {

  private val previewId = "com.example.Red"

  private fun newRenderRoot(): File =
    java.nio.file.Files.createTempDirectory("serve-stream").toFile().also { it.deleteOnExit() }

  private fun host(): ServeRenderHost =
    ServeRenderHost(
      session = FakeRenderSession(newRenderRoot()),
      previews = listOf(ServePreview(previewId, "Red")),
      renderTimeoutSeconds = 30,
    )

  private fun typeOf(text: String): String =
    Json.parseToJsonElement(text).jsonObject.getValue("type").jsonPrimitive.content

  private fun messageOf(text: String): String =
    Json.parseToJsonElement(text).jsonObject.getValue("message").jsonPrimitive.content

  private fun frameBytes(text: String): ByteArray =
    Base64.getDecoder()
      .decode(Json.parseToJsonElement(text).jsonObject.getValue("dataBase64").jsonPrimitive.content)

  @Test
  fun `onOpen pushes one frame`() {
    host().use { h ->
      val sent = CopyOnWriteArrayList<String>()
      ServeStreamSession(h, previewId, emptyMap(), sent::add, system = "compose-m3").onOpen()
      assertEquals(1, sent.size)
      assertEquals("frame", typeOf(sent[0]))
    }
  }

  @Test
  fun `setOverrides re-renders and the frame reflects the new overrides`() {
    host().use { h ->
      val sent = CopyOnWriteArrayList<String>()
      val session = ServeStreamSession(h, previewId, emptyMap(), sent::add, system = "compose-m3")
      session.onOpen()
      session.onClientMessage("""{"type":"setOverrides","overrides":{"uiMode":"dark"}}""")

      assertEquals(2, sent.size)
      assertTrue(sent.all { typeOf(it) == "frame" })
      // The fake encodes overrides into the PNG bytes, so different overrides → different frame.
      assertTrue(
        !frameBytes(sent[0]).contentEquals(frameBytes(sent[1])),
        "frame after setOverrides should differ from the default frame",
      )
    }
  }

  @Test
  fun `a dark-first system drops uiMode from setOverrides and switch messages`() {
    host().use { h ->
      val sent = CopyOnWriteArrayList<String>()
      // The REAL per-system policy, resolved from the catalog id — not an injected stand-in. A
      // uiMode the parser would reject proves it was dropped before parsing.
      val session =
        ServeStreamSession(h, previewId, emptyMap(), sent::add, system = "confetti-wear")
      session.onClientMessage("""{"type":"setOverrides","overrides":{"uiMode":"chartreuse"}}""")
      session.onClientMessage(
        """{"type":"switch","previewId":"$previewId","overrides":{"uiMode":"light"}}"""
      )

      assertEquals(listOf("frame", "frame"), sent.map(::typeOf))
    }
  }

  @Test
  fun `a light-capable system keeps uiMode on the socket lane`() {
    host().use { h ->
      val sent = CopyOnWriteArrayList<String>()
      val session = ServeStreamSession(h, previewId, emptyMap(), sent::add, system = "compose-m3")
      // Same message, non-Wear catalog: the override reaches the parser, which rejects the bogus
      // value. If normalization ever leaked to every system, this would silently become a frame.
      session.onClientMessage("""{"type":"setOverrides","overrides":{"uiMode":"chartreuse"}}""")

      assertEquals(listOf("error"), sent.map(::typeOf))
    }
  }

  @Test
  fun `seq is monotonic across frames`() {
    host().use { h ->
      val sent = CopyOnWriteArrayList<String>()
      val session = ServeStreamSession(h, previewId, emptyMap(), sent::add, system = "compose-m3")
      session.onOpen()
      session.onClientMessage("""{"type":"requestFrame"}""")
      session.onClientMessage("""{"type":"requestFrame"}""")
      val seqs = sent.map {
        Json.parseToJsonElement(it).jsonObject.getValue("seq").jsonPrimitive.content.toLong()
      }
      assertEquals(listOf(0L, 1L, 2L), seqs)
    }
  }

  @Test
  fun `invalid overrides produce an error frame, not a crash, and the lane stays open`() {
    host().use { h ->
      val sent = CopyOnWriteArrayList<String>()
      val session = ServeStreamSession(h, previewId, emptyMap(), sent::add, system = "compose-m3")
      session.onClientMessage("""{"type":"setOverrides","overrides":{"uiMode":"chartreuse"}}""")
      assertEquals("error", typeOf(sent.last()))
      // Still usable afterwards.
      session.onClientMessage("""{"type":"requestFrame"}""")
      assertEquals("frame", typeOf(sent.last()))
    }
  }

  @Test
  fun `switch re-renders a different preview on the snapshot lane`() {
    val blue = "com.example.Blue"
    ServeRenderHost(
        session = FakeRenderSession(newRenderRoot()),
        previews = listOf(ServePreview(previewId, "Red"), ServePreview(blue, "Blue")),
        renderTimeoutSeconds = 30,
      )
      .use { h ->
        val sent = CopyOnWriteArrayList<String>()
        val session = ServeStreamSession(h, previewId, emptyMap(), sent::add, system = "compose-m3")
        session.onOpen()
        session.onClientMessage("""{"type":"switch","previewId":"$blue"}""")
        assertEquals(2, sent.size)
        assertEquals("frame", typeOf(sent.last()))
      }
  }

  @Test
  fun `switch to an unknown preview errors and keeps the current one`() {
    host().use { h ->
      val sent = CopyOnWriteArrayList<String>()
      val session = ServeStreamSession(h, previewId, emptyMap(), sent::add, system = "compose-m3")
      session.onOpen()
      session.onClientMessage("""{"type":"switch","previewId":"com.example.Missing"}""")
      assertEquals("error", typeOf(sent.last()))
      session.onClientMessage("""{"type":"requestFrame"}""")
      assertEquals("frame", typeOf(sent.last()))
    }
  }

  @Test
  fun `switch with invalid overrides errors and keeps the working view`() {
    host().use { h ->
      val sent = CopyOnWriteArrayList<String>()
      val session = ServeStreamSession(h, previewId, emptyMap(), sent::add, system = "compose-m3")
      session.onOpen()
      session.onClientMessage(
        """{"type":"switch","previewId":"$previewId","overrides":{"uiMode":"chartreuse"}}"""
      )
      assertEquals("error", typeOf(sent.last()))
      // The bad override must not poison the session — the previous view still renders.
      session.onClientMessage("""{"type":"requestFrame"}""")
      assertEquals("frame", typeOf(sent.last()))
    }
  }

  @Test
  fun `input on the snapshot lane reports the original live-lane failure when known`() {
    host().use { h ->
      val sent = CopyOnWriteArrayList<String>()
      val session =
        ServeStreamSession(
          h,
          previewId,
          emptyMap(),
          sent::add,
          system = "compose-m3",
          liveUnavailableReason =
            "the daemon could not hold an interactive session for this preview",
        )
      session.onClientMessage("""{"type":"input","kind":"click","pixelX":1,"pixelY":1}""")
      assertEquals("error", typeOf(sent.single()))
      val message = messageOf(sent.single())
      assertTrue(message.contains("input requires a live stream"), "keeps the base explanation")
      assertTrue(
        message.contains("the daemon could not hold an interactive session"),
        "surfaces the original live-lane failure instead of the opaque message: $message",
      )
    }
  }

  @Test
  fun `input on the snapshot lane falls back to the bare message when no reason is known`() {
    host().use { h ->
      val sent = CopyOnWriteArrayList<String>()
      // No liveUnavailableReason (e.g. a backend that returned null without one).
      val session = ServeStreamSession(h, previewId, emptyMap(), sent::add, system = "compose-m3")
      session.onClientMessage("""{"type":"input","kind":"click","pixelX":1,"pixelY":1}""")
      assertEquals("input requires a live stream", messageOf(sent.single()))
    }
  }

  @Test
  fun `unsupported message yields an error`() {
    host().use { h ->
      val sent = CopyOnWriteArrayList<String>()
      ServeStreamSession(h, previewId, emptyMap(), sent::add, system = "compose-m3")
        .onClientMessage("""{"type":"nope"}""")
      assertEquals("error", typeOf(sent.single()))
    }
  }
}
