package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class DeviceCommandTest {
  private val json = Json {
    prettyPrint = false
    encodeDefaults = true
    ignoreUnknownKeys = true
  }

  @Test
  fun `device catalog response pins schema and includes known device geometry`() {
    val response = buildDeviceCatalogResponse()
    val encoded = json.encodeToString(DeviceCatalogResponse.serializer(), response)
    val parsed = json.decodeFromString(DeviceCatalogResponse.serializer(), encoded)

    assertEquals(DEVICES_SCHEMA, parsed.schema)
    assertTrue(""""schema":"$DEVICES_SCHEMA"""" in encoded)

    val pixel5 = parsed.devices.single { it.id == "id:pixel_5" }
    assertEquals(393, pixel5.widthDp)
    assertEquals(851, pixel5.heightDp)
    assertEquals(2.75f, pixel5.density)

    val wear = parsed.devices.single { it.id == "id:wearos_large_round" }
    assertEquals(true, wear.isRound)
  }

  @Test
  fun `device catalog is sorted for stable CLI output`() {
    val ids = buildDeviceCatalogResponse().devices.map { it.id }
    assertEquals(ids.sorted(), ids)
  }
}
