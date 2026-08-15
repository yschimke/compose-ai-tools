package com.example.sampleremotecompose

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * Regression guard for the critical invariant: **framing a Remote Compose widget in an ideal shape
 * via a `@PreviewWrapper` must not lose the encoded RemoteCompose document.**
 *
 * The `<stem>.rc` sidecar (the encoded doc that `BundlePreviewTask.resolvePreviewIr` packs) is
 * captured only through the RemoteCompose wrapper — `RemoteOverridablePreviewWrapper.Wrap` runs
 * `captureSingleRemoteDocument`. `RemoteWidgetSquirclePreview` frames the widget in a squircle via
 * [SquircleRemoteWidgetWrapper], which **extends** that wrapper rather than replacing it, so both the
 * shape and the doc survive. A shape wrapper that swapped out the RemoteCompose wrapper would render
 * the same PNG but produce no `.rc` — this test fails in that case.
 *
 * The extension is `.rc`; it was `.rcdoc` until the serve canvas lane renamed it (#2720). This test
 * kept asking for the old name and so had been failing — i.e. NOT guarding the invariant — until
 * that was corrected, which is the failure mode a guard whose subject is "a file exists" always
 * has: it cannot tell "the producer broke" from "I am looking in the wrong place".
 *
 * `renderBeforeUnitTests = true` chains `composePreviewRenderAll` before this test.
 */
class RemoteWidgetDocCaptureTest {

  private val rendersDir = File("build/compose-previews/renders")
  private val stem = "RemoteWidgetSquirclePreview_Remote_Widget_Squircle"

  @Test
  fun `shape-wrapped remote widget still captures the encoded rc document`() {
    val encodedDoc = renderFile(rendersDir, stem, ext = "rc")
    // Name what is actually on disk when this fails. A bare `exists()` cannot distinguish "the
    // wrapper stopped capturing the doc" (the regression this guards) from "the sidecar was
    // renamed and the assertion is now looking for a file nobody writes" — which is exactly how
    // this test sat red instead of guarding anything after `.rcdoc` became `.rc`.
    assertWithMessage(
        "no $stem*.rc sidecar in $rendersDir; found " +
          (rendersDir.listFiles()?.map { it.name }?.sorted() ?: emptyList<String>())
      )
      .that(encodedDoc.exists())
      .isTrue()
    // A real encoded RemoteCompose document, not an empty placeholder.
    assertThat(encodedDoc.length()).isGreaterThan(0L)
  }

  @Test
  fun `shape-wrapped remote widget renders clipped to its ideal shape`() {
    val png = renderFile(rendersDir, stem)
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
