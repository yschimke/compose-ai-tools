package ee.schimke.composeai.data.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeDiffTest {

  private fun payload(
    colors: Map<String, String> = emptyMap(),
    shapes: Map<String, String> = emptyMap(),
    typography: Map<String, TypographyToken> = emptyMap(),
  ): ThemePayload = ThemePayload(ResolvedThemeTokens(colors, typography, shapes))

  @Test
  fun identical_tokens_yield_empty_delta() {
    val p = payload(colors = mapOf("primary" to "0xFF6750A4"), shapes = mapOf("large" to "16.0dp"))
    val delta = ThemeDiff.diff(p, p)
    assertTrue(delta.isEmpty)
    // Versioned schema discriminator is always set.
    assertEquals(ThemeDiffProduct.SCHEMA, delta.schema)
  }

  @Test
  fun changed_color_reports_from_to() {
    val base = payload(colors = mapOf("primary" to "0xFF6750A4"))
    val head = payload(colors = mapOf("primary" to "0xFFB3261E"))
    val delta = ThemeDiff.diff(base, head)
    assertEquals(1, delta.colorScheme.size)
    val change = delta.colorScheme.single()
    assertEquals("primary", change.token)
    assertEquals("0xFF6750A4", change.from)
    assertEquals("0xFFB3261E", change.to)
  }

  @Test
  fun added_and_removed_tokens_have_null_on_the_absent_side() {
    val base = payload(colors = mapOf("primary" to "0xFF000000"))
    val head = payload(colors = mapOf("tertiary" to "0xFF7D5260"))
    val delta = ThemeDiff.diff(base, head)
    // Sorted by token name: "primary" (removed) before "tertiary" (added).
    assertEquals(listOf("primary", "tertiary"), delta.colorScheme.map { it.token })
    val removed = delta.colorScheme.first { it.token == "primary" }
    assertEquals("0xFF000000", removed.from)
    assertEquals(null, removed.to)
    val added = delta.colorScheme.first { it.token == "tertiary" }
    assertEquals(null, added.from)
    assertEquals("0xFF7D5260", added.to)
  }

  @Test
  fun shape_changes_are_reported() {
    val base = payload(shapes = mapOf("large" to "16.0dp"))
    val head = payload(shapes = mapOf("large" to "28.0dp"))
    val delta = ThemeDiff.diff(base, head)
    assertTrue(delta.colorScheme.isEmpty())
    assertEquals(1, delta.shapes.size)
    assertEquals("28.0dp", delta.shapes.single().to)
  }

  @Test
  fun typography_token_change_is_reported_whole() {
    val base = payload(typography = mapOf("bodyLarge" to TypographyToken(fontSize = 16f)))
    val head = payload(typography = mapOf("bodyLarge" to TypographyToken(fontSize = 18f)))
    val delta = ThemeDiff.diff(base, head)
    assertEquals(1, delta.typography.size)
    val change = delta.typography.single()
    assertEquals("bodyLarge", change.token)
    assertEquals(16f, change.from?.fontSize)
    assertEquals(18f, change.to?.fontSize)
  }
}
