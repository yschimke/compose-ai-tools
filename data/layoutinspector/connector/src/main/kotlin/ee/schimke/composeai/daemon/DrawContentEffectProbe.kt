package ee.schimke.composeai.daemon

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
 * Detects a draw modifier that obscures pixels produced by `drawContent()`.
 *
 * The probe is deliberately capability-based. It replays each `drawWithContent` lambda with a pair
 * of almost-identical opaque calibration fills standing in for the node's descendants. A
 * pass-through draw, a component that paints only behind its content, or an additive color overlay
 * leaves every output pixel sensitive to that one-step input difference. A clip, mask, sufficiently
 * strong fade, clear/opaque replacement, transform, or omitted `drawContent()` produces pixels that
 * are identical in both runs (or never calls the content at all). Those visibility effects cannot
 * be reconstructed from the layout/semantics tree and require a composited frame crop.
 *
 * This avoids component-name heuristics: Remote Compose may draw arbitrary chrome before
 * `drawContent()` and still keeps editable descendants. A gradient tint may paint over content but
 * does not erase the vector structure beneath it. A Picker—or any future component with the same
 * masking behavior—is detected from what its draw actually does.
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
    lateinit var darkScope: ProbedContentDrawScope
    val dark =
      render(width, height, density) {
        darkScope = ProbedContentDrawScope(this, Color.Black)
        darkScope.lambda()
      }
    if (darkScope.drawContentCalls == 0) return true

    lateinit var lightScope: ProbedContentDrawScope
    val light =
      render(width, height, density) {
        lightScope = ProbedContentDrawScope(this, Color(0xFF010101))
        lightScope.lambda()
      }
    if (lightScope.drawContentCalls == 0) return true

    return dark.indices.any { index -> dark[index] == light[index] }
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

  private class ProbedContentDrawScope(
    private val delegate: DrawScope,
    private val contentColor: Color,
  ) : ContentDrawScope, DrawScope by delegate {
    var drawContentCalls: Int = 0
      private set

    override fun drawContent() {
      drawContentCalls++
      delegate.drawRect(contentColor, size = size)
    }
  }

  private fun Any.isDrawWithContent(): Boolean {
    val inspectableName = (this as? InspectableValue)?.nameFallback
    return inspectableName?.contains("drawWithContent", ignoreCase = true) == true ||
      javaClass.simpleName.contains("DrawWithContent", ignoreCase = true)
  }
}
