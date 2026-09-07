package ee.schimke.composeai.daemon

import androidx.compose.ui.text.font.Font as ComposeFont
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Issue #3209 — `FontFamily.Default` is a sentinel, not a face name. Both capture paths (the
 * `TextLayoutResult`/semantics one and the compact `TextStyle` projection on the text layout
 * modifier) must report it as *unstated*, or its `toString()` reaches the `compose/figma-svg`
 * export as a concrete family (`font-family="FontFamily.Default, sans-serif"`) and the font-embed
 * path goes looking for a face called `Font Family.Default`.
 */
class FontFamilyLabelTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

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

  @Test
  fun modifierProjectionResolvesFontListsToTheSelectedFace() {
    val bytes = checkNotNull(javaClass.getResourceAsStream("/fonts/DroidSansMono.ttf")).readBytes()
    val family = FontFamily(Font("DroidSansMono", bytes, FontWeight.Normal, FontStyle.Normal))

    val label = layoutTextFontFamilyLabel(family, FontWeight.Normal, FontStyle.Normal)

    assertEquals("DroidSansMono", label)
    assertFalse(label.orEmpty().contains("FontListFontFamily"))
  }

  /**
   * A face built from a **file** and exposing nothing else — the shape `Font(File, …)` resolves to
   * on Android (`AndroidFileFont` declares `getFile()` and `getCacheKey()`, and no `identity` /
   * `resId`). Written as a stand-in rather than the real class because this module is JVM: the
   * capture reads all of these getters reflectively, so what is under test is the getter contract,
   * which this reproduces exactly.
   */
  private class FileBackedFont(
    val file: File,
    override val weight: FontWeight = FontWeight.Normal,
    override val style: FontStyle = FontStyle.Normal,
  ) : ComposeFont

  @Test
  fun fileBackedFacesReportTheirFileRatherThanNothing() {
    val file = tempFolder.newFile("7fbd4d8a-cache-entry.ttf").apply { writeBytes(byteArrayOf(0)) }

    val label =
      ComposeSemanticsDataProducer.fontFamilyLabel(
        FontFamily(FileBackedFont(file)),
        FontWeight.Normal,
        FontStyle.Normal,
      )

    assertEquals(file.absolutePath, label)
  }

  @Test
  fun aSweptCacheEntryStaysUnstatedRatherThanNamingAMissingPath() {
    val missing = File(tempFolder.root, "swept-by-the-cache.ttf")

    assertNull(
      ComposeSemanticsDataProducer.fontFamilyLabel(
        FontFamily(FileBackedFont(missing)),
        FontWeight.Normal,
        FontStyle.Normal,
      )
    )
  }
}
