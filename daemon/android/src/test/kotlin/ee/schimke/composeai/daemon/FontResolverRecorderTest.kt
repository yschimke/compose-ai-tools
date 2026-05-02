package ee.schimke.composeai.daemon

import androidx.compose.ui.text.font.FontFamily
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FontResolverRecorderTest {

  @Test
  fun `recording resolver preserves reflexive equals`() {
    val delegate = proxyResolver()
    val resolver = recordingFontFamilyResolver(delegate, FontResolverRecorder())

    assertTrue(resolver.equals(resolver))
    assertFalse(resolver.equals(delegate))
    assertEquals(System.identityHashCode(resolver), resolver.hashCode())
  }

  private fun proxyResolver(): FontFamily.Resolver =
    Proxy.newProxyInstance(
        FontFamily.Resolver::class.java.classLoader,
        arrayOf(FontFamily.Resolver::class.java),
      ) { proxy, method, args ->
        when (method.name) {
          "toString" -> "DelegateFontFamilyResolver"
          "hashCode" -> System.identityHashCode(proxy)
          "equals" -> proxy === args?.firstOrNull()
          else -> error("Unexpected resolver method in equals test: ${method.name}")
        }
      }
      as FontFamily.Resolver
}
