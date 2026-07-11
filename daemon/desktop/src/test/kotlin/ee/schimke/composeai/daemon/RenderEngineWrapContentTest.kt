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

  /**
   * Render the sticker fixture on a wrapped axis with the given size-bound overrides (the Fixed /
   * Max / Min / Within controls). The sandbox bound stays generous (800×1600 like wrap-on) so the
   * bound — not the frame — decides the measured intrinsic size the crop keeps.
   */
  private fun renderBounded(
    engine: RenderEngine,
    overrides: ee.schimke.composeai.daemon.protocol.PreviewOverrides,
    baseName: String,
  ): File {
    val spec =
      RenderSpec(
        previewId = "sticker",
        className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
        functionName = "WrapContentStickerPreview",
        widthPx = 800,
        heightPx = 1600,
        wrapWidth = true,
        wrapHeight = true,
        density = 2.0f,
        showBackground = true,
        outputBaseName = baseName,
        overrides = overrides,
      )
    val result = engine.render(spec, requestId = 1L, classLoader = javaClass.classLoader)
    assertNotNull("pngPath must be populated", result.pngPath)
    val png = File(result.pngPath!!)
    assertTrue("rendered PNG must exist: ${png.absolutePath}", png.exists())
    return png
  }

  /** Persist a size-mode render to `build/size-evidence/` for the PR's visual evidence. */
  private fun keepEvidence(png: File, name: String) {
    val evidenceDir = File("build/size-evidence").apply { mkdirs() }
    png.copyTo(File(evidenceDir, "$name.png"), overwrite = true)
  }

  @Test
  fun maxBoundCapsTheWrapCropBelowTheComponentsIntrinsicSize() {
    val engine = RenderEngine(outputDir = tempFolder.newFolder("renders"))
    // The sticker's intrinsic size is 176 px (56 dp badge + 16 dp padding each side, × density 2).
    // A max bound of 100 px lowers the wrap ceiling below that, so the crop lands at the bound.
    val png =
      renderBounded(
        engine,
        ee.schimke.composeai.daemon.protocol.PreviewOverrides(maxWidthPx = 100, maxHeightPx = 100),
        "sticker-max-100",
      )
    keepEvidence(png, "size-max-100")
    val (w, h) = dims(png)
    assertEquals("max width bound caps the crop", 100, w)
    assertEquals("max height bound caps the crop", 100, h)
  }

  @Test
  fun minBoundForcesTheWrapCropAboveTheComponentsIntrinsicSize() {
    val engine = RenderEngine(outputDir = tempFolder.newFolder("renders"))
    // A min bound of 400 px (> the 176 px intrinsic) forces the wrapped box to that floor, and the
    // scene is enlarged to fit so the component isn't clipped before the crop.
    val png =
      renderBounded(
        engine,
        ee.schimke.composeai.daemon.protocol.PreviewOverrides(minWidthPx = 400, minHeightPx = 400),
        "sticker-min-400",
      )
    keepEvidence(png, "size-min-400")
    val (w, h) = dims(png)
    assertEquals("min width bound raises the crop floor", 400, w)
    assertEquals("min height bound raises the crop floor", 400, h)
  }

  @Test
  fun withinBoundKeepsTheCropInsideTheMinMaxRange() {
    val engine = RenderEngine(outputDir = tempFolder.newFolder("renders"))
    // The 176 px intrinsic already sits inside [120, 400], so a "within" range leaves it unchanged
    // —
    // the bounds only bite when the component would fall outside them.
    val png =
      renderBounded(
        engine,
        ee.schimke.composeai.daemon.protocol.PreviewOverrides(
          minWidthPx = 120,
          minHeightPx = 120,
          maxWidthPx = 400,
          maxHeightPx = 400,
        ),
        "sticker-within-120-400",
      )
    keepEvidence(png, "size-within-120-400")
    val (w, h) = dims(png)
    assertTrue("width stays within the range (got $w)", w in 120..400)
    assertTrue("height stays within the range (got $h)", h in 120..400)
    assertEquals("unconstrained intrinsic is preserved inside the range", 176, w)
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
