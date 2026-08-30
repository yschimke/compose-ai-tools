package ee.schimke.composeai.servewasm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Reading a published preview's override declarations.
 *
 * The failure this guards is quiet by construction: a dropped seed does not error, it renders the
 * composable's author default. The preview still looks like a preview — just not like the catalog.
 */
class OverrideSeedsTest {
  private fun parse(json: String): Map<String, String> =
    overrideSeedsOf(Json.parseToJsonElement(json) as JsonObject)

  @Test
  fun `reads a colour declaration, which spells its value argb`() {
    // `PreviewOverrideValue` serialises each case under its own property name. Every other kind
    // uses `value`; colour uses `argb`. Reading only the first dropped every colour override.
    assertEquals(
      mapOf("iconColor" to "#FF7DE2FF"),
      parse(
        """{"overrides":[{"key":"iconColor","current":{"kind":"color","argb":"#FF7DE2FF"}}]}"""
      ),
    )
  }

  @Test
  fun `still reads the kinds that spell it value`() {
    assertEquals(
      mapOf("enabled" to "true", "count" to "3"),
      parse(
        """{"overrides":[
             {"key":"enabled","current":{"kind":"bool","value":true}},
             {"key":"count","default":{"kind":"int","value":3}}]}"""
      ),
    )
  }

  @Test
  fun `prefers the current value over the declared default`() {
    assertEquals(
      mapOf("iconColor" to "#FF001122"),
      parse(
        """{"overrides":[{"key":"iconColor",
             "default":{"kind":"color","argb":"#FFAABBCC"},
             "current":{"kind":"color","argb":"#FF001122"}}]}"""
      ),
    )
  }

  @Test
  fun `keys an indexed declaration by its position`() {
    assertEquals(
      mapOf("rowColor[1]" to "#FF7DE2FF"),
      parse(
        """{"overrides":[{"key":"rowColor","index":1,"current":{"kind":"color","argb":"#FF7DE2FF"}}]}"""
      ),
    )
  }

  @Test
  fun `skips a declaration with no value of either spelling`() {
    assertEquals(
      emptyMap(),
      parse("""{"overrides":[{"key":"mystery","current":{"kind":"future"}}]}"""),
    )
  }
}
