package ee.schimke.composeai.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Row-label matching for `@PreviewParameter` row addressing (issue #3749).
 *
 * The subtle case is case-folding. [PreviewParameterLabels] compares labels case-*sensitively* when
 * deciding whether a fan-out collides, so a provider yielding `Dark` and `dark` legitimately writes
 * two files; a resolver that folds case unconditionally maps both ids onto the first value and
 * silently renders the wrong state for the second.
 */
class PreviewParameterRowMatchTest {

  @Test
  fun `exact match wins`() {
    assertEquals(0, PreviewParameterSupport.matchLabel(listOf("Dark", "dark"), "Dark"))
    assertEquals(1, PreviewParameterSupport.matchLabel(listOf("Dark", "dark"), "dark"))
  }

  @Test
  fun `case-insensitive fallback resolves when exactly one row matches`() {
    assertEquals(1, PreviewParameterSupport.matchLabel(listOf("Light", "Dark"), "dark"))
    assertEquals(0, PreviewParameterSupport.matchLabel(listOf("Crimson", "Teal"), "CRIMSON"))
  }

  /** Two rows differing only by case make a non-exact request genuinely ambiguous — refuse it. */
  @Test
  fun `case-insensitive fallback refuses an ambiguous request`() {
    assertNull(PreviewParameterSupport.matchLabel(listOf("Dark", "dark"), "DARK"))
  }

  @Test
  fun `no match is null`() {
    assertNull(PreviewParameterSupport.matchLabel(listOf("Light", "Dark"), "Amber"))
    assertNull(PreviewParameterSupport.matchLabel(emptyList(), "Dark"))
  }
}
