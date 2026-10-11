package com.example.sampleremotecompose

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * Framing a Remote Compose widget in a shape via `@PreviewWrapper` must not lose its encoded
 * document. Only `RemoteOverridablePreviewWrapper.Wrap` captures the `<stem>.rc` sidecar, and
 * [SquircleRemoteWidgetWrapper] extends it rather than replacing it; a replacing wrapper would
 * render the same PNG but no `.rc`, failing this test. `renderBeforeUnitTests` renders first.
 */
class RemoteWidgetDocCaptureTest {

  private val rendersDir = File("build/compose-previews/renders")
  private val catalogTokensDir = File("build/compose-previews/data/catalog-tokens")
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

    // Interior carries the widget's blue fill (0xFF1E88E5-ish), proving the remote content
    // rendered.
    val inside = img.getRGB(img.width / 4, img.height / 2)
    val ir = (inside shr 16) and 0xff
    val ib = inside and 0xff
    assertThat(ib).isGreaterThan(150)
    assertThat(ib - ir).isGreaterThan(40)
  }

  @Test
  fun `remote material catalogs resolve every role and render complete sheets`() {
    val expected =
      mapOf(
        "colorcatalog__Remote_theme" to ("COLOR" to 29),
        "typographycatalog__Remote_theme" to ("TEXT_STYLE" to 18),
        "shapecatalog__Remote_theme" to ("SHAPE" to 5),
      )

    for ((catalog, kindAndCount) in expected) {
      val (kind, count) = kindAndCount
      val sidecar = File(catalogTokensDir, "$catalog.catalog.json")
      assertWithMessage("missing Remote catalog sidecar $sidecar").that(sidecar.exists()).isTrue()
      assertThat(Regex("\\\"kind\\\":\\\"$kind\\\"").findAll(sidecar.readText()).count())
        .isEqualTo(count)

      val image = ImageIO.read(File(rendersDir, "$catalog.png"))
      assertThat(image.width).isEqualTo(900)
      assertThat(image.height).isEqualTo(760)
    }
  }
}
