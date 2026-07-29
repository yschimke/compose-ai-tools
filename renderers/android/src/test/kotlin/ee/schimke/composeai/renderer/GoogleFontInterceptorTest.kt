package ee.schimke.composeai.renderer

import androidx.compose.ui.text.font.FontWeight
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for [GoogleFontCache], [GoogleFontKey], the CSS-API helpers, and the
 * `FontRequest.query` parser that underpins [ShadowFontsContractCompat].
 *
 * No network access: the download path is stubbed with a canned byte array so the test is
 * deterministic. No Robolectric runner either — these are pure JVM helpers, and the shadow is
 * exercised end-to-end via `:samples:android:composePreviewRenderAll`.
 */
class GoogleFontInterceptorTest {
  @Test
  fun `parseFontRequestQuery reads the Compose GoogleFont wire format`() {
    val query = "name=Roboto%20Mono&weight=500&width=100.0&italic=0.0&besteffort=true"
    val key = parseFontRequestQuery(query)
    assertNotNull(key)
    assertEquals("Roboto Mono", key!!.name)
    assertEquals(500, key.weight)
    assertFalse(key.italic)
  }

  @Test
  fun `parseFontRequestQuery treats italic floats above half as italic`() {
    val key = parseFontRequestQuery("name=Inter&weight=700&italic=1.0&besteffort=true")
    assertNotNull(key)
    assertTrue(key!!.italic)
    assertEquals(700, key.weight)
  }

  @Test
  fun `parseFontRequestQuery defaults missing weight to 400 and italic to false`() {
    val key = parseFontRequestQuery("name=Inter&besteffort=true")
    assertNotNull(key)
    assertEquals(400, key!!.weight)
    assertFalse(key.italic)
  }

  @Test
  fun `parseFontRequestQuery returns null when name is missing or blank`() {
    assertNull(parseFontRequestQuery(null))
    assertNull(parseFontRequestQuery("weight=400&italic=0.0"))
    assertNull(parseFontRequestQuery("name=&weight=400"))
  }
}
