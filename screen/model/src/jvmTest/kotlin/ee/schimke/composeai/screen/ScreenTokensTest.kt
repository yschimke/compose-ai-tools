package ee.schimke.composeai.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The source pane's highlighting.
 *
 * Rewritten when generation moved to the real `discovery.ScreenGenerator`: the spans used to be
 * emitted by codegen, which no longer exists here, so [SourceHighlighter] lexes instead. The
 * **tiling invariant** is carried over unchanged — it is what a renderer relies on, and it is the
 * assertion that catches an off-by-one before it becomes one wrongly coloured character.
 */
class ScreenTokensTest {

  private fun kinds(source: String): List<Pair<String, SourceTokenKind>> =
    SourceHighlighter.tokenize(source).map { source.substring(it.start, it.end) to it.kind }

  private fun assertTiles(source: String) {
    val tokens = SourceHighlighter.tokenize(source)
    var at = 0
    tokens.forEach { token ->
      assertEquals("tokens must be contiguous from $at", at, token.start)
      assertTrue("token must be non-empty", token.end > token.start)
      at = token.end
    }
    assertEquals("tokens must cover the whole source", source.length, at)
    // …and the text a renderer draws is exactly the source, in order.
    assertEquals(source, tokens.joinToString("") { source.substring(it.start, it.end) })
  }

  @Test
  fun `a generated screen tiles exactly`() {
    assertTiles(
      """
      import androidx.compose.material3.Button

      @Composable
      fun MyScreen() {
          Button(onClick = {}, enabled = false) { Text("Open") }
      }
      """
        .trimIndent()
    )
  }

  @Test
  fun `the empty source has no tokens, and tiling still holds`() {
    assertEquals(emptyList<SourceToken>(), SourceHighlighter.tokenize(""))
    assertTiles("")
  }

  @Test
  fun `keywords, annotations, calls, strings and numbers are distinguished`() {
    val tokens = kinds("""fun f() { Button(enabled = true, n = 42) { Text("Open") } }""")
    assertTrue(tokens.toString(), "fun" to SourceTokenKind.KEYWORD in tokens)
    assertTrue(tokens.toString(), "Button" to SourceTokenKind.CALL in tokens)
    assertTrue(tokens.toString(), "true" to SourceTokenKind.KEYWORD in tokens)
    assertTrue(tokens.toString(), "42" to SourceTokenKind.NUMBER in tokens)
    // The quotes belong to the literal, so a renderer colours them with it.
    assertTrue(tokens.toString(), "\"Open\"" to SourceTokenKind.STRING in tokens)
    assertTrue(
      kinds("@Composable\nfun f()").toString(),
      "@Composable" to SourceTokenKind.ANNOTATION in kinds("@Composable\nfun f()"),
    )
  }

  @Test
  fun `a string containing an escaped quote does not end early`() {
    val source = """Text("a \" b")"""
    assertTrue(kinds(source).toString(), """"a \" b"""" to SourceTokenKind.STRING in kinds(source))
    assertTiles(source)
  }

  @Test
  fun `a dp literal keeps its property read out of the number`() {
    // `4.0.dp` is the number `4.0` and a plain `.dp`. Swallowing the property into the literal
    // would colour it as a number, which is the one thing it is not.
    val tokens = kinds("padding(4.0.dp)")
    assertTrue(tokens.toString(), "4.0" to SourceTokenKind.NUMBER in tokens)
    assertTiles("padding(4.0.dp)")
  }

  @Test
  fun `comments are whole, and a generator refusal comment is one token`() {
    val line = "  // TODO unknown component\n  Button()"
    assertTrue(
      kinds(line).toString(),
      "// TODO unknown component" to SourceTokenKind.COMMENT in kinds(line),
    )
    assertTiles(line)
    val block = "value /* TODO not valid */ next"
    assertTrue(
      kinds(block).toString(),
      "/* TODO not valid */" to SourceTokenKind.COMMENT in kinds(block),
    )
    assertTiles(block)
  }
}
