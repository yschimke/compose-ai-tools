package ee.schimke.composeai.daemon

import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regression proof for the PNG ↔ Live Compose "preview size shift" (the interactive / stream lane
 * dropping the AS-parity wrap crop).
 *
 * A trusted-catalog sticker is served two ways: its browse snapshot is the **baked** catalog PNG —
 * wrap-cropped to the component's intrinsic size — while the "Live Compose" toggle streams the
 * **daemon** re-render of the same `@Preview`. Because `renderSpecFromInfo` never set
 * `wrapWidth`/`wrapHeight`, the stream rendered the fixed sandbox frame with the component pinned
 * to the top-left corner; the viewer stretches that whole frame into the (tightly-cropped)
 * snapshot's on-screen box, so the component appeared small and shoved up-left the instant you
 * toggled to Live.
 *
 * This renders [WrapContentStickerPreview] — a catalog-sticker-shaped wrap-content component —
 * twice through the real [RenderEngine], the same path the held/stream session uses (`render` →
 * `setUp` → `renderOnce` → `cropToMeasured`):
 *
 * * **wrap OFF** (the old stream framing): a fixed 320² frame — the component sits small in the
 *   top-left, most of the frame is empty background.
 * * **wrap ON** (the fix, matching the bake): the frame crops to the component's intrinsic size, so
 *   the component fills it — pixel-parity with the baked snapshot, no shift.
 *
 * Both PNGs are copied to `build/wrap-evidence/` for the PR's before/after visual evidence.
 */
class RenderEngineWrapContentTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  /** Render the sticker fixture with wrap on/off; returns the PNG. */
  private fun render(engine: RenderEngine, wrap: Boolean, baseName: String): File {
    val spec =
      RenderSpec(
        previewId = "sticker",
        className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
        functionName = "WrapContentStickerPreview",
        // Wrap OFF ⇒ fixed 320² frame (the old live-stream default). Wrap ON ⇒ the 400×800 dp
        // sandbox bound that renderSpecFromInfo now sets, cropped to intrinsic by cropToMeasured.
        widthPx = if (wrap) 800 else 320,
        heightPx = if (wrap) 1600 else 320,
        wrapWidth = wrap,
        wrapHeight = wrap,
        density = 2.0f,
        showBackground = true,
        outputBaseName = baseName,
      )
    val result = engine.render(spec, requestId = 1L, classLoader = javaClass.classLoader)
    assertNotNull("pngPath must be populated", result.pngPath)
    val png = File(result.pngPath!!)
    assertTrue("rendered PNG must exist: ${png.absolutePath}", png.exists())
    return png
  }

  /**
   * Fraction of pixels close to the badge fill (#B71C1C) — how much of the frame the component
   * fills.
   */
  private fun redFraction(png: File): Double {
    val img = ByteArrayInputStream(png.readBytes()).use { ImageIO.read(it) }
    assertNotNull("PNG must decode", img)
    var red = 0L
    for (y in 0 until img.height) for (x in 0 until img.width) {
      val rgb = img.getRGB(x, y)
      val a = (rgb ushr 24) and 0xFF
      val r = (rgb ushr 16) and 0xFF
      val g = (rgb ushr 8) and 0xFF
      val b = rgb and 0xFF
      if (a == 0xFF && r in 0x9F..0xD0 && g < 0x50 && b < 0x50) red++
    }
    return red.toDouble() / (img.width.toLong() * img.height)
  }

  private fun dims(png: File): Pair<Int, Int> {
    val img = ByteArrayInputStream(png.readBytes()).use { ImageIO.read(it) }
    return img.width to img.height
  }

  @Test
  fun wrapContentCropsTheStreamFrameToMatchTheBakedSnapshot() {
    val engine = RenderEngine(outputDir = tempFolder.newFolder("renders"))
    val evidenceDir = File("build/wrap-evidence").apply { mkdirs() }

    val wrapOff = render(engine, wrap = false, baseName = "sticker-fixed-320")
    val wrapOn = render(engine, wrap = true, baseName = "sticker-wrap-cropped")

    wrapOff.copyTo(File(evidenceDir, "sticker-fixed-320.png"), overwrite = true)
    wrapOn.copyTo(File(evidenceDir, "sticker-wrap-cropped.png"), overwrite = true)

    // Wrap OFF keeps the fixed 320² frame.
    assertEquals("wrap-off render keeps the fixed sandbox frame", 320 to 320, dims(wrapOff))

    // Wrap ON crops to the component's intrinsic size (56 dp badge + 16 dp padding each side = 88
    // dp
    // ⇒ 176 px at density 2), so both axes shrink well below the fixed frame — that crop is what
    // makes the live stream match the baked snapshot instead of shifting.
    val (onW, onH) = dims(wrapOn)
    assertTrue("wrap-on width must crop below the fixed frame (got $onW)", onW < 320)
    assertTrue("wrap-on height must crop below the fixed frame (got $onH)", onH < 320)

    // Same component, so it fills a far larger fraction of the cropped frame than of the fixed one
    // —
    // the on-screen "small in the top-left corner" symptom, quantified.
    val offRed = redFraction(wrapOff)
    val onRed = redFraction(wrapOn)
    assertTrue("the component must render in both frames (wrap-off red=$offRed)", offRed > 0.02)
    assertTrue(
      "wrap crop must make the component fill much more of the frame " +
        "(wrap-off ${"%.1f".format(offRed * 100)}% vs wrap-on ${"%.1f".format(onRed * 100)}%)",
      onRed > offRed * 2,
    )
  }
}
