package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.FocusOverride
import ee.schimke.composeai.io.SystemFileSystem
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Rectangle
import java.awt.RenderingHints
import java.io.File
import javax.imageio.ImageIO
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Post-capture stroke + label overlay drawn over the focused element's bounds — the Compose
 * Multiplatform Desktop counterpart of `:data-focus-connector`'s `FocusOverlay`, and
 * pixel-for-pixel the same marker (same red, same 3 px stroke, same label pill) so an `overlay =
 * true` capture reads identically whichever backend produced it.
 *
 * The one difference is where the bounds come from. The Android overlay reflects into
 * `AndroidComposeView.focusOwner.getFocusRect()` because it has a `View` to reach through; desktop
 * has none, so the caller passes the focused node's `boundsInRoot` from the semantics tree instead.
 * That keeps the reflection — the fragile half — off this side entirely.
 *
 * Saves the pre-overlay capture as `<basename>.raw.png` alongside so reviewers can A/B against the
 * unmarked image. Failures are silent: an overlay is a review aid, not a render contract.
 */
object FocusOverlayDesktop {

  fun apply(
    rect: Rectangle,
    outputFile: File,
    focus: FocusOverride,
    fileSystem: FileSystem = SystemFileSystem,
  ) {
    if (rect.width <= 0 || rect.height <= 0) return

    val rawFile =
      File(outputFile.parentFile, outputFile.nameWithoutExtension + ".raw." + outputFile.extension)
    runCatching { outputFile.copyTo(rawFile, overwrite = true) }

    val image =
      runCatching {
          ImageIO.read(fileSystem.read(outputFile.path.toPath()) { readByteArray() }.inputStream())
        }
        .getOrNull() ?: return
    val g = image.createGraphics()
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g.setRenderingHint(
        RenderingHints.KEY_TEXT_ANTIALIASING,
        RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
      )
      g.color = Color(0xFF, 0x40, 0x40, 0xFF)
      g.stroke = BasicStroke(3f)
      g.drawRect(rect.x, rect.y, rect.width, rect.height)

      val label = labelOf(focus)
      g.font = Font("SansSerif", Font.BOLD, 16)
      val metrics = g.fontMetrics
      val textWidth = metrics.stringWidth(label)
      val textHeight = metrics.height
      val pillX = rect.x
      val pillY = (rect.y - textHeight - 4).coerceAtLeast(0)
      g.color = Color(0xFF, 0x40, 0x40, 0xE0)
      g.fillRoundRect(pillX, pillY, textWidth + 12, textHeight + 4, 8, 8)
      g.color = Color.WHITE
      g.drawString(label, pillX + 6, pillY + textHeight - 4)
    } finally {
      g.dispose()
    }
    runCatching {
      fileSystem.write(outputFile.path.toPath()) { ImageIO.write(image, "png", outputStream()) }
    }
  }

  private fun labelOf(focus: FocusOverride): String {
    val direction = focus.direction
    val step = focus.step
    val tabIndex = focus.tabIndex
    return when {
      direction != null && step != null -> "step $step • ${direction.name}"
      tabIndex != null -> "index $tabIndex"
      else -> "focus"
    }
  }
}
