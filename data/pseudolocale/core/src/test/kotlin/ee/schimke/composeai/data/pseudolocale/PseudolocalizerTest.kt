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
  fun `accent indexMap places ASCII letters right after the opening bracket`() {
    val result = Pseudolocalizer.transformWithIndices("Hello", Pseudolocale.ACCENT)
    assertTrue("output starts with [: ${result.text}", result.text.startsWith("["))
    // Input 0 (`H`) should map to output position 1 (right after `[`).
    assertEquals(1, result.indexMap[0])
    // Input 4 (`o`) should map to output position 5.
    assertEquals(5, result.indexMap[4])
    // End-of-input (5) maps to position 6 — the slot right after `o`, before space + dots + `]`.
    assertEquals(6, result.indexMap[5])
    // The character at the end-of-input slot should be the accented form of `o` — padding
    // intervenes between this slot and the closing `]`.
    assertEquals('ö', result.text[result.indexMap[5] - 1])
  }

  @Test
  fun `accent indexMap respects placeholder preservation`() {
    val result = Pseudolocalizer.transformWithIndices("a%1\$sb", Pseudolocale.ACCENT)
    // Input index 0 (`a`) → after `[`.
    assertEquals(1, result.indexMap[0])
    // Input index 5 (`b`) sits after the 4-char `%1\$s` placeholder copied verbatim — output
    // position should be 1 (open bracket) + 1 (accented `a`) + 4 (placeholder) = 6.
    assertEquals(6, result.indexMap[5])
  }

  @Test
  fun `bidi indexMap maps word chars to positions inside RLO PDF wrap`() {
    val result = Pseudolocalizer.transformWithIndices("hi", Pseudolocale.BIDI)
    // Output is RLO `h` `i` PDF — input 0 (`h`) → output 1, input 1 (`i`) → output 2.
    assertEquals(1, result.indexMap[0])
    assertEquals(2, result.indexMap[1])
    // End-of-input (2) → after PDF (4).
    assertEquals(4, result.indexMap[2])
  }

  @Test
  fun `bidi indexMap handles whitespace between words`() {
    val result = Pseudolocalizer.transformWithIndices("hi yo", Pseudolocale.BIDI)
    // RLO `h` `i` PDF ` ` RLO `y` `o` PDF — input positions:
    //   0 → 1 (`h`)
    //   1 → 2 (`i`)
    //   2 → 4 (` `)
    //   3 → 6 (`y`)
    //   4 → 7 (`o`)
    //   5 → 9 (after PDF)
    assertEquals(1, result.indexMap[0])
    assertEquals(2, result.indexMap[1])
    assertEquals(4, result.indexMap[2])
    assertEquals(6, result.indexMap[3])
    assertEquals(7, result.indexMap[4])
    assertEquals(9, result.indexMap[5])
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
