package ee.schimke.composeai.data.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behavioural tests for the renderer-side [PreviewFilter]. Mirrors the plugin's
 * `PreviewNameFilterTest` in `:preview-discovery` — the two matchers must stay identical (see the
 * class KDoc), so the same cases exercised there are exercised here.
 */
class PreviewFilterTest {

  private data class Row(val id: String, val functionName: String, val className: String)

  private fun rows(vararg r: Row) = r.toList()

  private fun select(
    items: List<Row>,
    name: List<String> = emptyList(),
    id: List<String> = emptyList(),
    exclude: List<String> = emptyList(),
    failOnNoMatch: Boolean = true,
  ): List<Row> =
    PreviewFilter.select(
      items = items,
      nameFilters = name,
      idFilters = id,
      idExcludes = exclude,
      functionName = { it.functionName },
      className = { it.className },
      id = { it.id },
      failOnNoMatch = failOnNoMatch,
    )

  // --- matches (name / FQN) ---------------------------------------------------------------------

  @Test
  fun emptyPatternsMatchEverything() {
    assertTrue(PreviewFilter.matches(emptyList(), "FooPreview", "com.example.FooKt"))
    assertTrue(PreviewFilter.matchesId(emptyList(), "anything"))
  }

  @Test
  fun blankPatternsAreIgnored() {
    assertTrue(PreviewFilter.matches(listOf("  ", ""), "FooPreview", "com.example.FooKt"))
  }

  @Test
  fun plainPatternMatchesSimpleNameSubstringAndEquality() {
    assertTrue(PreviewFilter.matches(listOf("FooPreview"), "FooPreview", "com.example.FooKt"))
    assertTrue(PreviewFilter.matches(listOf("Foo"), "FooPreview", "com.example.FooKt"))
    assertFalse(PreviewFilter.matches(listOf("Bar"), "FooPreview", "com.example.FooKt"))
  }

  @Test
  fun plainPatternMatchesFullyQualifiedName() {
    assertTrue(
      PreviewFilter.matches(listOf("com.example.FooPreview"), "FooPreview", "com.example.FooKt")
    )
  }

  @Test
  fun fqNameUsesPackageNotSyntheticHolderClass() {
    assertEquals("com.example.FooPreview", PreviewFilter.fqName("com.example.FooKt", "FooPreview"))
    assertEquals("FooPreview", PreviewFilter.fqName("FooKt", "FooPreview"))
  }

  @Test
  fun globAnchorsAndTreatsDotAsLiteral() {
    assertTrue(PreviewFilter.matches(listOf("*Preview"), "FooPreview", "com.example.FooKt"))
    assertTrue(PreviewFilter.matches(listOf("Foo*"), "FooPreview", "com.example.FooKt"))
    assertTrue(PreviewFilter.matches(listOf("com.example.*"), "FooPreview", "com.example.FooKt"))
    // A glob is a full anchored match, not a substring: "Foo" alone does not match "FooPreview".
    assertFalse(PreviewFilter.matches(listOf("Fo?"), "FooPreview", "com.example.FooKt"))
    assertTrue(PreviewFilter.matches(listOf("Fo?Preview"), "FooPreview", "com.example.FooKt"))
    // The '.' in the pattern is literal, so it can't match an arbitrary char.
    assertFalse(
      PreviewFilter.matches(listOf("comXexample.FooPreview"), "FooPreview", "com.example.FooKt")
    )
  }

  @Test
  fun matchingIsCaseSensitive() {
    assertFalse(PreviewFilter.matches(listOf("foopreview"), "FooPreview", "com.example.FooKt"))
  }

  // --- select composition -----------------------------------------------------------------------

  @Test
  fun nameThenIdThenExcludeCompose() {
    val items =
      rows(
        Row("Foo_Light", "Foo", "com.example.FooKt"),
        Row("Foo_Dark", "Foo", "com.example.FooKt"),
        Row("Bar_Light", "Bar", "com.example.BarKt"),
      )
    // name keeps Foo's two members; id narrows to the light one.
    assertEquals(
      listOf("Foo_Light"),
      select(items, name = listOf("Foo"), id = listOf("*_Light")).map { it.id },
    )
    // exclude drops dark members across the board.
    assertEquals(
      listOf("Foo_Light", "Bar_Light"),
      select(items, exclude = listOf("*_Dark")).map { it.id },
    )
  }

  @Test
  fun noFilterReturnsEverythingUnchanged() {
    val items = rows(Row("a", "A", "p.AKt"), Row("b", "B", "p.BKt"))
    assertEquals(items, select(items))
  }

  // --- fail-fast --------------------------------------------------------------------------------

  @Test
  fun nameFilterMatchingNothingThrowsWithAvailableList() {
    val items = rows(Row("Foo_Light", "Foo", "com.example.FooKt"))
    val e = assertFailsWith<IllegalStateException> { select(items, name = listOf("Nope")) }
    assertTrue(e.message!!.contains("--preview matched no previews"))
    assertTrue(e.message!!.contains("com.example.Foo"))
  }

  @Test
  fun idFilterMatchingNothingThrows() {
    val items = rows(Row("Foo_Light", "Foo", "com.example.FooKt"))
    assertFailsWith<IllegalStateException> { select(items, id = listOf("*_Dark")) }
  }

  @Test
  fun excludeRemovingEverythingThrows() {
    val items = rows(Row("Foo_Dark", "Foo", "com.example.FooKt"))
    assertFailsWith<IllegalStateException> { select(items, exclude = listOf("*_Dark")) }
  }

