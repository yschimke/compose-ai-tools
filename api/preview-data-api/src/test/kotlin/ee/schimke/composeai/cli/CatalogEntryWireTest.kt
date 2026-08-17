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
              "parallel": "FilledButton",
              "kitAxis": "Configuration",
              "referenceContentsOnly": false
            }
          }]
        }
        """
          .trimIndent()
      )

    assertEquals("FilledButton", manifest.previews.single().catalog?.parallel)
    assertEquals("Configuration", manifest.previews.single().catalog?.kitAxis)
    assertEquals(false, manifest.previews.single().catalog?.referenceContentsOnly)
  }

  @Test
  fun `a variant's kit axis and value survive manifest decoding`() {
    // The pair a variant declares when the kit spells its value differently — `type=range` against
    // the kit's `Type=Full-screen (range)`. Dropping either half here strands the declaration on
    // the annotation, which is where it sat, unread, before anything projected it.
    val manifest =
      Json.decodeFromString<PreviewManifest>(
        """
        {
          "module": ":sample",
          "variant": "debug",
          "previews": [{
            "id": "test.DatePickerRange",
            "functionName": "DatePickerRange",
            "className": "test.CatalogKt",
            "catalog": {
              "role": "VARIANT",
              "componentId": "DatePicker/Modal",
              "props": [{ "key": "type", "value": "range" }],
              "kitAxis": "Type",
              "kitValue": "Full-screen (range)"
            }
          }]
        }
        """
          .trimIndent()
      )

    val catalog = manifest.previews.single().catalog
    assertEquals("Type", catalog?.kitAxis)
    assertEquals("Full-screen (range)", catalog?.kitValue)
  }

  @Test
  fun `catalog breakpoints survive manifest decoding`() {
    // `perBreakpoint` drives a FAN-OUT: the design-artifacts export mints one catalog component per
    // breakpoint the function rendered at, so a reader that dropped the field would report one
    // component where the published catalog has several — the exact silent loss `ignoreUnknownKeys`
    // makes possible and this mirror prevents.
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
              "perBreakpoint": true
            }
          }, {
            "id": "test.ListLayoutFocused",
            "functionName": "ListLayoutFocused",
            "className": "test.CatalogKt",
            "catalog": {
              "role": "VARIANT",
              "componentId": "Layout/List",
              "state": "focused"
            }
          }]
        }
        """
          .trimIndent()
      )

    val (component, variant) = manifest.previews.map { it.catalog }
    assertEquals(true, component?.perBreakpoint)
    assertEquals(false, variant?.perBreakpoint)
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

    assertEquals(false, manifest.previews.single().catalog?.perBreakpoint)
    assertEquals(true, manifest.previews.single().catalog?.referenceContentsOnly)
  }

  @Test
  fun `fixedTheme survives the wire and defaults off`() {
    // `ignoreUnknownKeys` would drop the flag silently if this API didn't mirror it, and a serve
    // host reading the manifest would re-theme a specimen that discovery had already marked.
    val manifest =
      Json.decodeFromString<PreviewManifest>(
        """
        {
          "module": ":sample",
          "variant": "debug",
          "previews": [
            {
              "id": "themecatalog__Brand_Light",
              "functionName": "Brand Light theme",
              "className": "test.BrandLightTheme",
              "fixedTheme": true
            },
            {
              "id": "test.ContactRow",
              "functionName": "ContactRow",
              "className": "test.PreviewsKt"
            }
          ]
        }
        """
          .trimIndent()
      )

    val byId = manifest.previews.associateBy { it.id }
    assertEquals(true, byId.getValue("themecatalog__Brand_Light").fixedTheme)
    assertEquals(false, byId.getValue("test.ContactRow").fixedTheme)
  }
}
