package ee.schimke.composeai.data.gestures

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the wire shape of [GesturePayload] — the JSON returned by
 * `data/fetch?kind=compose/gestures`. MCP clients in other languages (the TypeScript MCP relay, the
 * VS Code data-product viewer) read this shape; a silent rename here would break them.
 */
class GesturePayloadSerializationTest {
  private val json = Json { encodeDefaults = true }

  @Test
  fun `payload roundtrips through JSON`() {
    val payload =
      GesturePayload(
        enabled = true,
        hintsShown = true,
        lastInvoked = "Play",
        registered =
          listOf(
            RegisteredGesture(type = "primary", label = "Play", hintAvailable = true),
            RegisteredGesture(type = "dismiss", label = "Back", hintAvailable = false),
          ),
        detected = listOf("primary", "dismiss"),
      )
    val encoded = json.encodeToString(GesturePayload.serializer(), payload)
    val decoded = json.decodeFromString(GesturePayload.serializer(), encoded)
    assertEquals(payload, decoded)
  }

  @Test
  fun `wire field names match the documented shape`() {
    val payload =
      GesturePayload(
        enabled = true,
        hintsShown = false,
        registered = listOf(RegisteredGesture("scroll", "Scroll down", true)),
        detected = listOf("primary"),
      )
    val encoded = json.encodeToString(GesturePayload.serializer(), payload)
    assertTrue("enabled field present: $encoded", encoded.contains("\"enabled\":true"))
    assertTrue("hintsShown field present: $encoded", encoded.contains("\"hintsShown\":false"))
    assertTrue("type field present: $encoded", encoded.contains("\"type\":\"scroll\""))
    assertTrue("label field present: $encoded", encoded.contains("\"label\":\"Scroll down\""))
    assertTrue("hintAvailable present: $encoded", encoded.contains("\"hintAvailable\":true"))
    assertTrue("detected field present: $encoded", encoded.contains("\"detected\":[\"primary\"]"))
  }

  @Test
  fun `product kind is the documented compose gestures string`() {
    assertEquals("compose/gestures", Material3GestureProduct.KIND)
    assertEquals(1, Material3GestureProduct.SCHEMA_VERSION)
  }
}
