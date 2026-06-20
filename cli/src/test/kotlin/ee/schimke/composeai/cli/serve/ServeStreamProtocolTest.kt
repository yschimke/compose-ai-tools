package ee.schimke.composeai.cli.serve

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ServeStreamProtocolTest {

  @Test
  fun `parses setOverrides into a string map`() {
    val msg =
      ServeStreamProtocol.parseClient(
        """{"type":"setOverrides","overrides":{"uiMode":"dark","device":"id:pixel_5"}}"""
      )
    assertTrue(msg is ServeStreamProtocol.ClientMessage.SetOverrides, "got $msg")
    assertEquals(mapOf("uiMode" to "dark", "device" to "id:pixel_5"), msg.overrides)
  }

  @Test
  fun `setOverrides with no overrides object yields an empty map`() {
    val msg = ServeStreamProtocol.parseClient("""{"type":"setOverrides"}""")
    assertTrue(msg is ServeStreamProtocol.ClientMessage.SetOverrides, "got $msg")
    assertTrue(msg.overrides.isEmpty())
  }

  @Test
  fun `parses requestFrame`() {
    assertEquals(
      ServeStreamProtocol.ClientMessage.RequestFrame,
      ServeStreamProtocol.parseClient("""{"type":"requestFrame"}"""),
    )
  }

  @Test
  fun `parses input with pixel coordinates`() {
    val msg =
      ServeStreamProtocol.parseClient("""{"type":"input","kind":"click","pixelX":10,"pixelY":20}""")
    assertTrue(msg is ServeStreamProtocol.ClientMessage.Input, "got $msg")
    assertEquals("click", msg.kind)
    assertEquals(10, msg.pixelX)
    assertEquals(20, msg.pixelY)
  }

  @Test
  fun `parses input with pointerId, scroll delta and keyCode`() {
    val drag =
      ServeStreamProtocol.parseClient(
        """{"type":"input","kind":"pointerMove","pixelX":3,"pixelY":4,"pointerId":2}"""
      )
    assertTrue(drag is ServeStreamProtocol.ClientMessage.Input, "got $drag")
    assertEquals("pointerMove", drag.kind)
    assertEquals(2, drag.pointerId)

    val scroll =
      ServeStreamProtocol.parseClient(
        """{"type":"input","kind":"rotaryScroll","scrollDeltaY":-8.5}"""
      )
    assertTrue(scroll is ServeStreamProtocol.ClientMessage.Input, "got $scroll")
    assertEquals(-8.5f, scroll.scrollDeltaY)

    val key =
      ServeStreamProtocol.parseClient("""{"type":"input","kind":"keyDown","keyCode":"66"}""")
    assertTrue(key is ServeStreamProtocol.ClientMessage.Input, "got $key")
    assertEquals("66", key.keyCode)
  }

  @Test
  fun `unknown type and malformed json are Unsupported, never thrown`() {
    assertTrue(
      ServeStreamProtocol.parseClient("""{"type":"wat"}""")
        is ServeStreamProtocol.ClientMessage.Unsupported
    )
    assertTrue(
      ServeStreamProtocol.parseClient("not json at all")
        is ServeStreamProtocol.ClientMessage.Unsupported
    )
  }

  @Test
  fun `well-formed JSON of the wrong shape never throws`() {
    // type is not a string → unknown type → Unsupported (not a ClassCastException).
    assertTrue(
      ServeStreamProtocol.parseClient("""{"type":{}}""")
        is ServeStreamProtocol.ClientMessage.Unsupported
    )
    // a non-object root.
    assertTrue(
      ServeStreamProtocol.parseClient("[]") is ServeStreamProtocol.ClientMessage.Unsupported
    )
    // overrides as an array → degrade to empty, don't throw.
    val arr = ServeStreamProtocol.parseClient("""{"type":"setOverrides","overrides":[]}""")
    assertTrue(arr is ServeStreamProtocol.ClientMessage.SetOverrides, "got $arr")
    assertTrue(arr.overrides.isEmpty())
    // a non-string override value is skipped, valid ones kept.
    val mixed =
      ServeStreamProtocol.parseClient(
        """{"type":"setOverrides","overrides":{"uiMode":"dark","bad":{}}}"""
      )
    assertTrue(mixed is ServeStreamProtocol.ClientMessage.SetOverrides, "got $mixed")
    assertEquals(mapOf("uiMode" to "dark"), mixed.overrides)
  }

  @Test
  fun `frame message carries seq, size, codec and base64 payload`() {
    val png = byteArrayOf(1, 2, 3, 4, 5)
    val obj = Json.parseToJsonElement(ServeStreamProtocol.frameMessage(7, 320, 640, png)).jsonObject
    assertEquals("frame", obj.getValue("type").jsonPrimitive.content)
    assertEquals("7", obj.getValue("seq").jsonPrimitive.content)
    assertEquals("png", obj.getValue("codec").jsonPrimitive.content)
    assertEquals("320", obj.getValue("widthPx").jsonPrimitive.content)
    assertEquals("640", obj.getValue("heightPx").jsonPrimitive.content)
    assertContentEqualsB64(png, obj.getValue("dataBase64").jsonPrimitive.content)
  }

  @Test
  fun `error message carries the reason`() {
    val obj = Json.parseToJsonElement(ServeStreamProtocol.errorMessage("bad")).jsonObject
    assertEquals("error", obj.getValue("type").jsonPrimitive.content)
    assertEquals("bad", obj.getValue("message").jsonPrimitive.content)
  }

  private fun assertContentEqualsB64(expected: ByteArray, b64: String) {
    assertEquals(expected.toList(), Base64.getDecoder().decode(b64).toList())
  }
}
