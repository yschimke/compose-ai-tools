package com.example.wearwidget

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * End-to-end regression for issue #2670. `:samples:wear-widget` is a Wear module (its manifest
 * declares `android.hardware.type.watch`) that sets `retargetWearPreviews = false`. That opt-out
 * must make device-less widget previews crop to their intrinsic layout bounds — for export as
 * fixed-size drawable assets — instead of being pinned onto the 227dp (454×454 px) watch-face
 * canvas the Wear sticker retarget would otherwise force.
 *
 * The retarget still swaps in the Wear density (2.0x), so the cropped dp bounds scale to
 * watch-density px, NOT the inherited 2.625x phone default. Both facts are asserted below via exact
 * pixel dimensions:
 * - `ImageWidget` is 192×60 dp → 384×120 px (192×2.0, 60×2.0). Phone density would give 504×157.
 * - `BadgeWidget` is 96×96 dp → 192×192 px (96×2.0). Phone density would give 252×252.
 *
 * `renderBeforeUnitTests = true` in build.gradle.kts chains `composePreviewRenderAll` before this
 * test so the PNGs exist when it runs.
 */
class WearWidgetCropPixelTest {

  private val rendersDir = File("build/compose-previews/renders")

  // The Wear watch-face canvas the retarget would otherwise pin every device-less preview onto:
  // 227dp @ 2.0x ≈ 454 px square. The fix must produce something smaller and non-square.
  private val watchCanvasPx = 454

  @Test
  fun `image widget preview crops to its 192x60 dp bounds at wear density`() {
    val png = File(rendersDir, "ImageWidgetPreview_Image_Widget.png")
    assertThat(png.exists()).isTrue()
    val img = ImageIO.read(png)

    // Cropped to the composable's intrinsic bounds, not the watch canvas.
    assertThat(img.width).isLessThan(watchCanvasPx)
    assertThat(img.height).isLessThan(watchCanvasPx)

    // 192×60 dp at the Wear default density (2.0x). If the opt-out had dropped the retarget
    // entirely, the preview would keep the inherited phone density (2.625x) and render 504×157.
    assertThat(img.width).isEqualTo(384)
    assertThat(img.height).isEqualTo(120)
  }

  @Test
  fun `badge widget preview crops to its 96x96 dp bounds at wear density`() {
    val png = File(rendersDir, "BadgeWidgetPreview_Badge_Widget.png")
    assertThat(png.exists()).isTrue()
    val img = ImageIO.read(png)

    // 96×96 dp at 2.0x wear density → 192×192 px — cropped (not the 454 px canvas) and at wear,
    // not phone (2.625x → 252×252), density.
    assertThat(img.width).isEqualTo(192)
    assertThat(img.height).isEqualTo(192)
  }
}
