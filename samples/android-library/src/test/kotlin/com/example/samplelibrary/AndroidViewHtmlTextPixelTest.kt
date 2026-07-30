package com.example.samplelibrary

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * Issue #2957 — a plain `@Preview` whose body is an `AndroidView` must emit a static PNG, including
 * from a **library** module.
 *
 * Without `composePreview { hostTheme.set("@style/Theme.SampleLibrary") }`, the preview host
 * activity has no `<application android:theme>` to inherit, so inflating [HtmlShowNotes]'s layout
 * (styled through the app-owned `?attr/sampleBodyTextAppearance`) throws
 * `UnsupportedOperationException: Failed to resolve attribute at index N`. That escapes composition
 * and aborts the render, so `renders/` carries no PNG for the preview at all — which is exactly how
 * a real consumer's `AndroidView` surface got dropped from the design-artifacts candidate join as
 * "no static PNG".
 *
 * Existence alone is the primary assertion (that is what the export's candidate join tests), but a
 * blank PNG would satisfy it, so the test also asserts the hosted `TextView` actually drew: the
 * theme's body text appearance is near-black on the preview's light background.
 *
 * Reads the file produced by `:samples:android-library:composePreviewRenderAll`, which
 * `renderBeforeUnitTests` chains ahead of this task.
 */
class AndroidViewHtmlTextPixelTest {

  private val rendersDir = File("build/compose-previews/renders")
  private val pngName = "HtmlShowNotesPreview_HTML_show_notes.png"

  @Test
  fun `AndroidView-hosted preview renders a static PNG with drawn text`() {
    val file = File(rendersDir, pngName)
    assertThat(file.exists()).isTrue()

    val img = ImageIO.read(file)
    assertThat(img.width).isAtLeast(100)
    assertThat(img.height).isAtLeast(100)

    // The `TextView` sits below the "Show notes" title, in the lower two thirds of the frame.
    // Count near-black pixels there: a render that composed but drew no hosted View comes back as
    // a flat light surface and fails here.
    var darkPixels = 0
    for (y in img.height / 3 until img.height) {
      for (x in 0 until img.width) {
        val rgb = img.getRGB(x, y)
        val r = (rgb shr 16) and 0xff
        val g = (rgb shr 8) and 0xff
        val b = rgb and 0xff
        if (r < 96 && g < 96 && b < 96) darkPixels++
      }
    }
    assertThat(darkPixels).isGreaterThan(50)
  }
}
