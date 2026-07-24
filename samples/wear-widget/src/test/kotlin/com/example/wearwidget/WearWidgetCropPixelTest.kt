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

  // --- @PreviewWrapper + @PreviewParameter widget previews (mirroring wear-os-samples) ---
  //
  // Each squircle/rectangular preview fans out over its param provider and is framed in the ideal
  // widget shape by a PreviewWrapperProvider. These assert BOTH: (a) the crop lands at the param's
  // exact dp size × 2.0 wear density, and (b) the ideal shape actually clipped — the render's corner
  // is the (masked-away) background while the interior carries the widget's blue fill.

  @Test
  fun `squircle widget previews crop to param size and mask corners to the ideal shape`() {
    // 192dp → 384px, 228dp → 456px, both square, both at 2.0x wear density.
    assertWidgetShapeCropped(
      "ImageWidgetSquirclePreview_Image_Widget_Squircle_Squircle_Small.png",
      expectedWidth = 384,
      expectedHeight = 384,
    )
    assertWidgetShapeCropped(
      "ImageWidgetSquirclePreview_Image_Widget_Squircle_Squircle_Large.png",
      expectedWidth = 456,
      expectedHeight = 456,
    )
  }

  @Test
  fun `rectangular widget previews crop to param size and mask corners to the rounded shape`() {
    // 228×102 dp → 456×204 px, 228×150 dp → 456×300 px, at 2.0x wear density.
    assertWidgetShapeCropped(
      "ImageWidgetRectangularPreview_Image_Widget_Rectangular_Rectangular_Small.png",
      expectedWidth = 456,
      expectedHeight = 204,
    )
    assertWidgetShapeCropped(
      "ImageWidgetRectangularPreview_Image_Widget_Rectangular_Rectangular_Large.png",
      expectedWidth = 456,
      expectedHeight = 300,
    )
  }

  /**
   * Asserts a framed widget render is (a) cropped to [expectedWidth]×[expectedHeight] px — the
   * param's dp size at 2.0x wear density — and (b) clipped to its ideal shape: the top-left corner
   * is the masked-away background (near-black) while an interior point carries the widget's blue
   * fill (`0xFF1E88E5` = r30 g136 b229). A rectangle-fill render (no shape clip) would show the blue
   * at the corner; a phone-density render would miss the size.
   */
  private fun assertWidgetShapeCropped(fileName: String, expectedWidth: Int, expectedHeight: Int) {
    val png = File(rendersDir, fileName)
    assertThat(png.exists()).isTrue()
    val img = ImageIO.read(png)

    assertThat(img.width).isEqualTo(expectedWidth)
    assertThat(img.height).isEqualTo(expectedHeight)

    // Corner is outside the squircle / rounded-rect → the preview background shows through.
    val corner = img.getRGB(3, 3)
    val cr = (corner shr 16) and 0xff
    val cg = (corner shr 8) and 0xff
    val cb = corner and 0xff
    assertThat(cr + cg + cb).isLessThan(30)

    // A quarter-width interior point is inside the shape → the widget's blue fill.
    val inside = img.getRGB(img.width / 4, img.height / 2)
    val ir = (inside shr 16) and 0xff
    val ib = inside and 0xff
    assertThat(ib).isGreaterThan(150)
    assertThat(ib - ir).isGreaterThan(40)
  }
}
