package ee.schimke.composeai.clients

import com.google.common.truth.Truth.assertThat
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class StreamMessagesTest {

  private val json = Json

  @Test
  fun parsesFrameMessageProducedByTheServerShape() {
    val png = byteArrayOf(1, 2, 3, 4, 5)
    // Mirror exactly what ServeStreamProtocol.frameMessage emits.
    val wire =
      """{"type":"frame","seq":7,"codec":"png","widthPx":320,"heightPx":640,"dataBase64":"${
        Base64.getEncoder().encodeToString(png)
      }"}"""
    val msg = StreamMessages.parseServer(wire)
    assertThat(msg).isInstanceOf(StreamMessages.ServerMessage.Frame::class.java)
    val frame = (msg as StreamMessages.ServerMessage.Frame).frame
    assertThat(frame.seq).isEqualTo(7L)
    assertThat(frame.widthPx).isEqualTo(320)
    assertThat(frame.heightPx).isEqualTo(640)
    assertThat(frame.bytes).isEqualTo(png)
  }

  @Test
  fun parsesErrorMessage() {
    val msg = StreamMessages.parseServer("""{"type":"error","message":"bad override"}""")
    assertThat(msg).isEqualTo(StreamMessages.ServerMessage.Error("bad override"))
  }

  @Test
  fun unknownTypeAndMalformedDegradeToUnknownNotThrow() {
    assertThat(StreamMessages.parseServer("""{"type":"hello"}"""))
      .isInstanceOf(StreamMessages.ServerMessage.Unknown::class.java)
    assertThat(StreamMessages.parseServer("not json"))
      .isInstanceOf(StreamMessages.ServerMessage.Unknown::class.java)
    assertThat(StreamMessages.parseServer("""{"type":"frame","dataBase64":"!!!notbase64"}"""))
      .isInstanceOf(StreamMessages.ServerMessage.Unknown::class.java)
  }

  @Test
  fun setOverridesMatchesSpikeWireShape() {
    val obj =
      json.parseToJsonElement(StreamMessages.setOverrides(mapOf("device" to "pixel_7"))).jsonObject
    assertThat((obj["type"] as JsonPrimitive).contentOrNull).isEqualTo("setOverrides")
    val overrides = obj["overrides"]!!.jsonObject
    assertThat((overrides["device"] as JsonPrimitive).contentOrNull).isEqualTo("pixel_7")
  }

  @Test
  fun requestFrameIsTheBareType() {
    val obj = json.parseToJsonElement(StreamMessages.requestFrame()).jsonObject
    assertThat((obj["type"] as JsonPrimitive).contentOrNull).isEqualTo("requestFrame")
  }

  @Test
  fun inputClickCarriesKindAndPixelsMirroringDaemonSerialNames() {
    val obj = json.parseToJsonElement(StreamMessages.input(InputEvent.click(12, 34))).jsonObject
    assertThat((obj["type"] as JsonPrimitive).contentOrNull).isEqualTo("input")
    val input = obj["input"]!!.jsonObject
    assertThat((input["kind"] as JsonPrimitive).contentOrNull).isEqualTo("click")
    assertThat((input["pixelX"] as JsonPrimitive).content).isEqualTo("12")
    assertThat((input["pixelY"] as JsonPrimitive).content).isEqualTo("34")
  }

  @Test
  fun inputKeyAndRotaryCarryTheirFields() {
    val key = innerInput(StreamMessages.input(InputEvent.keyDown("Enter")))
    assertThat((key["kind"] as JsonPrimitive).contentOrNull).isEqualTo("keyDown")
    assertThat((key["keyCode"] as JsonPrimitive).contentOrNull).isEqualTo("Enter")

    val rotary = innerInput(StreamMessages.input(InputEvent.rotaryScroll(-3.5f)))
    assertThat((rotary["kind"] as JsonPrimitive).contentOrNull).isEqualTo("rotaryScroll")
    assertThat((rotary["scrollDeltaY"] as JsonPrimitive).content.toFloat()).isEqualTo(-3.5f)
  }

  private fun innerInput(wire: String): JsonObject =
    json.parseToJsonElement(wire).jsonObject["input"]!!.jsonObject
}