  @Test
  fun excludeMatchingNothingIsANoOp() {
    val items = rows(Row("Foo_Light", "Foo", "com.example.FooKt"))
    assertEquals(listOf("Foo_Light"), select(items, exclude = listOf("*_Dark")).map { it.id })
  }

  // --- failOnNoMatch = false (kind-restricted sibling view, e.g. XR) ----------------------------

  @Test
  fun siblingViewReturnsEmptyInsteadOfThrowingOnNoNameMatch() {
    val items = rows(Row("Xr_1", "XrPreview", "com.example.XrKt"))
    // A filter naming a non-XR preview matches nothing in this XR-only view — no throw, empty
    // result.
    assertEquals(
      emptyList(),
      select(items, name = listOf("RegularComposable"), failOnNoMatch = false),
    )
  }

  @Test
  fun siblingViewReturnsEmptyOnNoIdMatchAndFullExclude() {
    val items = rows(Row("Xr_dark", "XrPreview", "com.example.XrKt"))
    assertEquals(emptyList(), select(items, id = listOf("nope"), failOnNoMatch = false))
    assertEquals(emptyList(), select(items, exclude = listOf("*_dark"), failOnNoMatch = false))
  }

  @Test
  fun siblingViewStillMatchesWhenPatternHits() {
    val items = rows(Row("Xr_1", "XrPreview", "com.example.XrKt"), Row("Xr_2", "Other", "p.OKt"))
    assertEquals(
      listOf("Xr_1"),
      select(items, name = listOf("XrPreview"), failOnNoMatch = false).map { it.id },
    )
  }

  // --- system-property parsing ------------------------------------------------------------------

  @Test
  fun patternsFromSplitsTrimsAndDropsBlanks() {
    val read = mapOf("k" to " A , B ,, C ")::get
    assertEquals(listOf("A", "B", "C"), PreviewFilter.patternsFrom("k", read))
  }

  @Test
  fun patternsFromAbsentPropertyIsEmpty() {
    assertEquals(emptyList(), PreviewFilter.patternsFrom("missing") { null })
  }

  // --- @PreviewParameter row exclusions ---------------------------------------------------------

  /** What `PreviewParameterLabels.suffixesFor` hands the Robolectric expansion for four values. */
  private val rowSuffixes = listOf("_Amber", "_Crimson", "_Teal", "_Violet")

  @Test
  fun keptRowIndicesWithNoPatternsKeepsEveryRow() {
    assertEquals(listOf(0, 1, 2, 3), PreviewFilter.keptRowIndices(rowSuffixes, emptyList()))
    assertEquals(listOf(0, 1, 2, 3), PreviewFilter.keptRowIndices(rowSuffixes, listOf(" ", "")))
  }

  @Test
  fun keptRowIndicesDropsAnExactLabel() {
    assertEquals(listOf(0, 2, 3), PreviewFilter.keptRowIndices(rowSuffixes, listOf("Crimson")))
  }

  @Test
  fun keptRowIndicesMatchesLabelsCaseInsensitively() {
    // The motivating case: a spec says `modePriority: { dark: deferred }` while the provider value
    // labels itself `Dark`. Unlike `matchesId`, the row axis folds case.
    assertEquals(listOf(0, 1, 3), PreviewFilter.keptRowIndices(rowSuffixes, listOf("teal")))
  }

  @Test
  fun keptRowIndicesMatchesGlobs() {
    assertEquals(listOf(1, 3), PreviewFilter.keptRowIndices(rowSuffixes, listOf("?ea*", "Amber")))
    // Anchored: a bare substring is not a glob and must not match.
    assertEquals(listOf(0, 1, 2, 3), PreviewFilter.keptRowIndices(rowSuffixes, listOf("mber")))
  }

  @Test
  fun keptRowIndicesIgnoresTheLeadingUnderscore() {
    // A caller writes what they read off the filename (`Foo_Crimson.png` -> `Crimson`).
    assertEquals(listOf(0, 1, 2, 3), PreviewFilter.keptRowIndices(rowSuffixes, listOf("_Crimson")))
  }

  @Test
  fun keptRowIndicesNeverEmptiesTheRowSet() {
    // A preview that rendered nothing would publish as a component with no pixels — a misconfigured
    // exclusion, not a deferral. Same never-empty rule the desktop renderer applies.
    assertEquals(listOf(0, 1, 2, 3), PreviewFilter.keptRowIndices(rowSuffixes, listOf("*")))
  }

  @Test
  fun keptRowIndicesLeavesAnUnparameterizedPreviewAlone() {
    // The single empty suffix means "no fan-out": no rows, so a row pattern must not delete its
    // only
    // render.
    assertEquals(listOf(0), PreviewFilter.keptRowIndices(listOf(""), listOf("*")))
    assertEquals(listOf(0), PreviewFilter.keptRowIndices(listOf(""), listOf("Dark")))
  }

  @Test
  fun keptRowIndicesAddressesUnlabelledRowsByIndexForm() {
    assertEquals(
      listOf(0),
      PreviewFilter.keptRowIndices(listOf("_PARAM_0", "_PARAM_1"), listOf("PARAM_1")),
    )
  }

  @Test
  fun rowExcludePropertyIsTheWireContractWithThePlugin() {
    assertEquals("composeai.preview.rowExclude", PreviewFilter.ROW_EXCLUDE_PROPERTY)
  }
}
