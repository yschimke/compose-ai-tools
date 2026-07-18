package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Android (Robolectric) counterpart of `:daemon:desktop`'s `RenderEngineWrapContentTest`
 * size-bound cases — proves the Android backend honours the wrapped-axis content-size bounds
 * (`PreviewOverrides.{min,max}{Width,Height}Px`, the Max / Min / Within size modes) instead of
 * silently ignoring them.
 *
 * Before the fix the Android [RenderEngine]'s wrap measure used `minWidth = 0` / `maxWidth =
 * sandbox` with no reference to the bounds, so a `compose-preview serve` (or MCP / matrix) size
 * override against an Android module dropped the constraint — the sticker rendered at its full
 * intrinsic size no matter what "Within 120–400" / "Max 100" the viewer requested. With the fix the
 * wrap measure is clamped to `[min, max]` (and the Robolectric sandbox enlarged for a min bound
 * larger than the frame), then the AS-parity crop trims the PNG to the resulting size — matching the
 * desktop daemon exactly.
 *
 * Drives the production path: the base64 `overrides=` payload token is decoded into
 * `RenderSpec.overrides` by `parseFromPayloadOrNull`, the same shape the host builds from a
 * `renderNow.overrides` request. [WrapContentStickerPreview]'s intrinsic size is 176 px (56 dp badge
 * + 16 dp padding each side, × density 2), so each bound visibly reshapes the crop.
 */
class RenderEngineSizeBoundsTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private val json = Json { encodeDefaults = false }

  /** Render the sticker fixture wrap-content with [overrides]; returns the PNG dimensions. */
  private fun renderBounded(
    host: RobolectricHost,
    overrides: PreviewOverrides,
    base: String,
  ): Pair<Int, Int> {
    val overridesB64 =
      Base64.getEncoder()
        .encodeToString(
          json.encodeToString(PreviewOverrides.serializer(), overrides).toByteArray()
        )
    val result =
      host.submit(
        RenderRequest.Render(
          payload =
            "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
              "functionName=WrapContentStickerPreview;" +
              // Generous 800×1600 px wrap sandbox (like the desktop test) so the *bound*, not the
              // frame, decides the measured intrinsic size the crop keeps.
              "widthPx=800;heightPx=1600;density=2.0;" +
              "wrapWidth=true;wrapHeight=true;" +
              "showBackground=true;outputBaseName=$base;" +
              "overrides=$overridesB64",
        ),
        timeoutMs = 120_000,
      )
    assertNotNull("$base: pngPath must be populated", result.pngPath)
    val png = File(result.pngPath!!)
    assertTrue("$base: rendered PNG must exist", png.exists())
    // Keep the size-mode renders for the PR's visual evidence (build dir, not committed).
    File("build/size-evidence").apply { mkdirs() }.let { png.copyTo(File(it, "$base.png"), true) }
    val img =
      ByteArrayInputStream(png.readBytes()).use { ImageIO.read(it) } ?: error("$base: no decode")
    return img.width to img.height
  }

  @Test
  fun maxBoundCapsTheWrapCropBelowTheComponentsIntrinsicSize() {
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, tempFolder.newFolder("renders").absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val host = RobolectricHost()
    host.start()
    try {
      // A max bound of 100 px lowers the wrap ceiling below the 176 px intrinsic, so the crop lands
      // at the bound on both axes.
      val (w, h) =
        renderBounded(
          host,
          PreviewOverrides(maxWidthPx = 100, maxHeightPx = 100),
          "android-size-max-100",
        )
      assertEquals("max width bound caps the crop", 100, w)
      assertEquals("max height bound caps the crop", 100, h)
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun minBoundForcesTheWrapCropAboveTheComponentsIntrinsicSize() {
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, tempFolder.newFolder("renders").absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val host = RobolectricHost()
    host.start()
    try {
      // A min bound of 400 px (> the 176 px intrinsic) raises the wrap floor to that size on both
      // axes; the sandbox is generous enough that nothing is clipped before the crop.
      val (w, h) =
        renderBounded(
          host,
          PreviewOverrides(minWidthPx = 400, minHeightPx = 400),
          "android-size-min-400",
        )
      assertEquals("min width bound raises the crop floor", 400, w)
      assertEquals("min height bound raises the crop floor", 400, h)
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun withinBoundKeepsTheCropInsideTheMinMaxRange() {
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, tempFolder.newFolder("renders").absolutePath)
    System.setProperty("roborazzi.test.record", "true")
    val host = RobolectricHost()
    host.start()
    try {
      // The 176 px intrinsic already sits inside [120, 400], so a "within" range leaves it
      // unchanged — the bounds only bite when the component would fall outside them.
      val (w, h) =
        renderBounded(
          host,
          PreviewOverrides(
            minWidthPx = 120,
            minHeightPx = 120,
            maxWidthPx = 400,
            maxHeightPx = 400,
          ),
          "android-size-within-120-400",
        )
      assertTrue("width stays within the range (got $w)", w in 120..400)
      assertTrue("height stays within the range (got $h)", h in 120..400)
      assertEquals("unconstrained intrinsic is preserved inside the range", 176, w)
    } finally {
      host.shutdown()
    }
  }
}
