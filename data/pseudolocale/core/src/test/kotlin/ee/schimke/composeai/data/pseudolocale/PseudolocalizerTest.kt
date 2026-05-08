package ee.schimke.composeai.data.pseudolocale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PseudolocalizerTest {

  @Test
  fun `accent maps ASCII letters and brackets the result`() {
    val out = Pseudolocalizer.accent("Hello")
    assertTrue("output starts with [: $out", out.startsWith("["))
    assertTrue("output ends with ]: $out", out.endsWith("]"))
    assertTrue("contains accented form: $out", out.contains("Ĥêļļö"))
  }

  @Test
  fun `accent expands string by ~30 percent`() {
    val input = "The quick brown fox"
    val out = Pseudolocalizer.accent(input)
    assertTrue(
      "output ($out, ${out.length}) is at least 30% longer than input",
      out.length >= input.length * 1.3,
    )
  }

  @Test
  fun `accent preserves printf placeholders`() {
    val out = Pseudolocalizer.accent("Hello, %1\$s!")
    assertTrue("placeholder preserved: $out", out.contains("%1\$s"))
  }

  @Test
  fun `accent preserves ICU placeholders`() {
    val out = Pseudolocalizer.accent("Hello, {name}!")
    assertTrue("placeholder preserved: $out", out.contains("{name}"))
  }

  @Test
  fun `accent preserves xml tags`() {
    val out = Pseudolocalizer.accent("Click <b>here</b>")
    assertTrue("open tag preserved: $out", out.contains("<b>"))
    assertTrue("close tag preserved: $out", out.contains("</b>"))
  }

  @Test
  fun `bidi wraps each word with rlo and pdf marks`() {
    val out = Pseudolocalizer.bidi("hello world")
    val rlo = '‮'
    val pdf = '‬'
    assertEquals("$rlo" + "hello" + "$pdf $rlo" + "world" + "$pdf", out)
  }

  @Test
  fun `fromTag recognises pseudolocales case insensitively`() {
    assertEquals(Pseudolocale.ACCENT, Pseudolocale.fromTag("en-XA"))
    assertEquals(Pseudolocale.ACCENT, Pseudolocale.fromTag("en_xa"))
    assertEquals(Pseudolocale.BIDI, Pseudolocale.fromTag("ar-XB"))
    assertNull(Pseudolocale.fromTag("en-US"))
    assertNull(Pseudolocale.fromTag(null))
    assertNull(Pseudolocale.fromTag(""))
  }
}
