package com.example.designcatalogremotem3

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Guards that the widget-container stickers carry their **encoded RemoteCompose document**, not just
 * pixels.
 *
 * Every other sticker in this sheet captures through `RemoteOverridablePreview`, which offers the
 * document to `IrSidecarChannel` so the render lands a `<stem>.rc` and `BundlePreviewTask
 * .resolvePreviewIr` packs it as the sticker's IR. The widget-container stickers went through
 * upstream's `WearWidgetPreview` instead, which captures the document internally and keeps the bytes
 * to itself — so they rendered fine while silently riding the bundle as compiled `@Preview`
 * bytecode. `CapturingWearWidgetPreview` closed that gap; this test is what keeps it closed.
 *
 * It is a *sidecar* assertion rather than a pixel one on purpose: the failure mode being guarded
 * against is invisible in the PNG. The capture is best-effort inside the composable (an IR failure
 * must not break the raster), so without this test a regression — a coroutines version skew, an
 * upstream signature change — would show up as nothing more than a missing file and a green build.
 */
class WidgetContainerIrCaptureTest {

  private val rendersDir = File("build/compose-previews/renders")

  private val widgetStickers =
    listOf("WidgetContainerSmallRemote", "WidgetContainerLargeRemote", "WidgetContainerGradientRemote")

  @Test
  fun `every widget container sticker renders`() {
    for (stem in widgetStickers) {
      assertThat(File(rendersDir, "$stem.png").exists()).isTrue()
    }
  }

  @Test
  fun `every widget container sticker emits its encoded RemoteCompose document as a rc sidecar`() {
    for (stem in widgetStickers) {
      val rc = File(rendersDir, "$stem.rc")
      assertThat(rc.exists()).isTrue()
      // A real encoded document, not an empty placeholder.
      assertThat(rc.length()).isGreaterThan(0L)
    }
  }
}
