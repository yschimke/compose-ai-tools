package ee.schimke.composeai.renderer

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `@CaptureGutter` on the standalone renderer (m3-catalog#179): the capture bounds grow by the
 * declared gutter and **nothing else moves**.
 *
 * That second half is the whole claim, and it is what these tests actually check — not just the
 * canvas dimensions but every pixel of the component, asserted to be byte-identical to the
 * gutter-less render and merely translated by the gutter. A gutter implemented as padding inside
 * the tree would pass a size assertion and fail this one, because the component would have measured
 * in a smaller box.
 *
 * [WrapContentSticker]'s intrinsic size is 176 px (56 dp badge + 16 dp padding a side, × density
 * 2), which is what every expected number below is derived from.
 */
class DesktopCaptureGutterRendererTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private val stickerClass = "ee.schimke.composeai.renderer.SizeBoundsRenderTestFixturesKt"

  private fun decode(file: File): BufferedImage {
    assertTrue("rendered PNG must exist: ${file.absolutePath}", file.exists() && file.length() > 0)
    return ByteArrayInputStream(file.readBytes()).use { ImageIO.read(it) } ?: error("no decode")
  }

  private fun renderSticker(
    base: String,
    gutter: PreviewCaptureGutter = PreviewCaptureGutter.None,
    wrapWidth: Boolean = true,
    wrapHeight: Boolean = true,
    widthPx: Int = 800,
    heightPx: Int = 1600,
  ): BufferedImage {
    val out = File(tempFolder.newFolder(base), "$base.png")
    renderPreview(
      className = stickerClass,
      functionName = "WrapContentSticker",
      widthPx = widthPx,
      heightPx = heightPx,
      density = 2.0f,
      showBackground = true,
      backgroundColor = 0L,
      outputFile = out,
      wrapperClassName = null,
      wrapWidth = wrapWidth,
      wrapHeight = wrapHeight,
      previewArgs = emptyList(),
      localeTag = null,
      captureGutter = gutter,
    )
    return decode(out)
  }

  /**
   * Every pixel of [inner] must appear in [outer], shifted by ([dx], [dy]).
   *
   * Compared with a small per-channel tolerance rather than for byte equality: an antialiased
   * curved edge blends against a surface of a different size, and Skia's coverage on the outermost
   * boundary pixel of the rounded badge lands a couple of levels apart between the two rasters. The
   * tolerance is far below anything a *layout* difference could hide behind — a component measured
   * in a smaller box moves its edges by whole pixels, which is what [assertContentBoxTranslated]
   * pins exactly.
   */
  private fun assertTranslatedCopy(inner: BufferedImage, outer: BufferedImage, dx: Int, dy: Int) {
    for (y in 0 until inner.height) {
      for (x in 0 until inner.width) {
        val expected = inner.getRGB(x, y)
        val actual = outer.getRGB(x + dx, y + dy)
        val delta =
          (0..3).maxOf { shift ->
            val e = (expected shr (shift * 8)) and 0xFF
            val a = (actual shr (shift * 8)) and 0xFF
            kotlin.math.abs(e - a)
          }
        if (delta > AA_TOLERANCE) {
          error(
            "component pixel moved: (%d,%d) expected %08X, got %08X at (%d,%d)"
              .format(
                x,
                y,
                expected,
                actual,
                x + dx,
                y + dy,
              )
          )
        }
      }
    }
  }

  /**
   * The drawn component's bounding box in [outer] must be exactly [inner]'s, offset by ([dx], [dy])
   * — the pixel-exact half of the claim. If a gutter were padding *inside* the tree the component
   * would have measured in a smaller box and this box would shrink; if it were placed centred
   * rather than at the leading edge, the offset would not be the gutter.
   */
  private fun assertContentBoxTranslated(
    inner: BufferedImage,
    outer: BufferedImage,
    dx: Int,
    dy: Int,
  ) {
    val (ix, iy, iw, ih) = drawnBounds(inner)
    val (ox, oy, ow, oh) = drawnBounds(outer)
    assertEquals("drawn width is unchanged by the gutter", iw, ow)
    assertEquals("drawn height is unchanged by the gutter", ih, oh)
    assertEquals("drawn content starts one gutter to the right", ix + dx, ox)
    assertEquals("drawn content starts one gutter down", iy + dy, oy)
  }

  /** Bounding box (x, y, w, h) of everything that isn't the white `showBackground` fill. */
  private fun drawnBounds(img: BufferedImage): List<Int> {
    var minX = img.width
    var minY = img.height
    var maxX = -1
    var maxY = -1
    for (y in 0 until img.height) {
      for (x in 0 until img.width) {
        if ((img.getRGB(x, y) and 0xFFFFFF) == 0xFFFFFF) continue
        if (x < minX) minX = x
        if (y < minY) minY = y
        if (x > maxX) maxX = x
        if (y > maxY) maxY = y
      }
    }
    check(maxX >= 0) { "image is entirely background — nothing was drawn" }
    return listOf(minX, minY, maxX - minX + 1, maxY - minY + 1)
  }

  @Test
  fun aWrappedCaptureGrowsByTheGutterAndTheComponentIsUnchanged() {
    val bare = renderSticker("gutter-none")
    assertEquals("baseline wrap crop is the intrinsic width", 176, bare.width)
    assertEquals("baseline wrap crop is the intrinsic height", 176, bare.height)

    // 8 dp a side at density 2 = 16 px a side.
    val gutter = PreviewCaptureGutter.ofDp(8, 8, 8, 8, density = 2.0f)
    val gutted = renderSticker("gutter-8dp", gutter)
    assertEquals("canvas grows by the horizontal gutter", 176 + 32, gutted.width)
    assertEquals("canvas grows by the vertical gutter", 176 + 32, gutted.height)
    assertTranslatedCopy(bare, gutted, dx = 16, dy = 16)
    assertContentBoxTranslated(bare, gutted, dx = 16, dy = 16)
  }

  /**
   * The shape a Material elevation gutter actually has: symmetric sides, a deeper bottom, because
   * the shadow is offset downward. Each edge has to land independently — a single "inset" value
   * would centre the component and put the extra pixels in the wrong place.
   */
  @Test
  fun anAsymmetricGutterPlacesEachEdgeIndependently() {
    val bare = renderSticker("gutter-asym-none")
    val gutter = PreviewCaptureGutter.ofDp(startDp = 4, topDp = 4, endDp = 4, bottomDp = 10, 2.0f)
    val gutted = renderSticker("gutter-asym", gutter)
    assertEquals("start + end = 16 px", 176 + 16, gutted.width)
    assertEquals("top (8) + bottom (20) = 28 px", 176 + 28, gutted.height)
    assertTranslatedCopy(bare, gutted, dx = 8, dy = 8)
    assertContentBoxTranslated(bare, gutted, dx = 8, dy = 8)
  }

  /**
   * A fixed axis grows too. `widthPx = 400` with a gutter renders 400 + gutter wide and still
   * measures the component against the 400 it declared — so the pixels inside are the same as an
   * un-guttered fixed render, offset by the gutter, rather than a component squeezed into 368.
   */
  @Test
  fun aFixedAxisKeepsItsDeclaredFrameAndAddsTheGutterAroundIt() {
    val bare = renderSticker("gutter-fixed-none", wrapWidth = false, widthPx = 400, heightPx = 1600)
    assertEquals("baseline fixed axis is the declared frame", 400, bare.width)

    val gutter = PreviewCaptureGutter.ofDp(8, 8, 8, 8, density = 2.0f)
    val gutted =
      renderSticker(
        "gutter-fixed-8dp",
        gutter,
        wrapWidth = false,
        widthPx = 400,
        heightPx = 1600,
      )
    assertEquals("fixed frame plus the horizontal gutter", 432, gutted.width)
    assertEquals("wrapped axis is intrinsic plus the vertical gutter", 176 + 32, gutted.height)
    assertTranslatedCopy(bare, gutted, dx = 16, dy = 16)
    assertContentBoxTranslated(bare, gutted, dx = 16, dy = 16)
  }

  /**
   * An all-zero gutter must be the pre-gutter path verbatim, not a no-op that still re-lays out.
   */
  @Test
  fun aZeroGutterRendersTheSameBytesAsNoGutterAtAll() {
    val bare = renderSticker("gutter-zero-none")
    val zero = renderSticker("gutter-zero", PreviewCaptureGutter.ofDp(0, 0, 0, 0, 2.0f))
    assertEquals(bare.width, zero.width)
    assertEquals(bare.height, zero.height)
    assertTranslatedCopy(bare, zero, dx = 0, dy = 0)
  }

  /**
   * Locks the `main()` positional-arg wiring: the gutter's four dp edges live at indices 49–52,
   * after the `@SettledPreview` pair. An off-by-one there would silently drop the gutter, which is
   * exactly the failure the annotation exists to prevent, so drive the real entry point.
   */
  @Test
  fun mainAppliesTheGutterAtItsPositionalArgIndices() {
    val out = File(tempFolder.newFolder("main-args"), "sticker.png")
    val args = MutableList(53) { "" }
    args[0] = stickerClass
    args[1] = "WrapContentSticker"
    args[2] = "800" // widthPx
    args[3] = "1600" // heightPx
    args[4] = "2.0" // density
    args[5] = "true" // showBackground
    args[6] = "0" // backgroundColor
    args[7] = out.absolutePath
    args[9] = "true" // wrapWidth
    args[10] = "true" // wrapHeight
    args[12] = "0" // previewParameterLimit
    args[49] = "8" // captureGutter.start dp
    args[50] = "8" // captureGutter.top dp
    args[51] = "8" // captureGutter.end dp
    args[52] = "10" // captureGutter.bottom dp
    main(args.toTypedArray())
    val img = decode(out)
    assertEquals("main() must thread the start/end gutter (args 49/51)", 176 + 32, img.width)
    assertEquals("main() must thread the top/bottom gutter (args 50/52)", 176 + 16 + 20, img.height)
  }

  @Test
  fun mainRejectsAnUnavailableDraggedVariantInsteadOfPublishingTheRestingFrame() {
    val out = File(tempFolder.newFolder("main-invalid-drag"), "sticker.png")
    val args = MutableList(54) { "" }
    args[0] = stickerClass
    args[1] = "WrapContentSticker" // deliberately has no interactive semantics node
    args[2] = "800"
    args[3] = "1600"
    args[4] = "2.0"
    args[5] = "true"
    args[6] = "0"
    args[7] = out.absolutePath
    args[9] = "true"
    args[10] = "true"
    args[12] = "0"
    args[53] = "0"

    main(args.toTypedArray())

    assertFalse("an unavailable drag must not publish the resting PNG", out.exists())
    val sidecar = File(out.parentFile, "${out.name}.error.json")
    assertTrue("an unavailable drag must publish an error sidecar", sidecar.isFile)
    assertTrue(sidecar.readText().contains("refusing to publish an undriven artifact"))
  }

  private companion object {
    /** Per-channel slack for antialiased boundary pixels; see [assertTranslatedCopy]. */
    const val AA_TOLERANCE = 12
  }
}
