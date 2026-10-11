package com.example.samplexrglimmer

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * The additive-RGB capture contract for `:samples:xr-glimmer` (the counterpart of
 * `:samples:android`'s `TransparentBackgroundPreviewPixelTest`). Glimmer's display is additive, so
 * captures are opaque RGB on black, later `ADD`-blended onto an environment image:
 *
 * - the PNG has an alpha plane, fully opaque (`0xFF`) everywhere;
 * - the four outer corners are `RGB == (0, 0, 0)`, so `backgroundColor = 0xFF000000` survived and
 *   no compositor mutated the capture in place.
 *
 * An opaque grey background would look fine to a reviewer but wash out every composited pixel.
 */
class GlimmerCaptureAdditivePixelTest {

  private val rendersDir = File("build/compose-previews/renders")

  // One capture per `@Preview` environment name; sanitising drops the middle dot (see
  // `docs/RENDER_FILENAMES.md`), giving `NowPlayingCard_Glimmer_Light.png`.
  private val nowPlayingCaptures =
    listOf(
      "NowPlayingCard_Glimmer_Light",
      "NowPlayingCard_Glimmer_Dark",
      "NowPlayingCard_Glimmer_Busy",
      "NowPlayingCard_Glimmer_VeniceCanalCats",
    )

  private val focusableMenuCapture = "FocusableMenu_Glimmer_Input"

  @Test
  fun `every Glimmer capture is opaque RGB with additive-zero corners`() {
    val files = (nowPlayingCaptures + focusableMenuCapture).map { renderFile(rendersDir, it) }
    files.forEach { file ->
      assertThat(file.exists()).isTrue()
      val img = ImageIO.read(file)
      assertThat(img.colorModel.hasAlpha()).isTrue()
      assertThat(img.width).isAtLeast(40)
      assertThat(img.height).isAtLeast(40)

      val (w, h) = img.width to img.height
      // The card and chip sit centred in a 960×720 canvas with 24-dp insets from the
      // background `Box`, so the four absolute corner pixels are well outside any
      // composable that could paint over the background-fill layer.
      listOf(0 to 0, w - 1 to 0, 0 to h - 1, w - 1 to h - 1).forEach { (x, y) ->
        val argb = img.getRGB(x, y)
        val alpha = (argb ushr 24) and 0xff
        val r = (argb ushr 16) and 0xff
        val g = (argb ushr 8) and 0xff
        val b = argb and 0xff
        assertThat(alpha).isEqualTo(0xff)
        assertThat(Triple(r, g, b)).isEqualTo(Triple(0, 0, 0))
      }
    }
  }

  /**
   * The four captures are pixel-identical today (the environment compositor writes separate files);
   * catches the renderer starting to vary them by `Preview.name`.
   */
  @Test
  fun `four NowPlayingCard env variants land at pixel-identical captures`() {
    val files = nowPlayingCaptures.map { renderFile(rendersDir, it) }
    files.forEach { assertThat(it.exists()).isTrue() }
    val hashes = files.map { it.readBytes().contentHashCode() }.toSet()
    assertThat(hashes).hasSize(1)
  }
}
