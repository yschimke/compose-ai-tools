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

  private fun frameBytes(text: String): ByteArray =
    Base64.getDecoder()
      .decode(Json.parseToJsonElement(text).jsonObject.getValue("dataBase64").jsonPrimitive.content)

  @Test
  fun `onOpen pushes one frame`() {
    host().use { h ->
      val sent = CopyOnWriteArrayList<String>()
      ServeStreamSession(h, previewId, emptyMap(), sent::add).onOpen()
      assertEquals(1, sent.size)
      assertEquals("frame", typeOf(sent[0]))
    }
  }

  @Test
  fun `setOverrides re-renders and the frame reflects the new overrides`() {
    host().use { h ->
      val sent = CopyOnWriteArrayList<String>()
      val session = ServeStreamSession(h, previewId, emptyMap(), sent::add)
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
  fun `seq is monotonic across frames`() {
    host().use { h ->
      val sent = CopyOnWriteArrayList<String>()
      val session = ServeStreamSession(h, previewId, emptyMap(), sent::add)
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
      val session = ServeStreamSession(h, previewId, emptyMap(), sent::add)
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
        val session = ServeStreamSession(h, previewId, emptyMap(), sent::add)
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
      val session = ServeStreamSession(h, previewId, emptyMap(), sent::add)
      session.onOpen()
      session.onClientMessage("""{"type":"switch","previewId":"com.example.Missing"}""")
      assertEquals("error", typeOf(sent.last()))
      session.onClientMessage("""{"type":"requestFrame"}""")
      assertEquals("frame", typeOf(sent.last()))
    }
  }

  @Test
  fun `unsupported message yields an error`() {
    host().use { h ->
      val sent = CopyOnWriteArrayList<String>()
      ServeStreamSession(h, previewId, emptyMap(), sent::add).onClientMessage("""{"type":"nope"}""")
      assertEquals("error", typeOf(sent.single()))
    }
  }
}
