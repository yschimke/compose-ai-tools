package com.example.sampleremotecompose

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * The XR examples rely on two things a plain render check would not notice: the documents paint no
 * background (so a host surface keeps alpha and composites them over a room), and
 * `spatial/wall/scene.json` stays a valid SpatialScene whose panels match their textures.
 * `renderBeforeUnitTests` renders first.
 */
class SpatialRemoteComposeTest {

  private val rendersDir = File("build/compose-previews/renders")
  private val wallSceneDir = File("spatial/wall")

  @Test
  fun `cube document paints only the cube`() {
    val img = render("RemoteCube3dPreview")
    assertThat(alpha(img, 2, 2)).isEqualTo(0)
    assertThat(alpha(img, img.width - 3, img.height - 3)).isEqualTo(0)
    // The cube fills the centre, so the projection, culling and fill all ran.
    assertThat(alpha(img, img.width / 2, img.height / 2)).isEqualTo(255)
  }

  @Test
  fun `cube from below shows different faces than the default orientation`() {
    val default = render("RemoteCube3dPreview")
    val fromBelow = render("RemoteCube3dFromBelowPreview")
    // Same document shape, other initial angles: the projected centre pixel changes face colour.
    assertThat(fromBelow.getRGB(fromBelow.width / 2, fromBelow.height / 2))
      .isNotEqualTo(default.getRGB(default.width / 2, default.height / 2))
  }

  @Test
  fun `spinning cube animates across its captured frames`() {
    val gif = renderFile(rendersDir, "RemoteCube3dSpinningPreview", ext = "gif")
    assertWithMessage("render $gif").that(gif.exists()).isTrue()
    val reader = ImageIO.getImageReadersByFormatName("gif").next()
    val frames =
      ImageIO.createImageInputStream(gif).use { input ->
        reader.input = input
        (0 until reader.getNumImages(true)).map { reader.read(it) }
      }
    assertThat(frames.size).isGreaterThan(2)
    // The encoder writes full frames, so a pose that never changed would decode identically.
    val first = frames.first()
    val middle = frames[frames.size / 2]
    val changed =
      (0 until first.height).sumOf { y ->
        (0 until first.width).count { x -> first.getRGB(x, y) != middle.getRGB(x, y) }
      }
    assertWithMessage("pixels that moved between frame 0 and ${frames.size / 2}")
      .that(changed)
      .isGreaterThan(first.width * first.height / 20)
  }

  @Test
  fun `tapping the textured cube's swatch swaps its texture`() {
    val frames = gifFrames("RemoteTexturedCubeInteractionPreview")
    // The script taps the cube twice, the swatch, then the cube: the opening frames show bricks
    // and the closing ones tiles. Bricks are warm (red > blue), tiles cool (blue >= red).
    assertThat(warmth(frames.first())).isGreaterThan(0)
    assertThat(warmth(frames.last())).isLessThan(0)
  }

  @Test
  fun `wall card is transparent outside its rounded card`() {
    val img = render("RemoteWallCardPreview")
    assertThat(alpha(img, 0, 0)).isEqualTo(0)
    assertThat(alpha(img, img.width / 2, img.height / 2)).isGreaterThan(200)
  }

  @Test
  fun `wall scene is a SpatialScene whose panels match their textures`() {
    val scene = Json.parseToJsonElement(File(wallSceneDir, "scene.json").readText()).jsonObject
    assertThat(scene.getValue("version").jsonPrimitive.int).isEqualTo(1)
    assertThat(scene.getValue("units").jsonPrimitive.content).isEqualTo("dp")

    val panels = scene.getValue("panels").jsonArray.map { it.jsonObject }
    assertThat(panels.map { it.getValue("id").jsonPrimitive.content })
      .containsExactly("wall-card", "cube")
    for (panel in panels) {
      val id = panel.getValue("id").jsonPrimitive.content
      val texture = File(wallSceneDir, panel.getValue("texture").jsonPrimitive.content)
      assertWithMessage("texture for $id").that(texture.exists()).isTrue()
      val img = ImageIO.read(texture)

      val size = panel.getValue("sizeDp").jsonObject
      val width = size.getValue("width").jsonPrimitive.content.toFloat()
      val height = size.getValue("height").jsonPrimitive.content.toFloat()
      val aspectError = abs(img.width / img.height.toFloat() - width / height)
      assertWithMessage("aspect of $id: ${img.width}x${img.height} vs ${width}x$height dp")
        .that(aspectError)
        .isLessThan(0.02f)
      // No background baked into the texture: the compositor's room shows through the corner.
      assertWithMessage("corner alpha of $id").that(alpha(img, 0, 0)).isEqualTo(0)
    }
  }

  private fun gifFrames(stem: String): List<BufferedImage> {
    val gif = renderFile(rendersDir, stem, ext = "gif")
    assertWithMessage("render $gif").that(gif.exists()).isTrue()
    val reader = ImageIO.getImageReadersByFormatName("gif").next()
    return ImageIO.createImageInputStream(gif).use { input ->
      reader.input = input
      (0 until reader.getNumImages(true)).map { reader.read(it) }
    }
  }

  /** Mean red minus mean blue over the central square, where the cube is. */
  private fun warmth(img: BufferedImage): Int {
    var sum = 0L
    var count = 0
    for (y in img.height / 3 until img.height * 2 / 3) {
      for (x in img.width / 3 until img.width * 2 / 3) {
        val rgb = img.getRGB(x, y)
        sum += ((rgb shr 16) and 0xff) - (rgb and 0xff)
        count++
      }
    }
    return (sum / count).toInt()
  }

  private fun render(stem: String): BufferedImage {
    val png = renderFile(rendersDir, stem)
    assertWithMessage("render $png").that(png.exists()).isTrue()
    return ImageIO.read(png)
  }

  private fun alpha(img: BufferedImage, x: Int, y: Int): Int = (img.getRGB(x, y) ushr 24) and 0xff
}
