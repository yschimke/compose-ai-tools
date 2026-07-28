package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class CatalogEntryWireTest {
  @Test
  fun `catalog component parallel survives manifest decoding`() {
    val manifest =
      Json.decodeFromString<PreviewManifest>(
        """
        {
          "module": ":sample",
          "variant": "debug",
          "previews": [{
            "id": "test.FilledButton",
            "functionName": "FilledButton",
            "className": "test.CatalogKt",
            "catalog": {
              "role": "COMPONENT",
              "componentId": "Button/Filled",
              "parallel": "FilledButton"
            }
          }]
        }
        """
          .trimIndent()
      )

    assertEquals("FilledButton", manifest.previews.single().catalog?.parallel)
  }
}
