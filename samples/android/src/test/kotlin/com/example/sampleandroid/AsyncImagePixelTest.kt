package com.example.sampleandroid

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * End-to-end guard for issue #2952 — a coil `AsyncImage` must actually resolve during a render.
 *
 * Reads the PNGs produced by `:samples:android:composePreviewRenderAll` (wired into this module's
 * `test` task via `renderBeforeUnitTests`) and asserts the two halves of the bug independently,
 * because they fail for different reasons and a fix could plausibly address only one:
 *
 * 1. **the pixels** — the artwork band carries several distinct colours, not one flat fill. Before
 *    the fix the whole capture was a single background colour.
 * 2. **the layout** — the caption below the image is still on screen. An unresolved
 *    `AsyncImagePainter` reports no intrinsic size, so `ContentScale.FillWidth` grew it to the
 *    parent's full height and pushed the caption out of frame. Asserting "the bottom strip is not
 *    the same colour as the middle" catches that collapse even if the image itself somehow
 *    resolved.
 *
 * A third test covers the diagnostic half: a model that genuinely can't be fetched must draw its
 * request placeholder and leave a `<png>.warnings.json` naming the unresolved model.
 */
class AsyncImagePixelTest {

  private val rendersDir = File("build/compose-previews/renders")
  private val artworkPng = renderFile(rendersDir, "AsyncImageArtworkPreview_Async_Image_Artwork")
  private val unreachablePng =
    renderFile(rendersDir, "AsyncImageUnreachablePreview_Async_Image_Unreachable")

  @Test
  fun `AsyncImage fed a ByteArray resolves and paints real artwork`() {
    assertThat(artworkPng.exists()).isTrue()
    val img = ImageIO.read(artworkPng)
    assertThat(img.width).isAtLeast(100)
    assertThat(img.height).isAtLeast(100)

    // Sample a horizontal band through the vertical centre, where the 100dp-wide artwork sits.
    // The fixture draws concentric rings over a gradient, so a resolved image gives many distinct
    // colours; an unresolved one gives exactly one (the surface fill).
    val band = img.height / 2
    val colours =
      (0 until img.width step 2).map { x -> img.getRGB(x, band) and 0xffffff }.distinct()
    assertThat(colours.size).isAtLeast(8)
  }

  @Test
  fun `the caption below the image is not pushed out of frame`() {
    val img = ImageIO.read(artworkPng)
    // The caption sits under the artwork. If the painter had no intrinsic size the image would
    // have consumed the full height and this strip would be image, not text-on-surface — so
    // compare it against the run of background at the very top, which is surface either way.
    val topRow = (0 until img.width).map { x -> img.getRGB(x, 2) and 0xffffff }.distinct()
    assertThat(topRow).hasSize(1)
    val surface = topRow.single()

    val captionStrip =
      (img.height * 3 / 4 until img.height - 2).flatMap { y ->
        (0 until img.width).map { x -> img.getRGB(x, y) and 0xffffff }
      }
    // Text antialiasing means the strip is mostly surface with some darker glyph pixels. Both
    // facts matter: surface present (the image didn't eat the frame) and non-surface present
    // (the caption actually drew).
    assertThat(captionStrip).contains(surface)
    assertThat(captionStrip.any { it != surface }).isTrue()
  }

  @Test
  fun `an unfetchable model is recorded in the warnings sidecar`() {
    assertThat(unreachablePng.exists()).isTrue()
    val sidecar = File(unreachablePng.parentFile, unreachablePng.name + ".warnings.json")
    assertThat(sidecar.exists()).isTrue()
    val json = sidecar.readText()
    assertThat(json).contains("unresolvedImages")
    assertThat(json).contains(UNREACHABLE_ARTWORK_URL)
  }

  @Test
  fun `an unfetchable model falls back to its placeholder`() {
    assertThat(unreachablePng.exists()).isTrue()
    val img = ImageIO.read(unreachablePng)
    val placeholderPink = 0xe91e63

    val centreBand =
      (img.height / 3 until img.height * 2 / 3).flatMap { y ->
        (img.width / 4 until img.width * 3 / 4).map { x -> img.getRGB(x, y) and 0xffffff }
      }

    assertThat(centreBand).contains(placeholderPink)
  }
}
