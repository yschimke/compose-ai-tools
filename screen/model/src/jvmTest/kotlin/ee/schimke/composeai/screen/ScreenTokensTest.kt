package ee.schimke.composeai.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the highlighting spans codegen records as it writes.
 *
 * The load-bearing assertion is [tokensTileTheSource], applied to **every** screen below rather
 * than to one blessed output. An off-by-one in a single fragment shows up as one wrongly coloured
 * character, which nobody notices in a review and everybody notices in use; as a property over the
 * whole corpus it is a red test instead.
 */
class ScreenTokensTest {

  private val specs =
    mapOf(
      "lazy-column" to
        ComponentSpec(
          call = "LazyColumn",
          imports = listOf("androidx.compose.foundation.lazy.LazyColumn"),
          container = true,
        ),
      "list-header" to
        ComponentSpec(
          call = "ListHeader",
          imports = listOf("com.example.ListHeader"),
          knobs = mapOf("text" to KnobSpec("text")),
        ),
      "card" to
        ComponentSpec(
          call = "Card",
          imports = listOf("androidx.compose.material3.Card"),
          container = true,
          knobs = mapOf("elevation" to KnobSpec("elevationDp", KnobKind.DP)),
        ),
      "button-filled" to
        ComponentSpec(
          call = "Button",
          imports = listOf("androidx.compose.material3.Button"),
          requiredArgs = listOf("onClick = {}"),
          knobs = mapOf("enabled" to KnobSpec("enabled", KnobKind.BOOLEAN)),
          contentKnob = "label",
        ),
      "scaffold" to
        ComponentSpec(
          call = "Scaffold",
          imports = listOf("androidx.compose.material3.Scaffold"),
          container = true,
          slots = mapOf("topBar" to "topBar"),
        ),
      "every-kind" to
        ComponentSpec(
          call = "EveryKind",
          imports = listOf("com.example.EveryKind"),
          knobs =
            mapOf(
              "s" to KnobSpec("s", KnobKind.STRING),
              "i" to KnobSpec("i", KnobKind.INT),
              "f" to KnobSpec("f", KnobKind.FLOAT),
              "b" to KnobSpec("b", KnobKind.BOOLEAN),
              "c" to KnobSpec("c", KnobKind.COLOR),
              "d" to KnobSpec("d", KnobKind.DP),
            ),
        ),
    )

  /**
   * Every shape codegen can produce, including the ones it refuses to produce.
   *
   * The unrepresentable cases matter most to the invariant: they are the paths that append comments
   * out of line with the call being written, which is exactly where an offset goes wrong.
   */
  private val corpus: Map<String, Screen> =
    mapOf(
      "empty" to Screen(name = "empty"),
      "unnameable" to Screen(name = "!!!"),
      "the builder's scenario" to
        Screen(
          name = "activity list",
          roots =
            listOf(
              ScreenNode(
                "lazy-column",
                children =
                  listOf(
                    ScreenNode("list-header", knobs = mapOf("text" to "Today")),
                    ScreenNode(
                      "card",
                      knobs = mapOf("elevation" to "4"),
                      children =
                        listOf(
                          ScreenNode(
                            "button-filled",
                            knobs = mapOf("label" to "Open", "enabled" to "false"),
                          )
                        ),
                    ),
                  ),
              )
            ),
        ),
      "a named slot" to
        Screen(
          name = "slotted",
          roots =
            listOf(
              ScreenNode(
                "scaffold",
                children =
                  listOf(
                    ScreenNode("list-header", slot = "topBar", knobs = mapOf("text" to "Title")),
                    ScreenNode("list-header", knobs = mapOf("text" to "Body")),
                  ),
              )
            ),
        ),
      "every literal kind" to
        Screen(
          name = "kinds",
          roots =
            listOf(
              ScreenNode(
                "every-kind",
                knobs =
                  mapOf(
                    "s" to "hi",
                    "i" to "7",
                    "f" to "1.5",
                    "b" to "true",
                    "c" to "#2196F3",
                    "d" to "8",
                  ),
              )
            ),
        ),
      "every literal kind, all invalid" to
        Screen(
          name = "bad kinds",
          roots =
            listOf(
              ScreenNode(
                "every-kind",
                knobs =
                  mapOf(
                    "i" to "abc",
                    "f" to "abc",
                    "b" to "abc",
                    "c" to "abc",
                    "d" to "abc",
                  ),
              )
            ),
        ),
      "a string needing every escape" to
        Screen(
          name = "escapes",
          roots = listOf(ScreenNode("list-header", knobs = mapOf("text" to "a\"b\\c\$d\ne\tf"))),
        ),
      "everything unrepresentable at once" to
        Screen(
          name = "problems",
          roots =
            listOf(
              ScreenNode("no-such-component", children = listOf(ScreenNode("list-header"))),
              ScreenNode("list-header", knobs = mapOf("nope" to "x")),
              ScreenNode("list-header", children = listOf(ScreenNode("list-header"))),
              ScreenNode(
                "scaffold",
                children = listOf(ScreenNode("list-header", slot = "bottomBar")),
              ),
            ),
        ),
    )

  @Test
  fun `tokens tile the source exactly, for every screen`() {
    corpus.forEach { (label, screen) ->
      val generated = ScreenCodegen.generate(screen, specs)
      assertTokensTile(label, generated)
    }
  }

