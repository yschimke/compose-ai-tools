package ee.schimke.composeai.daemon

import android.view.View
import ee.schimke.composeai.daemon.protocol.FocusOverride
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Rectangle
import java.awt.RenderingHints
import java.io.File
import javax.imageio.ImageIO

/**
 * Post-capture stroke + label overlay drawn over the focused element's bounds. Sourced from
 * `AndroidComposeView.focusOwner.getFocusRect()` reflectively — the focus owner isn't on
 * compose-ui's public surface. Saves the pre-overlay capture as `<basename>.raw.png` alongside so
 * reviewers can A/B against the unmarked image.
 *
 * Skipped silently when the view isn't an AndroidComposeView, the focus owner has no active
 * focus, or the focus rect is empty — overlays are a review aid, not a render contract.
 */
object FocusOverlay {

  fun apply(view: View?, outputFile: File, focus: FocusOverride) {
    if (view == null) return
    val rect = readFocusRect(view) ?: return
    if (rect.width <= 0 || rect.height <= 0) return

    val rawFile = File(outputFile.parentFile, outputFile.nameWithoutExtension + ".raw." + outputFile.extension)
    runCatching {
      outputFile.copyTo(rawFile, overwrite = true)
    }

    val image = runCatching { ImageIO.read(outputFile) }.getOrNull() ?: return
    val g = image.createGraphics()
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
      g.color = Color(0xFF, 0x40, 0x40, 0xFF)
      g.stroke = BasicStroke(3f)
      g.drawRect(rect.x, rect.y, rect.width, rect.height)

      val label = labelOf(focus)
      val font = Font("SansSerif", Font.BOLD, 16)
      g.font = font
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
    runCatching { ImageIO.write(image, "png", outputFile) }
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

  /**
   * Reflects into `AndroidComposeView.focusOwner.getFocusRect()` to retrieve the focused element's
   * bounds. Returns `null` when the view isn't an AndroidComposeView, the focus owner has no
   * active focus, or any reflective lookup fails. Failure here is silent because the overlay is a
   * review aid, not a render contract.
   */
  private fun readFocusRect(view: View): Rectangle? {
    return runCatching {
      val getFocusOwner = view::class.java.methods.firstOrNull { it.name == "getFocusOwner" } ?: return null
      val owner = getFocusOwner.invoke(view) ?: return null
      val getFocusRect = owner::class.java.methods.firstOrNull { it.name == "getFocusRect" } ?: return null
      val rect = getFocusRect.invoke(owner) ?: return null
      val left = (rect::class.java.getMethod("getLeft").invoke(rect) as? Float) ?: return null
      val top = (rect::class.java.getMethod("getTop").invoke(rect) as? Float) ?: return null
      val right = (rect::class.java.getMethod("getRight").invoke(rect) as? Float) ?: return null
      val bottom = (rect::class.java.getMethod("getBottom").invoke(rect) as? Float) ?: return null
      // Compose's `Rect` reports `left = top = Float.POSITIVE_INFINITY` (`Rect.Zero`) when no
      // focus is active; clamp to int silently here.
      if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) {
        return null
      }
      Rectangle(left.toInt(), top.toInt(), (right - left).toInt(), (bottom - top).toInt())
    }.getOrNull()
  }
}
