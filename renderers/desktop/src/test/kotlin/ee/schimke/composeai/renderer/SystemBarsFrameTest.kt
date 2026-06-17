package ee.schimke.composeai.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Density
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Desktop twin of the Android renderer's `SystemBarsFrameTest`. Verifies the ported
 * [SystemBarsFrame] paints a status bar at the top and a navigation pill bar at the bottom on the
 * Skiko backend, with the same light/dark tint behaviour — the cross-backend parity issue #1930
 * relies on. Renders through [ImageComposeScene] (the same path `DesktopRendererMain` uses),
 * encodes to PNG, and samples pixel rows at known y-offsets.
 *
 * Also covers [shouldApplySystemBars]'s phone-shape gate so a round/Wear surface or a chrome-less
 * default doesn't accidentally get phone bars stamped on it.
 */
class SystemBarsFrameTest {

  private fun renderToImage(
    width: Int,
    height: Int,
    uiMode: Int,
    body: @Composable () -> Unit,
  ): BufferedImage {
    val scene = ImageComposeScene(width = width, height = height, density = Density(1f))
    try {
      scene.setContent {
        CompositionLocalProvider(LocalInspectionMode provides true) {
          SystemBarsFrame(uiMode = uiMode, content = body)
        }
      }
      scene.render()
      val image = scene.render()
      val png =
        image.encodeToData(EncodedImageFormat.PNG)
          ?: error("Failed to encode SystemBarsFrame test image")
      return ByteArrayInputStream(png.bytes).use { ImageIO.read(it) }
    } finally {
      scene.close()
    }
  }

  @Test
  fun `light mode paints translucent bars over the top and bottom of the content`() {
    val width = 200
    val height = 600
    val image =
      renderToImage(width, height, uiMode = 0) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Red))
      }

    val centre = image.getRGB(width / 2, height / 2)
    // Centre is body content — the red fill is preserved untouched.
    assertTrue(
      "centre pixel should remain a strong red, got rgb=" +
        "(${centre.red()},${centre.green()},${centre.blue()})",
      centre.red() > 200 && centre.green() < 50 && centre.blue() < 50,
    )

    // Status bar lifts the red toward pink via its translucent white tint. Sample the bar middle
    // (clear of the left clock text and the right battery glyph).
    val status = image.getRGB(width / 2, 4)
    assertTrue(
      "status bar pixel should have a green/blue lift from the white tint, " +
        "got rgb=(${status.red()},${status.green()},${status.blue()})",
      status.green() > 40 && status.blue() > 40,
    )

    val nav = image.getRGB(width / 2, height - 4)
    assertTrue(
      "nav bar pixel should have a green/blue lift from the white tint, " +
        "got rgb=(${nav.red()},${nav.green()},${nav.blue()})",
      nav.green() > 40 && nav.blue() > 40,
    )
  }

  @Test
  fun `dark mode darkens the top strip rather than lightening it`() {
    val width = 200
    val height = 600
    // 0x20 == Configuration.UI_MODE_NIGHT_YES — the only bit SystemBarsFrame inspects.
    val image =
      renderToImage(width, height, uiMode = 0x20) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Red))
      }

    val status = image.getRGB(width / 2, 4)
    // Translucent black tint pulls the red channel below the original 255 fill.
    assertTrue(
      "dark-mode status bar pixel should be darkened by the night tint, got red=${status.red()}",
      status.red() < 230,
    )
  }

  @Test
  fun `gate applies bars only for phone-shape system-ui captures`() {
    assertTrue(shouldApplySystemBars(showSystemUi = true, device = "id:pixel_8", kind = "COMPOSE"))
    assertTrue(shouldApplySystemBars(showSystemUi = true, device = null, kind = null))
    // No showSystemUi → chrome-less default.
    assertFalse(
      shouldApplySystemBars(showSystemUi = false, device = "id:pixel_8", kind = "COMPOSE")
    )
    // Round / Wear surfaces keep their own framing.
    assertFalse(
      shouldApplySystemBars(showSystemUi = true, device = "id:wearos_large_round", kind = "COMPOSE")
    )
    assertFalse(
      shouldApplySystemBars(
        showSystemUi = true,
        device = "spec:width=200dp,height=200dp,isRound=true",
        kind = "COMPOSE",
      )
    )
    // Tiles fill the whole watch face — bars don't apply.
    assertFalse(shouldApplySystemBars(showSystemUi = true, device = "id:pixel_8", kind = "TILE"))
  }

  private fun Int.red() = (this shr 16) and 0xFF

  private fun Int.green() = (this shr 8) and 0xFF

  private fun Int.blue() = this and 0xFF
}
