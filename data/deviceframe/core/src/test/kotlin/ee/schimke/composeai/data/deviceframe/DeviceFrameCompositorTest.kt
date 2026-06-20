package ee.schimke.composeai.data.deviceframe

import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceFrameCompositorTest {

  private fun solid(width: Int, height: Int, color: Color): BufferedImage {
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.color = color
    g.fillRect(0, 0, width, height)
    g.dispose()
    return img
  }

  private fun loadWearRoundBack(): BufferedImage {
    val stream =
      requireNotNull(javaClass.getResourceAsStream("/deviceframe/wear_round/back.png")) {
        "wear_round/back.png fixture missing"
      }
    return requireNotNull(ImageIO.read(stream)) { "could not decode wear_round/back.png" }
  }

  @Test
  fun outputMatchesFrameCanvasSize() {
    val back = loadWearRoundBack()
    val out =
      DeviceFrameCompositor.composite(
        screenshot = solid(454, 454, Color.RED),
        layers = mapOf(DeviceArtCatalog.BACK to back),
        spec = DeviceArtCatalog.WEAR_ROUND,
      )
    assertEquals(back.width, out.width)
    assertEquals(back.height, out.height)
  }

  @Test
  fun screenCentreShowsScreenshotAndCornersStayBezel() {
    val back = loadWearRoundBack()
    val spec = DeviceArtCatalog.WEAR_ROUND
    val out =
      DeviceFrameCompositor.composite(
        screenshot = solid(454, 454, Color.RED),
        layers = mapOf(DeviceArtCatalog.BACK to back),
        spec = spec,
      )
    // Centre of the round screen must be the screenshot colour.
    val cx = spec.screenX + spec.screenWidth / 2
    val cy = spec.screenY + spec.screenHeight / 2
    assertEquals(Color.RED.rgb, out.getRGB(cx, cy))

    // A pixel just inside the screen's bounding-box corner is outside the inscribed circle, so it
    // must NOT be the screenshot colour (the bezel / transparency shows through there).
    val corner = out.getRGB(spec.screenX + 2, spec.screenY + 2)
    assertTrue(
      "round clip should keep the bounding-box corner free of screenshot pixels",
      Color(corner, true).red != 255 ||
        Color(corner, true).green != 0 ||
        Color(corner, true).blue != 0,
    )
  }

  @Test
  fun missingBackLayerFailsLoudly() {
    val ex =
      runCatching {
          DeviceFrameCompositor.composite(
            screenshot = solid(10, 10, Color.BLUE),
            layers = emptyMap(),
            spec = DeviceArtCatalog.WEAR_ROUND,
          )
        }
        .exceptionOrNull()
    assertNotNull("compositing without a back layer should throw", ex)
  }

  @Test
  fun syntheticRoundedRectFrameClipsScreenshot() {
    // A self-contained frame (no fixture): 100x100 magenta bezel with a 60x60 square screen window.
    val back = solid(100, 100, Color.MAGENTA)
    val spec =
      DeviceArtCatalog.DeviceArtSpec(
        artId = "test_square",
        screenX = 20,
        screenY = 20,
        screenWidth = 60,
        screenHeight = 60,
        cornerRadius = 0,
        resources = listOf(DeviceArtCatalog.BACK),
      )
    val out =
      DeviceFrameCompositor.composite(
        screenshot = solid(120, 120, Color.GREEN),
        layers = mapOf(DeviceArtCatalog.BACK to back),
        spec = spec,
      )
    assertEquals("screen centre is the screenshot", Color.GREEN.rgb, out.getRGB(50, 50))
    assertEquals("outside the screen window stays bezel", Color.MAGENTA.rgb, out.getRGB(5, 5))
  }
}
