package ee.schimke.composeai.daemon

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ModifierInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorBounds
import ee.schimke.composeai.data.layoutinspector.LayoutInspectorDrawRaster
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Captures what a node's imperative draw painted by **re-invoking its draw lambda against an
 * offscreen bitmap** — the raster counterpart to [DrawCaptureExtractor], for draws that recorder
 * cannot turn into `<path>`s.
 *
 * [DrawCaptureExtractor] aborts the moment a lambda reaches for `drawContext.canvas`, a transform,
 * a clip, a shader or a bitmap, because none of those have a flat-paint SVG form. That is the
 * *whole* of what the Remote Compose embedded player draws with (issue #2937): every component it
 * interprets paints its background and shape through one `drawWithContent { executeOperations(…) }`
 * that drives the native canvas directly. Before this, such a node contributed nothing at all to
 * the export — not a path, not even a raster.
 *
 * The frame crop the hybrid export already had is not an answer for those nodes. It cuts
 * *composited* pixels out of the rendered frame, so cropping a container's box bakes in its
 * descendants; that is why the crop path is restricted to childless leaves, and every drawing RC
 * component is a container. Re-drawing in isolation removes the constraint: the bitmap holds this
 * node's own paint and nothing below it, so the export can lay it under still-editable children
 * without double-rendering them. It also needs no frame, so the vector-only export gains the same
 * chrome.
 *
 * Two rules keep the capture honest:
 * * **Behind-the-content only.** [IsolatedContentDrawScope.drawContent] redirects the rest of the
 *   lambda to a scratch canvas, so what comes back is strictly the pass drawn *behind* the node's
 *   children. Anything a `drawWithContent` paints *over* them (a scrim, a blend-mode tint) is left
 *   out rather than wrongly composited beneath — which is what the export did with it before
 *   anyway.
 * * **Nothing painted ⇒ nothing captured.** A fully transparent result yields null, so a
 *   pass-through `drawWithContent { drawContent() }` — the shape every tint/placeholder overlay
 *   lowers to — adds no `<image>` to the export.
 */
internal object DrawRasterCapture {

  /**
   * Refuse absurd regions rather than allocate them. Comfortably above a full phone screen
   * (1080×2400 ≈ 2.6M) and far above the component-sized draws this actually fires for, while
   * keeping the transient ARGB bitmap inside ~32 MB.
   */
  private const val MAX_PIXELS = 8_000_000

  /**
   * The isolated re-draw of [modifiers]' draw lambdas, or null when nothing drew, the bounds are
   * degenerate, or the backend refused the offscreen render.
   *
   * Every draw modifier on the chain is replayed into one bitmap covering their union, each at its
   * own bounds and its own size — a `Modifier.drawBehind{…}.padding(…).drawBehind{…}` chain paints
   * two differently-sized regions, and both belong in the node's raster.
   *
   * @param boundsOf the modifier's placed bounds in root-pixel space (the space the export lays the
   *   `<image>` out in); a modifier with no readable bounds is skipped.
   */
  fun capture(
    modifiers: List<ModifierInfo>,
    density: Float,
    boundsOf: (ModifierInfo) -> LayoutInspectorBounds?,
  ): LayoutInspectorDrawRaster? {
    val draws = modifiers.mapNotNull { info ->
      // A `Modifier.placeholder`'s own draw is not the node's art (issue #2646): loaded, it is a
      // pass-through over content the vector export already represents; loading, the export emits
      // the placeholder block as its own layer. Skipping it here matches the same exclusion the
      // export's `hasCustomDraw` makes, and saves an offscreen render that would come back empty.
      if (ModifierTokenResolver.isPlaceholderElement(info.modifier)) return@mapNotNull null
      val lambda = DrawCaptureExtractor.drawLambda(info.modifier) ?: return@mapNotNull null
      val bounds = boundsOf(info)?.takeIf { it.right > it.left && it.bottom > it.top }
      bounds?.let { it to lambda }
    }
    if (draws.isEmpty()) return null

    val left = draws.minOf { it.first.left }
    val top = draws.minOf { it.first.top }
    val right = draws.maxOf { it.first.right }
    val bottom = draws.maxOf { it.first.bottom }
    val width = right - left
    val height = bottom - top
    if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS) return null

    val pixels =
      runCatching { render(draws, left, top, width, height, density) }.getOrNull() ?: return null
    if (pixels.none { (it ushr 24) != 0 }) return null
    val png = runCatching { encodePng(pixels, width, height) }.getOrNull() ?: return null
    return LayoutInspectorDrawRaster(
      left = left,
      top = top,
      right = right,
      bottom = bottom,
      pngBase64 = Base64.getEncoder().encodeToString(png),
    )
  }

  /** Draws every lambda into one union-sized bitmap and reads it back as ARGB pixels. */
  private fun render(
    draws: List<Pair<LayoutInspectorBounds, DrawScope.() -> Unit>>,
    left: Int,
    top: Int,
    width: Int,
    height: Int,
    density: Float,
  ): IntArray {
    val target = ImageBitmap(width, height)
    val canvas = Canvas(target)
    // A scratch bitmap, not a null canvas: `drawContent()` cuts the capture off, but the lambda
    // keeps running (the RC draw list has no early exit) and its remaining ops must land somewhere
    // valid or the backend throws mid-draw and the whole capture is lost.
    val scratch = Canvas(ImageBitmap(1, 1))
    val scope = CanvasDrawScope()
    val densityScope = Density(density)
    for ((bounds, lambda) in draws) {
      val dx = (bounds.left - left).toFloat()
      val dy = (bounds.top - top).toFloat()
      val size =
        Size((bounds.right - bounds.left).toFloat(), (bounds.bottom - bounds.top).toFloat())
      canvas.save()
      canvas.translate(dx, dy)
      scope.draw(densityScope, LayoutDirection.Ltr, canvas, size) {
        IsolatedContentDrawScope(this, scratch).lambda()
      }
      canvas.restore()
    }
    val pixels = IntArray(width * height)
    target.readPixels(pixels, 0, 0, width, height, 0, width)
    return pixels
  }

  private fun encodePng(pixels: IntArray, width: Int, height: Int): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, width, height, pixels, 0, width)
    val out = ByteArrayOutputStream()
    ImageIO.write(image, "png", out)
    return out.toByteArray()
  }

  /**
   * A [ContentDrawScope] over a live [DrawScope] whose `drawContent()` draws nothing and diverts
   * everything after it to [scratch] — so a captured lambda yields exactly the pass drawn behind
   * the node's children, and its over-content pass is discarded instead of being composited beneath
   * them.
   */
  private class IsolatedContentDrawScope(
    private val delegate: DrawScope,
    private val scratch: Canvas,
  ) : ContentDrawScope, DrawScope by delegate {
    override fun drawContent() {
      delegate.drawContext.canvas = scratch
    }
  }
}
