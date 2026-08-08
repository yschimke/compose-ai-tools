package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the declaration slice — the part of the playground handoff that decides whether a visitor
 * lands on one composable or on the whole file it shares with four others.
 *
 * The fixture is shaped like the real thing that motivated this: a section file from m3-catalog,
 * where one group is one file, every component carries a variant matrix as stacked
 * `@OverrideVariant` lines, and `Buttons.kt` is 242 lines of which the composable a visitor clicked
 * is about fifteen.
 */
class PlaygroundSeedSliceTest {

  /**
   * Two components in one file, the first carrying KDoc and a matrix. Line numbers matter, so the
   * anchors below are resolved against this text by [lineOf] rather than hard-coded.
   */
  private val sectionFile =
    """
    @file:CatalogGroup(name = "Buttons", section = "Actions")
    @file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

    package ee.schimke.m3catalog.sections

    import androidx.compose.material3.Button
    import androidx.compose.material3.Text
    import androidx.compose.runtime.Composable
    import ee.schimke.composeai.preview.OverrideVariant

    // The five common M3 buttons, highest to lowest emphasis.

    /** The button's label at the type scale its size carries. */
    @CatalogComponent(id = "Button/Filled", caption = "Highest emphasis.")
    @CatalogModes
    @OverrideVariant(name = "xs", strings = ["size=xs"])
    @OverrideVariant(name = "xs-square", strings = ["size=xs", "shape=square"])
    @Composable
    fun FilledButton() = Sticker {
      val c = counted("Filled")
      Button(onClick = c.onClick) {
        Text(c.label)
      }
    }

    @CatalogComponent(id = "Button/Tonal", caption = "Secondary.")
    @CatalogModes
    @Composable
    fun TonalButton() = Sticker {
      Text("Tonal")
    }
    """
      .trimIndent()

  private fun lineOf(needle: String): Int =
    sectionFile.lines().indexOfFirst { it.contains(needle) } + 1

