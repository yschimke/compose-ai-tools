package com.example.sampleremotecompose

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * Regression guard for the critical invariant: **framing a Remote Compose widget in an ideal shape
 * via a `@PreviewWrapper` must not lose the encoded RemoteCompose document.**
 *
 * The `<stem>.rcdoc` sidecar (the encoded doc that `BundlePreviewTask.resolvePreviewIr` packs) is
 * captured only through the RemoteCompose wrapper — `RemoteOverridablePreviewWrapper.Wrap` runs
 * `captureSingleRemoteDocument`. `RemoteWidgetSquirclePreview` frames the widget in a squircle via
 * [SquircleRemoteWidgetWrapper], which **extends** that wrapper rather than replacing it, so both the
 * shape and the doc survive. A shape wrapper that swapped out the RemoteCompose wrapper would render
 * the same PNG but produce no `.rcdoc` — this test fails in that case.
 *
 * `renderBeforeUnitTests = true` chains `composePreviewRenderAll` before this test.
 */
class RemoteWidgetDocCaptureTest {

  private val rendersDir = File("build/compose-previews/renders")
  private val stem = "RemoteWidgetSquirclePreview_Remote_Widget_Squircle"

  @Test
  fun `shape-wrapped remote widget still captures the encoded rcdoc`() {
    val rcdoc = File(rendersDir, "$stem.rcdoc")
    assertThat(rcdoc.exists()).isTrue()
    // A real encoded RemoteCompose document, not an empty placeholder.
    assertThat(rcdoc.length()).isGreaterThan(0L)
  }

  @Test
  fun `shape-wrapped remote widget renders clipped to its ideal shape`() {
    val png = File(rendersDir, "$stem.png")
    assertThat(png.exists()).isTrue()
    val img = ImageIO.read(png)

    // Corner falls outside the squircle clip → the preview background shows through.
    val corner = img.getRGB(3, 3)
    val cs = ((corner shr 16) and 0xff) + ((corner shr 8) and 0xff) + (corner and 0xff)
    assertThat(cs).isLessThan(30)

    // Interior carries the widget's blue fill (0xFF1E88E5-ish), proving the remote content rendered.
    val inside = img.getRGB(img.width / 4, img.height / 2)
    val ir = (inside shr 16) and 0xff
    val ib = inside and 0xff
    assertThat(ib).isGreaterThan(150)
    assertThat(ib - ir).isGreaterThan(40)
  }
}
