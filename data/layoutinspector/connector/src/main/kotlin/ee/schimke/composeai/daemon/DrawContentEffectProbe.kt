package ee.schimke.composeai.daemon

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ModifierInfo
import androidx.compose.ui.platform.InspectableValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * Detects a draw modifier that changes the pixels produced by `drawContent()`.
 *
 * The probe is deliberately capability-based. It replays each `drawWithContent` lambda with an
 * opaque calibration pattern standing in for the node's descendants. A pass-through draw, or a
 * component that paints only behind its content, leaves that pattern byte-for-byte intact. A clip,
 * mask, transform, blend, alpha layer, omitted `drawContent()`, or over-content paint changes it.
 * Those are precisely the effects that cannot be reconstructed from the layout/semantics tree and
 * require a composited frame crop to remain faithful.
 *
 * This avoids component-name heuristics: Remote Compose may draw arbitrary chrome before
 * `drawContent()` and still keeps editable descendants, while a Picker—or any future component with
 * the same opaque content effect—is detected from what its draw actually does.
 */
internal object DrawContentEffectProbe {
  private const val MAX_PIXELS = 8_000_000

  fun modifiesContent(
    modifiers: List<ModifierInfo>,
    width: Int,
    height: Int,
    density: Float,
  ): Boolean {
    if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS) return false
    return modifiers.any { info ->
      if (!info.modifier.isDrawWithContent()) return@any false
      val lambda = DrawCaptureExtractor.drawLambda(info.modifier) ?: return@any false
      runCatching { probe(lambda, width, height, density) }.getOrDefault(false)
    }
  }

  private fun probe(
    lambda: DrawScope.() -> Unit,
    width: Int,
    height: Int,
    density: Float,
  ): Boolean {
    val expected = render(width, height, density) { drawCalibrationPattern() }
    val actual = render(width, height, density) { ProbedContentDrawScope(this).lambda() }
    return !actual.contentEquals(expected)
  }

  private fun render(
    width: Int,
    height: Int,
    density: Float,
    block: DrawScope.() -> Unit,
  ): IntArray {
    val target = ImageBitmap(width, height)
    CanvasDrawScope()
      .draw(
        density = Density(density),
        layoutDirection = LayoutDirection.Ltr,
        canvas = Canvas(target),
        size = Size(width.toFloat(), height.toFloat()),
        block = block,
      )
    return IntArray(width * height).also { target.readPixels(it, 0, 0, width, height, 0, width) }
  }

  private fun DrawScope.drawCalibrationPattern() {
    drawRect(Color(0xFF19A7CE), size = size)
    drawRect(
      Color(0xFFE85D75),
      topLeft = Offset(size.width / 2f, 0f),
      size = Size(size.width / 2f, size.height),
    )
    drawRect(
      Color(0xFFFFD166),
      topLeft = Offset(0f, size.height / 2f),
      size = Size(size.width / 2f, size.height / 2f),
    )
  }

  private class ProbedContentDrawScope(private val delegate: DrawScope) :
    ContentDrawScope, DrawScope by delegate {
    override fun drawContent() {
      delegate.drawCalibrationPattern()
    }
  }

  private fun Any.isDrawWithContent(): Boolean {
    val inspectableName = (this as? InspectableValue)?.nameFallback
    return inspectableName?.contains("drawWithContent", ignoreCase = true) == true ||
      javaClass.simpleName.contains("DrawWithContent", ignoreCase = true)
  }
}
