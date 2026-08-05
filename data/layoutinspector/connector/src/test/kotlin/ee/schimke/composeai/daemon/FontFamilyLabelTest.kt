package ee.schimke.composeai.daemon

import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #3209 — `FontFamily.Default` is a sentinel, not a face name. Both capture paths (the
 * `TextLayoutResult`/semantics one and the compact `TextStyle` projection on the text layout
 * modifier) must report it as *unstated*, or its `toString()` reaches the `compose/figma-svg`
 * export as a concrete family (`font-family="FontFamily.Default, sans-serif"`) and the font-embed
 * path goes looking for a face called `Font Family.Default`.
 */
class FontFamilyLabelTest {
  @Test
  fun defaultFamilySentinelIsNeverCapturedAsAFaceName() {
    assertNull(ComposeSemanticsDataProducer.fontFamilyLabel(FontFamily.Default, null, null))
    assertNull(layoutTextFontFamilyLabel(FontFamily.Default))
  }

  @Test
  fun inheritedFamilyStaysUnstated() {
    assertNull(ComposeSemanticsDataProducer.fontFamilyLabel(null, null, null))
    assertNull(layoutTextFontFamilyLabel(null))
  }

  @Test
  fun genericFamiliesKeepTheirCssNames() {
    assertEquals(
      "sans-serif",
      ComposeSemanticsDataProducer.fontFamilyLabel(FontFamily.SansSerif, null, null),
    )
    assertEquals(
      "serif",
      ComposeSemanticsDataProducer.fontFamilyLabel(FontFamily.Serif, null, null),
    )
    assertEquals(
      "monospace",
      ComposeSemanticsDataProducer.fontFamilyLabel(FontFamily.Monospace, null, null),
    )
    assertEquals("sans-serif", layoutTextFontFamilyLabel(FontFamily.SansSerif))
    assertEquals("monospace", layoutTextFontFamilyLabel(FontFamily.Monospace))
  }
}
