package ee.schimke.composeai.data.ambient

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the wire shape of [AmbientPayload] — the JSON returned by `data/fetch?kind=compose/ambient`.
 * MCP clients in other languages (the TypeScript MCP relay, the VS Code data-product viewer) read
 * this shape; a silent rename here would break them.
 */
class AmbientPayloadSerializationTest {
  private val json = Json { encodeDefaults = true }

  @Test
  fun `payload roundtrips through JSON`() {
    val payload =
      AmbientPayload(
        state = "ambient",
        burnInProtectionRequired = true,
        deviceHasLowBitAmbient = false,
        updateTimeMillis = 1_234_567L,
      )
    val encoded = json.encodeToString(AmbientPayload.serializer(), payload)
    val decoded = json.decodeFromString(AmbientPayload.serializer(), encoded)
    assertEquals(payload, decoded)
  }

  @Test
  fun `wire field names match the documented shape`() {
    val payload =
      AmbientPayload(
        state = "ambient",
        burnInProtectionRequired = true,
        deviceHasLowBitAmbient = true,
        updateTimeMillis = 0L,
      )
    val encoded = json.encodeToString(AmbientPayload.serializer(), payload)
    assertTrue("state field present: $encoded", encoded.contains("\"state\":\"ambient\""))
    assertTrue(
      "burnIn flag present: $encoded",
      encoded.contains("\"burnInProtectionRequired\":true"),
    )
    assertTrue(
      "low-bit flag present: $encoded",
      encoded.contains("\"deviceHasLowBitAmbient\":true"),
    )
    assertTrue(
      "updateTimeMillis present (encodeDefaults=true): $encoded",
      encoded.contains("\"updateTimeMillis\":0"),
    )
  }

  @Test
  fun `product kind is the documented compose ambient string`() {
    assertEquals("compose/ambient", Material3AmbientProduct.KIND)
    assertEquals(1, Material3AmbientProduct.SCHEMA_VERSION)
  }
}
