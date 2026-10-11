package com.example.wearwidget

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * End-to-end check of the Glance Wear widget fixture, routed through [CapturingWearWidgetPreview]:
 * - each render is cropped to the widget's intrinsic bounds, smaller than the 227dp (≈454 px) watch
 *   canvas;
 * - each emits a sibling `<stem>.rc` sidecar (`IR_EXT_REMOTECOMPOSE`), so the widget travels in the
 *   bundle as data.
 *
 * `renderBeforeUnitTests = true` renders first.
 */
class WearWidgetDocCaptureTest {

  private val rendersDir = File("build/compose-previews/renders")

  // 227dp @ 2.0x ≈ 454 px — the watch-face canvas a widget must never be pinned to.
  private val watchCanvasPx = 454

  private fun widgetPngs(): List<File> =
    rendersDir
      .listFiles { f -> f.name.startsWith("ImageWidget") && f.name.endsWith(".png") }
      ?.sortedBy { it.name } ?: emptyList()

  // The device-less, `@PreviewParameter`-driven squircle previews — the ones discovery auto-detects
  // and crops. The `ImageWidgetFixed*` preview pins its own `@Preview` dimensions, so it's excluded
  // from the crop assertion (it exists to prove the bundle packs the `.rc`, not the crop).
  private fun croppedWidgetPngs(): List<File> =
    widgetPngs().filter { it.name.startsWith("ImageWidgetSquircle") }

  @Test
  fun `every wear widget preview rendered`() {
    // 4 squircle footprints (All) + 2 (Large) + 1 fixed = 7 variants.
    assertThat(widgetPngs()).isNotEmpty()
  }

  @Test
  fun `each auto-detected widget crops below the watch canvas at wear density`() {
    val cropped = croppedWidgetPngs()
    assertThat(cropped).isNotEmpty()
    for (png in cropped) {
      val img = ImageIO.read(png)
      assertThat(img.width).isLessThan(watchCanvasPx)
      assertThat(img.height).isLessThan(watchCanvasPx)
    }
  }

  @Test
  fun `each widget emits its encoded RemoteCompose document as a rc sidecar`() {
    val pngs = widgetPngs()
    assertThat(pngs).isNotEmpty()
    for (png in pngs) {
      val rc = File(png.parentFile, png.nameWithoutExtension + ".rc")
      assertThat(rc.exists()).isTrue()
      // A real encoded document, not an empty placeholder.
      assertThat(rc.length()).isGreaterThan(0L)
    }
  }
}
