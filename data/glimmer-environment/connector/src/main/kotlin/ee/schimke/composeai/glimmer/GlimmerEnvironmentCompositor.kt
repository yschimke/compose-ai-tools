package ee.schimke.composeai.glimmer

import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Tool-owned environment presets for additive Glimmer preview captures. */
enum class GlimmerEnvironment {
  Light,
  Dark,
  Busy,
  VeniceCanalCats,
}

/**
 * ADD-composites an opaque RGB-on-black Glimmer capture over a simulated environment.
 *
 * The source capture is never treated as alpha: black is additive zero and each colour channel is
 * saturated independently. [applyToPng] preserves the source next to the composited result as
 * `<basename>.raw.png`.
 */
object GlimmerEnvironmentCompositor {
  fun applyToPng(file: File, environment: GlimmerEnvironment): File {
    val raw = file.resolveSibling("${file.nameWithoutExtension}.raw.png")
    file.copyTo(raw, overwrite = true)
    val capture = ImageIO.read(raw) ?: error("Unable to decode Glimmer capture: $raw")
    ImageIO.write(composite(capture, environment), "png", file)
    return raw
  }

  fun composite(capture: BufferedImage, environment: GlimmerEnvironment): BufferedImage {
    val backdrop = backdrop(environment, capture.width, capture.height)
    val result = BufferedImage(capture.width, capture.height, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until capture.height) {
      for (x in 0 until capture.width) {
        val world = backdrop.getRGB(x, y)
        val display = capture.getRGB(x, y)
        val red = (((world ushr 16) and 0xff) + ((display ushr 16) and 0xff)).coerceAtMost(255)
        val green = (((world ushr 8) and 0xff) + ((display ushr 8) and 0xff)).coerceAtMost(255)
        val blue = ((world and 0xff) + (display and 0xff)).coerceAtMost(255)
        result.setRGB(x, y, (0xff shl 24) or (red shl 16) or (green shl 8) or blue)
      }
    }
    return result
  }

  private fun backdrop(
    environment: GlimmerEnvironment,
    width: Int,
    height: Int,
  ): BufferedImage =
    when (environment) {
      GlimmerEnvironment.Light -> lightBackdrop(width, height)
      else -> photoBackdrop(environment, width, height)
    }

  private fun photoBackdrop(
    environment: GlimmerEnvironment,
    width: Int,
    height: Int,
  ): BufferedImage {
    val resource =
      when (environment) {
        GlimmerEnvironment.Dark -> "/glimmer-environments/env_dark.jpg"
        GlimmerEnvironment.Busy -> "/glimmer-environments/env_busy.jpg"
        GlimmerEnvironment.VeniceCanalCats ->
          "/glimmer-environments/env_venice_canal_cats.jpg"
        GlimmerEnvironment.Light -> error("Light is procedural")
      }
    val source =
      GlimmerEnvironmentCompositor::class.java.getResourceAsStream(resource)?.use(ImageIO::read)
        ?: error("Missing Glimmer environment resource: $resource")
    val scale = maxOf(width.toDouble() / source.width, height.toDouble() / source.height)
    val scaledWidth = (source.width * scale).toInt()
    val scaledHeight = (source.height * scale).toInt()
    val result = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    result.createGraphics().use { graphics ->
      graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
      graphics.drawImage(
        source,
        (width - scaledWidth) / 2,
        (height - scaledHeight) / 2,
        scaledWidth,
        scaledHeight,
        null,
      )
    }
    return result
  }

  private fun lightBackdrop(width: Int, height: Int): BufferedImage {
    val result = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    result.createGraphics().use { graphics ->
      graphics.paint =
        GradientPaint(0f, 0f, Color(0xAE, 0xDD, 0xFF), 0f, height.toFloat(), Color(0xD2, 0xEA, 0xFF))
      graphics.fillRect(0, 0, width, height)
      val hillY = (height * 0.75f).toInt()
      graphics.color = Color(0x98, 0xC7, 0x78)
      graphics.fillRect(0, hillY, width, height - hillY)
      val diameter = maxOf(36, minOf(width, height) / 10)
      graphics.color = Color(0xFF, 0xE5, 0x99)
      graphics.fillOval(width - diameter - width / 10, height / 10, diameter, diameter)
    }
    return result
  }
}

private inline fun <T : java.awt.Graphics2D, R> T.use(block: (T) -> R): R =
  try {
    block(this)
  } finally {
    dispose()
  }
