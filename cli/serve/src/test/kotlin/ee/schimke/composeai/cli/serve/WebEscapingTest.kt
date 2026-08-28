package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.web.WebEscaping
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebEscapingTest {
  @Test
  fun `html escape covers both quote styles at the interpolation boundary`() {
    assertEquals("&amp;&lt;&gt;&quot;&#39;", WebEscaping.htmlEscape("&<>\"'"))
  }

  @Test
  fun `javascript string cannot close an inline script element`() {
    val escaped = WebEscaping.jsString("</script><img src=x onerror=alert(1)>&")

    assertTrue(escaped.startsWith("\"") && escaped.endsWith("\""), escaped)
    assertFalse(escaped.contains("</script>"), escaped)
    assertTrue(escaped.contains("\\u003c/script\\u003e"), escaped)
    assertTrue(escaped.contains("\\u0026"), escaped)
  }

  @Test
  fun `url path segment encoding keeps attacker influenced preview ids in one segment`() {
    assertEquals("a%2Fb%3Fc%23d%20e%27f", WebEscaping.urlEncodeSegment("a/b?c#d e'f"))
  }
}
