package ee.schimke.composeai.renderer

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewRowFilterTest {

  /** What `PreviewParameterLabels.suffixesFor` hands the render loop for a four-value provider. */
  private val rows = listOf("_Amber", "_Crimson", "_Teal", "_Violet")

  @Test
  fun `no patterns keeps every row`() {
    assertEquals(listOf(0, 1, 2, 3), PreviewRowFilter.keptRows(rows, emptyList()))
  }

  @Test
  fun `an exact label drops that row`() {
    assertEquals(listOf(0, 2, 3), PreviewRowFilter.keptRows(rows, listOf("Crimson")))
  }

  @Test
  fun `the leading underscore is not part of the label`() {
    // A caller writes what they read off the filename (`Foo_Crimson.png` → `Crimson`).
    assertEquals(rows.indices.toList(), PreviewRowFilter.keptRows(rows, listOf("_Crimson")))
  }

  @Test
  fun `labels match case-insensitively`() {
    // The motivating case: a spec says `modePriority: { dark: deferred }` while the provider value
    // labels itself `Dark`.
    assertEquals(listOf(0, 1, 3), PreviewRowFilter.keptRows(rows, listOf("teal")))
  }

  @Test
  fun `a glob drops a family of rows`() {
    assertEquals(listOf(1, 3), PreviewRowFilter.keptRows(rows, listOf("?ea*", "Amber")))
  }

  @Test
  fun `a glob is anchored and case-insensitive`() {
    assertEquals(rows.indices.toList(), PreviewRowFilter.keptRows(rows, listOf("mber")))
    assertEquals(listOf(1, 2, 3), PreviewRowFilter.keptRows(rows, listOf("amb*")))
  }

  @Test
  fun `a pattern matching nothing keeps every row`() {
    // Exclusion polarity: a stale label renders too much, never too little.
    assertEquals(rows.indices.toList(), PreviewRowFilter.keptRows(rows, listOf("Chartreuse")))
  }

  @Test
  fun `excluding every row keeps them all`() {
    // A preview that rendered nothing would publish as a component with no pixels — a misconfigured
    // exclusion, not a deferral. Same never-empty rule the catalog derivation applies upstream.
    assertEquals(rows.indices.toList(), PreviewRowFilter.keptRows(rows, listOf("*")))
  }

  @Test
  fun `a preview with no fan-out is never filtered`() {
    // The single empty suffix means "not parameterized": it has no rows, so a row pattern must not
    // be
    // able to delete its only render.
    assertEquals(listOf(0), PreviewRowFilter.keptRows(listOf(""), listOf("*")))
    assertEquals(listOf(0), PreviewRowFilter.keptRows(listOf(""), listOf("Dark")))
  }

  @Test
  fun `unlabelled rows are addressable by their PARAM index form`() {
    val indexed = listOf("_PARAM_0", "_PARAM_1")
    assertEquals(listOf(0), PreviewRowFilter.keptRows(indexed, listOf("PARAM_1")))
  }

  @Test
  fun `patterns are split on commas with blanks dropped`() {
    assertEquals(listOf("Dark", "Extra_Dark"), PreviewRowFilter.patterns(" Dark, Extra_Dark ,, "))
    assertEquals(emptyList<String>(), PreviewRowFilter.patterns(null))
    assertEquals(emptyList<String>(), PreviewRowFilter.patterns(""))
  }
}