  @Test
  fun `a declaration slice carries the header, the annotations and the KDoc`() {
    // The anchor is the body's first statement — what the line table reports — so the `fun` line
    // above it and the closing braces below it both have to be recovered by the walk.
    val slice =
      PlaygroundSeedResolver.sliceDeclaration(sectionFile, lineOf("""val c = counted("Filled")"""))
    assertNotNull(slice)

    // Header, verbatim and whole — including the file annotations an experimental-API body needs.
    assertTrue(slice.contains("@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)"))
    assertTrue(slice.contains("package ee.schimke.m3catalog.sections"))
    assertTrue(slice.contains("import androidx.compose.material3.Button"))
    assertTrue(slice.contains("import ee.schimke.composeai.preview.OverrideVariant"))

    // The declaration, with the annotation stack and the KDoc above it — the blank-line walk picks
    // those up without a doc-comment-matching special case.
    assertTrue(slice.contains("/** The button's label at the type scale its size carries. */"))
    assertTrue(slice.contains("""@CatalogComponent(id = "Button/Filled""""))
    assertTrue(slice.contains("""@OverrideVariant(name = "xs-square""""))
    assertTrue(slice.contains("fun FilledButton() = Sticker {"))
    assertTrue(slice.contains("""val c = counted("Filled")"""))

    // …and nothing belonging to the sibling component.
    assertFalse(slice.contains("TonalButton"))
    assertFalse(slice.contains("""Button/Tonal"""))

    // The free-standing comment between the imports and the first declaration belongs to neither,
    // and is dropped with the rest of the file's body.
    assertFalse(slice.contains("highest to lowest emphasis"))
  }

  @Test
  fun `the last declaration in a file slices without running off the end`() {
    val slice = PlaygroundSeedResolver.sliceDeclaration(sectionFile, lineOf("""Text("Tonal")"""))
    assertNotNull(slice)
    assertTrue(slice.contains("fun TonalButton() = Sticker {"))
    assertTrue(slice.trimEnd().endsWith("}"))
    assertFalse(slice.contains("FilledButton"))
  }

  /**
   * A blank line inside a body is ordinary formatted Kotlin, not an oddity — this fixture is
   * `samples/cmp`'s `OverridableListPreview`, which separates its `previewOverride*` declarations
   * from the `Surface` they feed. The anchor is the *first* statement, so an end rule that stopped
   * at the first blank line would cut the declaration off before `Surface` and emit a buffer with
   * unbalanced braces: not a smaller slice, an uncompilable one.
   */
  @Test
  fun `a blank line inside the body does not end the declaration`() {
    val withBlankLine =
      """
      package example

      import androidx.compose.runtime.Composable

      @Preview(name = "Overridable List", showBackground = true)
      @Composable
      fun OverridableListPreview() {
        val title = previewOverrideString("title", default = "Shopping list")
        val itemCount = previewOverrideInt("itemCount", default = 3)

        Surface {
          Column {
            Text(title)

            repeat(itemCount) { i -> Text("Item ${'$'}i") }
          }
        }
      }

      @Composable
      fun Unrelated() {
        Text("nope")
      }
      """
        .trimIndent()
    val anchor = withBlankLine.lines().indexOfFirst { it.contains("val title") } + 1

    val slice = PlaygroundSeedResolver.sliceDeclaration(withBlankLine, anchor)
    assertNotNull(slice)
    // Everything past the internal blank lines, including the closing braces.
    assertTrue(slice.contains("Surface {"))
    assertTrue(slice.contains("repeat(itemCount)"))
    // Balanced: the declaration was not truncated mid-body.
    assertEquals(slice.count { it == '{' }, slice.count { it == '}' })
    // …and still bounded at the next declaration.
    assertFalse(slice.contains("Unrelated"))
  }

  /**
   * The counterpart guard: a top-level closing brace is at column 0, so a boundary test that looked
   * only at indentation would end every declaration at its own `}` minus one line.
   */
  @Test
  fun `a declaration is not ended by its own closing brace`() {
    val slice =
      PlaygroundSeedResolver.sliceDeclaration(sectionFile, lineOf("""val c = counted("Filled")"""))
    assertNotNull(slice)
    assertTrue(slice.trimEnd().endsWith("}"))
    assertEquals(slice.count { it == '{' }, slice.count { it == '}' })
  }

  @Test
  fun `no anchor seeds the whole file`() {
    assertNull(PlaygroundSeedResolver.sliceDeclaration(sectionFile, null))
  }

  /**
   * A branch `ref` keeps the cache key stable while the file under it moves, so an anchor from an
   * older manifest can point past the end of the text now being sliced. Slicing on a stale offset
   * would hand over an arbitrary fragment; the whole file is the honest answer.
   *
   * The out-of-range case is not hypothetical, which is why the anchor is a single line rather than
   * the span the classfile appears to offer: Kotlin emits an inlined function body into its caller
   * with SMAP line numbers past the end of the caller's file, so a method's *last* line routinely
   * points at nothing. Measured over m3-catalog, 9 of 244 methods with line info reported an end
   * beyond their own file — and every one of their starts was in range.
   */
  @Test
  fun `an anchor outside the file seeds the whole file`() {
    assertNull(PlaygroundSeedResolver.sliceDeclaration(sectionFile, 500))
    assertNull(PlaygroundSeedResolver.sliceDeclaration(sectionFile, 0))
    // A blank line is in range but says the source moved — the walk from it would collapse.
    assertNull(PlaygroundSeedResolver.sliceDeclaration(sectionFile, lineOf("package ") - 1))
  }

  @Test
  fun `a single-declaration file is not sliced into a copy of itself`() {
    val single =
      """
      package example

      import androidx.compose.runtime.Composable

      @Composable
      fun Only() {
        Text("hi")
      }
      """
        .trimIndent()
    // Slicing this yields the file back, so there is nothing to gain and the seed stays whole-file.
    assertNull(PlaygroundSeedResolver.sliceDeclaration(single, 7))
  }

  @Test
  fun `a file with no imports or package still slices`() {
    val loose =
      """
      @Composable
      fun A() {
        Text("a")
      }

      @Composable
      fun B() {
        Text("b")
      }
      """
        .trimIndent()
    val slice = PlaygroundSeedResolver.sliceDeclaration(loose, 7)
    assertNotNull(slice)
    assertEquals("@Composable\nfun B() {\n  Text(\"b\")\n}", slice)
  }

  /** End to end through the resolver, so the flag the editor's note reads is pinned too. */
  @Test
  fun `the resolver reports whether it sliced`() {
    fun seedWith(bodyLine: Int?) =
      PlaygroundSeedResolver(
          locate = { _, _ ->
            PlaygroundSeedResolver.Location(
              repo = "yschimke/m3-catalog",
              ref = "main",
              module = ":catalog",
              sourceFile = "src/main/kotlin/ee/schimke/m3catalog/sections/Buttons.kt",
              bodyLine = bodyLine,
            )
          },
          fetch = { sectionFile.toByteArray() },
        )
        .seed("m3-catalog", "…FilledButton_Light")

    val whole = seedWith(null)
    assertNotNull(whole)
    assertFalse(whole.sliced)
    assertEquals(sectionFile, whole.text)

    val sliced = seedWith(lineOf("""val c = counted("Filled")"""))
    assertNotNull(sliced)
    assertTrue(sliced.sliced)
    assertTrue(sliced.text.length < whole.text.length)
    assertFalse(sliced.text.contains("TonalButton"))
    // The tab is still named for the file the declaration came from.
    assertEquals("Buttons.kt", sliced.fileName)
  }
}
