package ee.schimke.composeai.servewasm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `parseFontsManifest` is `internal` and top-level precisely so these rules can be asserted without
 * a browser, and the rule that matters most is the fail-soft one: a dropped face looks like a
 * working render, so nothing else in the system will report it.
 */
class CatalogFontsTest {

  @Test
  fun `a well-formed manifest flattens to its faces`() {
    val faces =
      parseFontsManifest(
        """
        {"families":[
          {"role":"default","name":"Roboto","fonts":[
            {"file":"Roboto-Regular.ttf"},
            {"file":"Roboto-Medium.ttf","weight":500},
            {"file":"Roboto-Italic.ttf","italic":true}
          ]}
        ]}
        """
      )
    assertEquals(3, faces.size)
    assertEquals(listOf("default", "default", "default"), faces.map { it.role })
    // An absent weight is Regular — what a single-face family means by omitting it.
    assertEquals(listOf(400, 500, 400), faces.map { it.weight })
    assertEquals(listOf(false, false, true), faces.map { it.italic })
  }

  @Test
  fun `one malformed family does not cost the others`() {
    // The regression this test exists for. `.jsonPrimitive` / `.jsonArray` THROW on a wrong-typed
    // element, and the only `runCatching` in this path wraps the whole call in `loadCatalogFonts` —
    // so a single family whose `name` was an object, or whose `fonts` was not an array, collapsed
    // the entire manifest to nothing and every native preview silently fell back to the CMP
    // bundled font. Each of these entries used to take Roboto down with it.
    for (broken in
      listOf(
        """{"role":"generic","name":{"nested":"object"},"fonts":[{"file":"a.ttf"}]}""",
        """{"role":"generic","name":"X","fonts":{"not":"an array"}}""",
        """{"role":["not","a","string"],"name":"X","fonts":[{"file":"a.ttf"}]}""",
        """{"role":"generic","name":"X","fonts":[{"file":{"nested":"object"}}]}""",
        """{"role":"generic","name":"X","fonts":[{"file":"a.ttf","weight":"heavy"}]}""",
        """{"role":"generic","name":"X","fonts":[{"file":"a.ttf","italic":"yes"}]}""",
        """"a bare string, not an object"""",
      )) {
      val faces =
        parseFontsManifest(
          """{"families":[$broken,{"role":"default","name":"Roboto","fonts":[{"file":"R.ttf"}]}]}"""
        )
      assertTrue(
        faces.any { it.role == "default" && it.file == "R.ttf" },
        "the valid family was dropped alongside: $broken",
      )
    }
  }

  @Test
  fun `a malformed weight or italic falls back rather than dropping the face`() {
    val faces =
      parseFontsManifest(
        """{"families":[{"role":"default","name":"R","fonts":[
             {"file":"R.ttf","weight":"heavy","italic":"yes"}]}]}"""
      )
    assertEquals(1, faces.size)
    assertEquals(400, faces.single().weight)
    assertEquals(false, faces.single().italic)
  }

  @Test
  fun `an entry without a role or a file is skipped`() {
    // Both are load-bearing: the role is how a face is looked up, and the file is what is fetched.
    // A face missing either cannot be used, so it is dropped rather than half-registered.
    assertEquals(
      emptyList(),
      parseFontsManifest("""{"families":[{"name":"R","fonts":[{"file":"R.ttf"}]}]}"""),
    )
    assertEquals(
      emptyList(),
      parseFontsManifest(
        """{"families":[{"role":"default","name":"R","fonts":[{"weight":400}]}]}"""
      ),
    )
    assertEquals(
      emptyList(),
      parseFontsManifest(
        """{"families":[{"role":"default","name":"R","fonts":[{"file":"  "}]}]}"""
      ),
    )
  }

  @Test
  fun `unparseable or unexpected json yields nothing rather than throwing`() {
    // `loadCatalogFonts` turns an empty manifest into today's bundled-font behaviour, which is the
    // correct degradation. Throwing would not be.
    assertEquals(emptyList(), parseFontsManifest("not json at all"))
    assertEquals(emptyList(), parseFontsManifest("[]"))
    assertEquals(emptyList(), parseFontsManifest("{}"))
    assertEquals(emptyList(), parseFontsManifest("""{"families":"not an array"}"""))
    assertEquals(emptyList(), parseFontsManifest(""))
  }
}
