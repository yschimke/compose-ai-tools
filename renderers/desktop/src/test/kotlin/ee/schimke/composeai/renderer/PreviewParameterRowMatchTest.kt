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

  /**
   * The index lane shares [PreviewParameterSupport.MAX_ROW_SCAN] as a ceiling, checked before any
   * enumeration: `n` comes from a caller-supplied previewId and the annotation's `limit` defaults
   * to `Int.MAX_VALUE`, so an unbounded `Screen_PARAM_100000000` would ask an infinite provider for
   * a hundred million values.
   */
  @Test
  fun `an index beyond the ceiling is rejected without enumerating`() {
    var enumerated = false
    val provider =
      object {
        @Suppress("unused")
        val values: Sequence<String> =
          generateSequence("x") {
            enumerated = true
            "x"
          }
      }
    val failure =
      runCatching {
          PreviewParameterSupport.resolve(
            clazz = provider.javaClass,
            functionName = "Irrelevant",
            providerClassName = provider.javaClass.name,
            row = "PARAM_${PreviewParameterSupport.MAX_ROW_SCAN}",
          )
        }
        .exceptionOrNull()
    assertEquals(false, enumerated)
    assertEquals(
      true,
      failure?.message?.contains("beyond the ${PreviewParameterSupport.MAX_ROW_SCAN}-row"),
    )
  }

  /**
   * `PARAM_-0` is a label, not a position. The reserved-label grammar is digits-only, so label
   * derivation keeps that spelling — and `"-0".toIntOrNull()` returning 0 would otherwise make the
   * parser bind value 0 instead of the labelled row that is actually on disk.
   */
  @Test
  fun `signed and non-digit index spellings stay labels`() {
    assertEquals(1, PreviewParameterSupport.matchLabel(listOf("Dark", "PARAM_-0"), "PARAM_-0"))
    assertEquals(1, PreviewParameterSupport.matchLabel(listOf("Dark", "PARAM_+1"), "PARAM_+1"))
    assertEquals(1, PreviewParameterSupport.matchLabel(listOf("Dark", "PARAM_x"), "PARAM_x"))
  }
}
