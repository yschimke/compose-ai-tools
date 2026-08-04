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

  @Test
  fun `catalog breakpoints survive manifest decoding`() {
    // `sizes` drives a FAN-OUT: the design-artifacts export mints one catalog component per name,
    // so a reader that dropped the field would report one component where the published catalog has
    // several — the exact silent loss `ignoreUnknownKeys` makes possible and this mirror prevents.
    val manifest =
      Json.decodeFromString<PreviewManifest>(
        """
        {
          "module": ":sample",
          "variant": "debug",
          "previews": [{
            "id": "test.ListLayout",
            "functionName": "ListLayout",
            "className": "test.CatalogKt",
            "catalog": {
              "role": "COMPONENT",
              "componentId": "Layout/List",
              "sizes": ["smallRound", "largeRound"]
            }
          }, {
            "id": "test.ListLayoutFocused",
            "functionName": "ListLayoutFocused",
            "className": "test.CatalogKt",
            "catalog": {
              "role": "VARIANT",
              "componentId": "Layout/List",
              "state": "focused",
              "size": "largeRound"
            }
          }]
        }
        """
          .trimIndent()
      )

    val (component, variant) = manifest.previews.map { it.catalog }
    assertEquals(listOf("smallRound", "largeRound"), component?.sizes)
    assertEquals(null, component?.size)
    assertEquals("largeRound", variant?.size)
    assertEquals(emptyList(), variant?.sizes)
  }

  @Test
  fun `a catalog entry declaring no breakpoints decodes to the inert defaults`() {
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
            "catalog": { "role": "COMPONENT", "componentId": "Button/Filled" }
          }]
        }
        """
          .trimIndent()
      )

    val catalog = manifest.previews.single().catalog
    assertEquals(emptyList(), catalog?.sizes)
    assertEquals(null, catalog?.size)
  }
}
