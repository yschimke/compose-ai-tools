package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MaterialThemePayloadReflectionFacadeTest {
  @Test
  fun readsColorSchemeFromThemePayloadFacade() {
    val colors = mapOf("background" to "#FFFFFFFF")
    val payload = ThemePayloadLike(ResolvedTokensLike(colors))

    val result = MaterialThemePayloadReflectionFacade.colorScheme(payload)

    assertEquals(colors, result)
  }

  @Test
  fun returnsNullForUnknownPayloadShape() {
    val result = MaterialThemePayloadReflectionFacade.colorScheme(Any())

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
