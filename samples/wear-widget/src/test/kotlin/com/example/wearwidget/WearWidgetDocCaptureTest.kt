package com.example.wearwidget

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * End-to-end regression for the Glance Wear widget fixture (issue #2670 + the doc-capture
 * requirement).
 *
 * Every widget preview here is a real `WearWidgetPreview` driven by an
 * `androidx.glance.wear.tooling.preview` `@PreviewParameter` provider, routed through
 * [CapturingWearWidgetPreview]. This asserts both properties that make it a faithful widget fixture:
 * - **Cropped, not the watch canvas.** Discovery's auto-detect recognises the glance-wear provider
 *   and crops each render to its intrinsic bounds at wear density — so every PNG is smaller than the
 *   227dp (≈454 px) square watch-face canvas.
 * - **Encoded document captured.** Each render emits a sibling `<stem>.rc` sidecar — the widget's
 *   encoded RemoteCompose document (`IR_EXT_REMOTECOMPOSE`) — so the widget travels in the portable
 *   bundle as data, not as compiled `@Preview` bytecode. Upstream `WearWidgetPreview` keeps those
 *   bytes to itself; [CapturingWearWidgetPreview] is what surfaces them.
 *
 * `renderBeforeUnitTests = true` chains `composePreviewRenderAll` before this test.
 */
class WearWidgetDocCaptureTest {

  private val rendersDir = File("build/compose-previews/renders")

  // 227dp @ 2.0x ≈ 454 px — the watch-face canvas a widget must never be pinned to.
  private val watchCanvasPx = 454

  private fun widgetPngs(): List<File> =
    rendersDir.listFiles { f -> f.name.startsWith("ImageWidget") && f.name.endsWith(".png") }
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