  /**
   * Sorted, non-overlapping, gapless, and bounded by the source — the whole invariant a renderer
   * leans on, checked in one walk so a failure names which of the four broke and where.
   */
  private fun assertTokensTile(label: String, generated: GeneratedScreen) {
    val source = generated.source
    val tokens = generated.tokens
    assertTrue("$label: a non-empty source must have tokens", tokens.isNotEmpty())

    var at = 0
    tokens.forEachIndexed { index, token ->
      assertTrue(
        "$label: token $index is empty or inverted ($token)",
        token.start < token.end,
      )
      assertEquals(
        "$label: token $index does not start where token ${index - 1} ended",
        at,
        token.start,
      )
      assertTrue(
        "$label: token $index runs past the end of a ${source.length}-char source ($token)",
        token.end <= source.length,
      )
      at = token.end
    }
    assertEquals("$label: the tokens stop before the end of the source", source.length, at)
  }

  @Test
  fun `a token's range is its text`() {
    corpus.forEach { (label, screen) ->
      val generated = ScreenCodegen.generate(screen, specs)
      val rebuilt =
        generated.tokens.joinToString("") { generated.source.substring(it.start, it.end) }
      assertEquals(
        "$label: concatenating every token must reproduce the source",
        generated.source,
        rebuilt,
      )
    }
  }

  @Test
  fun `plain runs are maximal, never split into neighbours`() {
    corpus.forEach { (label, screen) ->
      val tokens = ScreenCodegen.generate(screen, specs).tokens
      tokens.zipWithNext().forEachIndexed { index, (a, b) ->
        assertTrue(
          "$label: tokens $index and ${index + 1} are both PLAIN and should be one run",
          a.kind != b.kind || a.kind != SourceTokenKind.PLAIN,
        )
      }
    }
  }

  @Test
  fun `each construct gets the kind a highlighter needs`() {
    val generated = ScreenCodegen.generate(corpus.getValue("the builder's scenario"), specs)
    val byText = generated.tokens.map { generated.source.substring(it.start, it.end) to it.kind }

    assertTrue("`import` is a keyword", byText.contains("import" to SourceTokenKind.KEYWORD))
    assertTrue("`fun` is a keyword", byText.contains("fun" to SourceTokenKind.KEYWORD))
    assertTrue(
      "`@Composable` is an annotation",
      byText.contains("@Composable" to SourceTokenKind.ANNOTATION),
    )
    assertTrue("a call name is a call", byText.contains("LazyColumn" to SourceTokenKind.CALL))
    assertTrue("so is the one in content", byText.contains("Text" to SourceTokenKind.CALL))
    assertTrue(
      "the generated function's own name is a call",
      byText.contains("ActivityList" to SourceTokenKind.CALL),
    )
    assertTrue(
      "a string literal carries its quotes",
      byText.contains("\"Open\"" to SourceTokenKind.STRING),
    )
    assertTrue("`false` is a keyword", byText.contains("false" to SourceTokenKind.KEYWORD))

    // `4.0.dp`: the number stops at the dot, which is where an IDE stops colouring it too.
    assertTrue("a dp value's number is a number", byText.contains("4.0" to SourceTokenKind.NUMBER))
    val dpAt = generated.source.indexOf("4.0.dp")
    assertTrue("the scenario still generates a dp value", dpAt >= 0)
    val dpNumber = generated.tokens.single { it.start == dpAt }
    assertEquals(SourceTokenKind.NUMBER, dpNumber.kind)
    assertEquals("4.0", generated.source.substring(dpNumber.start, dpNumber.end))
  }

  @Test
  fun `a color is a call around a hex number`() {
    val generated = ScreenCodegen.generate(corpus.getValue("every literal kind"), specs)
    val byText = generated.tokens.map { generated.source.substring(it.start, it.end) to it.kind }
    assertTrue(generated.source.contains("Color(0xFF2196F3)"))
    assertTrue("`Color` is a call", byText.contains("Color" to SourceTokenKind.CALL))
    assertTrue("its argument is a number", byText.contains("0xFF2196F3" to SourceTokenKind.NUMBER))
  }

  @Test
  fun `a TODO line is a comment, and so is the marker beside a bad value`() {
    val problems =
      ScreenCodegen.generate(corpus.getValue("everything unrepresentable at once"), specs)
    val comments =
      problems.tokens
        .filter { it.kind == SourceTokenKind.COMMENT }
        .map { problems.source.substring(it.start, it.end) }
    assertTrue(
      "every TODO codegen emitted is a comment token, got $comments",
      comments.any { it.startsWith("// TODO unknown component") } &&
        comments.any { it.startsWith("// TODO knob") } &&
        comments.any { it.startsWith("// TODO no slot") } &&
        comments.any { it.contains("takes no children") },
    )
    // The comment token covers the comment and not its indent, so a renderer colours the marker
    // rather than the whole line's leading whitespace.
    comments.forEach { assertTrue("`$it` should not carry indent", !it.startsWith(" ")) }

    val bad = ScreenCodegen.generate(corpus.getValue("every literal kind, all invalid"), specs)
    val badComments =
      bad.tokens
        .filter { it.kind == SourceTokenKind.COMMENT }
        .map { bad.source.substring(it.start, it.end) }
    assertEquals(
      "each of the five invalid values is marked",
      5,
      badComments.count { it == "/* TODO not a valid value */" },
    )
    assertTrue(
      "and the value itself is still a string",
      bad.tokens
        .filter { it.kind == SourceTokenKind.STRING }
        .any { bad.source.substring(it.start, it.end) == "\"abc\"" },
    )
  }

  @Test
  fun `an empty screen's placeholder is a comment`() {
    val generated = ScreenCodegen.generate(corpus.getValue("empty"), specs)
    val byText = generated.tokens.map { generated.source.substring(it.start, it.end) to it.kind }
    assertTrue(byText.contains("// (empty screen)" to SourceTokenKind.COMMENT))
  }
}
