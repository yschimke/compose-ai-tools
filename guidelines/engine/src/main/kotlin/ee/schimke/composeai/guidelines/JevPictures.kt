package ee.schimke.composeai.guidelines

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A subject's picture as the jev checker shows it: a PNG data URL fitted into the sizes Jev reads.
 * [scale] is how many of its pixels make one of the render's; [padded] says it was centred on a
 * grey margin to reach Jev's least size, which is not part of the render.
 */
internal class JevPicture(
  val dataUrl: String,
  val width: Int,
  val height: Int,
  val scale: Double,
  val padded: Boolean,
  val caption: String,
) {
  /** Its estimated input tokens, counted as the vision path counts a picture. */
  val tokens: Int
    get() =
      max(PreviewGuidelineRequests.PICTURE_TOKENS.toLong(), width.toLong() * height / 750).toInt()

  /** The image part Jev reads: OpenRouter's Decisions `image_url` part. */
  fun part(): JsonObject = buildJsonObject {
    put("type", "image_url")
    putJsonObject("image_url") { put("url", dataUrl) }
  }
}

/**
 * Fits renders into Jev's image limits (OpenRouter's multimodal Decisions guide, and [MIN_SIDE]):
 * 280 to 1,568 pixels a side, 78,400 to 2,458,624 pixels in all, at most 16 MiB a file. A render
 * too large is scaled down; one too small is scaled up by whole-pixel repetition, so an edge stays
 * as sharp as it was drawn, and centred on a grey margin only where its shape keeps one side short.
 */
internal object JevPictures {
  /**
   * The least side sent: 280, not the documented 256. Measured on typesafe/jev-1.13 (2026-11-01):
   * any image with a 256px side answered 400 "Invalid image dimensions" (300×256, 512×256,
   * 1024×256, 256×768), while 280px sides passed (280×280, 280×900, 1100×280, 1300×280). 280² is
   * also [MIN_PIXELS], so a square at the least side meets both.
   */
  const val MIN_SIDE: Int = 280
  const val MAX_SIDE: Int = 1_568
  const val MIN_PIXELS: Long = 78_400
  const val MAX_PIXELS: Long = 2_458_624
  const val MAX_BYTES: Int = 16 * 1024 * 1024

  /**
   * At most this many pictures a request. TypeSafe caps all processed images of a request together
   * at 32 MiB, and a full-size picture decodes to almost 10.
   */
  const val MAX_PICTURES: Int = 2

  /** The margin a picture is padded with: mid grey, unlike most themes' background. */
  private val MARGIN = Color(0x80, 0x80, 0x80)

  /** The scaled size of a [width]×[height] picture and the canvas it is drawn on. */
  internal class Layout(
    val scale: Double,
    val width: Int,
    val height: Int,
    val canvasWidth: Int,
    val canvasHeight: Int,
  ) {
    val padded: Boolean
      get() = canvasWidth != width || canvasHeight != height
  }

  internal fun layout(width: Int, height: Int): Layout {
    val pixels = width.toDouble() * height
    val longest = max(width, height).toDouble()
    val shortest = min(width, height).toDouble()
    // The most it may grow (or the least it must shrink) to stay inside the upper limits.
    val ceiling = min(MAX_SIDE / longest, sqrt(MAX_PIXELS / pixels))
    var scale = min(1.0, ceiling)
    if (scale >= 1.0) {
      // Small: grow by a whole factor, so each render pixel stays a crisp block.
      val wanted = max(MIN_SIDE / shortest, sqrt(MIN_PIXELS / pixels))
      if (wanted > 1.0) scale = min(ceil(wanted), floor(ceiling)).coerceAtLeast(1.0)
    }
    val w = (width * scale).roundToInt().coerceIn(1, MAX_SIDE)
    val h = (height * scale).roundToInt().coerceIn(1, MAX_SIDE)
    var cw = max(w, MIN_SIDE)
    var ch = max(h, MIN_SIDE)
    if (cw.toLong() * ch < MIN_PIXELS) {
      if (cw <= ch) cw = min(MAX_SIDE, ceil(MIN_PIXELS.toDouble() / ch).toInt())
      else ch = min(MAX_SIDE, ceil(MIN_PIXELS.toDouble() / cw).toInt())
    }
    return Layout(scale, w, h, cw, ch)
  }

  /** The first [MAX_PICTURES] of [pictures] Jev can read, fitted; those that are not PNGs drop. */
  fun of(pictures: List<SubjectPicture>): List<JevPicture> =
    pictures.asSequence().mapNotNull { fit(it) }.take(MAX_PICTURES).toList()

  /** [picture] fitted into Jev's limits, or null when it is not an image ImageIO reads. */
  fun fit(picture: SubjectPicture): JevPicture? {
    val image =
      runCatching { ImageIO.read(ByteArrayInputStream(picture.png)) }.getOrNull() ?: return null
    val layout = layout(image.width, image.height)
    val png =
      if (
        layout.scale == 1.0 &&
          !layout.padded &&
          picture.png.size <= MAX_BYTES &&
          PreviewGuidelineRequests.pngSize(picture.png) != null
      ) {
        picture.png
      } else {
        encode(draw(image, layout)) ?: return null
      }
    if (png.size > MAX_BYTES) return null
    return JevPicture(
      dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(png),
      width = layout.canvasWidth,
      height = layout.canvasHeight,
      scale = layout.scale,
      padded = layout.padded,
      caption = caption(picture, layout),
    )
  }

  private fun draw(image: BufferedImage, layout: Layout): BufferedImage {
    val out = BufferedImage(layout.canvasWidth, layout.canvasHeight, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    try {
      if (layout.padded) {
        g.color = MARGIN
        g.fillRect(0, 0, layout.canvasWidth, layout.canvasHeight)
      }
      g.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION,
        if (layout.scale > 1.0) RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
        else RenderingHints.VALUE_INTERPOLATION_BICUBIC,
      )
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
      val x = (layout.canvasWidth - layout.width) / 2
      val y = (layout.canvasHeight - layout.height) / 2
      g.drawImage(image, x, y, layout.width, layout.height, null)
    } finally {
      g.dispose()
    }
    return out
  }

  private fun encode(image: BufferedImage): ByteArray? =
    ByteArrayOutputStream().also { if (!ImageIO.write(image, "png", it)) return null }.toByteArray()

  private fun caption(picture: SubjectPicture, layout: Layout): String = buildString {
    append(picture.description ?: "the ${picture.kind} render")
    append(" (${picture.widthDp}×${picture.heightDp}dp")
    if (layout.scale != 1.0) {
      append(
        ", shown at ${String.format(java.util.Locale.ROOT, "%.2f", layout.scale)}× its pixels; " +
          "node bounds are in the render's own pixels",
      )
    }
    if (layout.padded) {
      append("; the flat grey margin around it only pads it to a readable size and is not drawn")
    }
    append(")")
  }
}
