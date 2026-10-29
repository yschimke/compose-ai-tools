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

  // Guards for the player update. The textured cube's static preview and its recorded taps must
  // keep drawing the whole cube. Daemon `main` + rc-players 2.21.0 recorded the interaction with
  // one
  // face per frame (REMOTE_COMPOSE_XR.md, Example 3), which these fail on: resting frames fell to
  // 21-60% of the static cube's pixels.

  @Test
  fun `textured cube still draws three faces`() {
    val img = render("RemoteTexturedCubePreview")
    // Three lit faces cover about 35% of the frame; one face about 21%.
    assertWithMessage("textured cube pixels / frame (swatch excluded)")
      .that(cubeCoverage(img) / (img.width * img.height * 0.75))
      .isAtLeast(0.30)
  }

  @Test
  fun `textured cube interaction keeps the whole cube at every rest`() {
    val still = cubeCoverage(render("RemoteTexturedCubePreview"))
    val frames = gifFrames("RemoteTexturedCubeInteractionPreview")
    // A quarter turn leaves the cube's silhouette unchanged, so every frame at rest (before the
    // first tap, just before each later tap, and the last) covers what the still does.
    val tapFrames = (1 until frames.size).filter { changed(frames[it - 1], frames[it]) }
    val restFrames =
      (listOf(0, frames.lastIndex) + motionRuns(tapFrames).map { it.first - 1 }).distinct()
    for (index in restFrames) {
      assertWithMessage("frame $index cube pixels / still cube pixels")
        .that(cubeCoverage(frames[index]).toDouble() / still)
        .isAtLeast(0.9)
    }
  }

  // Tripwire for the same update: it fails when the embedded player starts animating the tapped
  // turn, which rc-players 2.21.0 does and the pinned 2.17.5 does not (each tap snaps). When it
  // fails, the pins carry the embedded-player fixes: move RemoteCube3dSpinningPreview from the View
  // player to the default embedded one, update REMOTE_COMPOSE_XR.md's findings, and invert this
  // test into a guard that the turn animates.
  @Test
  fun `tripwire - pinned embedded player snaps the tapped turn`() {
    val frames = gifFrames("RemoteTexturedCubeInteractionPreview")
    val changes = (1 until frames.size).filter { changed(frames[it - 1], frames[it]) }
    val longestMotion = motionRuns(changes).maxOfOrNull { it.count() } ?: 0
    assertWithMessage(
        "consecutive moving frames; > 3 means the embedded player now animates the turn, so the " +
          "pins moved: see the comment on this test"
      )
      .that(longestMotion)
      .isAtMost(3)
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

  /** Pixels that differ from the background corner, outside the swatch's bottom-right quarter. */
  private fun cubeCoverage(img: BufferedImage): Int {
    val background = img.getRGB(0, 0)
    var count = 0
    for (y in 0 until img.height) {
      for (x in 0 until img.width) {
        if (x >= img.width * 3 / 4 && y >= img.height * 3 / 4) continue
        if (channelDistance(img.getRGB(x, y), background) > 24) count++
      }
    }
    return count
  }

  private fun channelDistance(a: Int, b: Int): Int =
    (0 until 4).sumOf { i -> abs(((a ushr (i * 8)) and 0xff) - ((b ushr (i * 8)) and 0xff)) }

  private fun changed(a: BufferedImage, b: BufferedImage): Boolean =
    (0 until a.height).any { y -> (0 until a.width).any { x -> a.getRGB(x, y) != b.getRGB(x, y) } }

  /** Groups ascending frame indices into runs of consecutive indices. */
  private fun motionRuns(indices: List<Int>): List<IntRange> =
    indices.fold(mutableListOf<IntRange>()) { runs, i ->
      val last = runs.lastOrNull()
      if (last != null && last.last == i - 1) runs[runs.lastIndex] = last.first..i
      else runs.add(i..i)
      runs
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
