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
  fun `parses switch with and without overrides`() {
    val plain =
      ServeStreamProtocol.parseClient("""{"type":"switch","previewId":"com.example.Blue"}""")
    assertTrue(plain is ServeStreamProtocol.ClientMessage.Switch, "got $plain")
    assertEquals("com.example.Blue", plain.previewId)
    assertEquals(null, plain.overrides, "omitted overrides should carry the current ones over")

    val withOverrides =
      ServeStreamProtocol.parseClient(
        """{"type":"switch","previewId":"com.example.Blue","overrides":{"uiMode":"dark"}}"""
      )
    assertTrue(withOverrides is ServeStreamProtocol.ClientMessage.Switch, "got $withOverrides")
    assertEquals(mapOf("uiMode" to "dark"), withOverrides.overrides)
  }

  @Test
  fun `switch without a previewId is Unsupported`() {
    assertTrue(
      ServeStreamProtocol.parseClient("""{"type":"switch"}""")
        is ServeStreamProtocol.ClientMessage.Unsupported
    )
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
  fun `parses visibility with and without an explicit fps`() {
    val hidden = ServeStreamProtocol.parseClient("""{"type":"visibility","visible":false}""")
    assertTrue(hidden is ServeStreamProtocol.ClientMessage.Visibility, "got $hidden")
    assertEquals(false, hidden.visible)
    assertEquals(null, hidden.fps, "no fps means the daemon's own throttled default")

    val throttled =
      ServeStreamProtocol.parseClient("""{"type":"visibility","visible":false,"fps":2}""")
    assertTrue(throttled is ServeStreamProtocol.ClientMessage.Visibility, "got $throttled")
    assertEquals(2, throttled.fps)

    val back = ServeStreamProtocol.parseClient("""{"type":"visibility","visible":true}""")
    assertTrue(back is ServeStreamProtocol.ClientMessage.Visibility, "got $back")
    assertEquals(true, back.visible)
  }

  @Test
  fun `visibility without a usable boolean is unsupported, and a zero fps is dropped`() {
    // Neither polarity can be guessed at: pinning it visible keeps a hidden tab rendering, pinning
    // it hidden strands a visible one at 1 fps.
    assertTrue(
      ServeStreamProtocol.parseClient("""{"type":"visibility"}""")
        is ServeStreamProtocol.ClientMessage.Unsupported
    )
    assertTrue(
      ServeStreamProtocol.parseClient("""{"type":"visibility","visible":"maybe"}""")
        is ServeStreamProtocol.ClientMessage.Unsupported
    )

    // fps=0 would mean "never emit"; fall back to the daemon's default instead.
    val zero = ServeStreamProtocol.parseClient("""{"type":"visibility","visible":false,"fps":0}""")
    assertTrue(zero is ServeStreamProtocol.ClientMessage.Visibility, "got $zero")
    assertEquals(null, zero.fps)
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
