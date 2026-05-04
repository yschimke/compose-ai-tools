package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MaterialThemePayloadSnapshotTest {
  @Test
  fun readsColorSchemeFromThemePayload() {
    val colors = mapOf("background" to "#FFFFFFFF")
    val payload = ThemePayloadLike(ResolvedTokensLike(colors))

    val result = MaterialThemePayloadSnapshot.colorScheme(payload)

    assertEquals(colors, result)
  }

  @Test
  fun returnsNullForUnknownPayloadShape() {
    val result = MaterialThemePayloadSnapshot.colorScheme(Any())

    assertNull(result)
  }

  @Suppress("unused")
  private class ThemePayloadLike(private val resolvedTokens: ResolvedTokensLike) {
    fun getResolvedTokens(): ResolvedTokensLike = resolvedTokens
  }

  @Suppress("unused")
  private class ResolvedTokensLike(private val colorScheme: Map<String, String>) {
    fun getColorScheme(): Map<String, String> = colorScheme
  }
}
