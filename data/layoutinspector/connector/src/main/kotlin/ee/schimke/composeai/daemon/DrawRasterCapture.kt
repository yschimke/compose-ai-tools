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
    fontScale: Float = 1f,
    boundsOf: (ModifierInfo) -> LayoutInspectorBounds?,
  ): LayoutInspectorDrawRaster? {
    val draws = modifiers.mapNotNull { info ->
      // A `Modifier.placeholder`'s own draw is not the node's art (issue #2646): loaded, it is a
      // pass-through over content the vector export already represents; loading, the export emits
      // the placeholder block as its own layer. Skipping it here matches the same exclusion the
      // export's `hasCustomDraw` makes, and saves an offscreen render that would come back empty.
      if (ModifierTokenResolver.isPlaceholderElement(info.modifier)) return@mapNotNull null
      if (!DrawCaptureExtractor.isDrawModifier(info.modifier)) return@mapNotNull null
      val bounds =
        boundsOf(info)?.takeIf { it.right > it.left && it.bottom > it.top }
          ?: return@mapNotNull null
      // Bounds first, lambda second: a `drawWithCache` builds its draw block *for a size*, so the
      // modifier's own local size has to be known before its lambda can be asked for.
      val (localWidth, localHeight) = localSizeOf(info, bounds)
      val cacheParams =
        DrawCaptureExtractor.CacheDrawParams(
          Size(localWidth.toFloat(), localHeight.toFloat()),
          density,
          fontScale,
        )
      val lambda =
        DrawCaptureExtractor.drawLambda(info.modifier, cacheParams) ?: return@mapNotNull null
      Draw(bounds, localWidth, localHeight, lambda)
    }
    if (draws.isEmpty()) return null

    val left = draws.minOf { it.bounds.left }
    val top = draws.minOf { it.bounds.top }
    val right = draws.maxOf { it.bounds.right }
    val bottom = draws.maxOf { it.bounds.bottom }
    if (right <= left || bottom <= top) return null

    // The lambda draws in the node's **local** coordinates, but the bounds above are root-space —
    // already shrunk by any `graphicsLayer` scale between here and the root (a Wear
    // `TransformingLazyColumn` item near the curved edge, issue #2615). Rendering at the root size
    // would shrink size-relative geometry while leaving absolute lengths (`10.dp.toPx()`, a native
    // corner radius) at full size, so the two would disagree. Render at local resolution instead,
    // and let the `<image>` — emitted at the root bounds — scale the whole bitmap uniformly, so
    // every part of the draw scales by exactly the factor the render applied. Identity scale (the
    // overwhelmingly common case) makes this a no-op.
    val scaleX = draws.first().scaleX
    val scaleY = draws.first().scaleY
    val width = Math.round((right - left) / scaleX)
    val height = Math.round((bottom - top) / scaleY)
    if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS) return null

    val pixels =
      runCatching { render(draws, left, top, scaleX, scaleY, width, height, density, fontScale) }
        .getOrNull() ?: return null
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

  /**
   * One draw modifier to replay: where it lands ([bounds], root px) and how big it draws
   * ([localWidth] × [localHeight], its own px). The two differ by exactly the `graphicsLayer` scale
   * inherited from the node's ancestors.
   */
  private class Draw(
    val bounds: LayoutInspectorBounds,
    val localWidth: Int,
    val localHeight: Int,
    val lambda: DrawScope.() -> Unit,
  ) {
    val scaleX: Float = (bounds.right - bounds.left).toFloat() / localWidth
    val scaleY: Float = (bounds.bottom - bounds.top).toFloat() / localHeight
  }

  /**
   * The modifier's size in its **own** coordinate space. Falls back to the root-space [bounds] when
   * the coordinates can't be read, which yields scale 1 — the same answer an unscaled node gives,
   * and the conservative one for a scaled node whose transform we couldn't measure.
   */
  private fun localSizeOf(info: ModifierInfo, bounds: LayoutInspectorBounds): Pair<Int, Int> {
    val size = runCatching { info.coordinates.size }.getOrNull()
    val w = size?.width?.takeIf { it > 0 }
    val h = size?.height?.takeIf { it > 0 }
    if (w != null && h != null) return w to h
    return (bounds.right - bounds.left) to (bounds.bottom - bounds.top)
  }

  /** Draws every lambda into one union-sized bitmap and reads it back as ARGB pixels. */
  private fun render(
    draws: List<Draw>,
    left: Int,
    top: Int,
    scaleX: Float,
    scaleY: Float,
    width: Int,
    height: Int,
    density: Float,
    fontScale: Float,
  ): IntArray {
    val target = ImageBitmap(width, height)
    val canvas = Canvas(target)
    // A scratch bitmap, not a null canvas: `drawContent()` cuts the capture off, but the lambda
    // keeps running (the RC draw list has no early exit) and its remaining ops must land somewhere
    // valid or the backend throws mid-draw and the whole capture is lost.
    val scratch = Canvas(ImageBitmap(1, 1))
    val scope = CanvasDrawScope()
    val densityScope = Density(density, fontScale)
    for (draw in draws) {
      // Offsets are root-space distances, so they divide back into the bitmap's local space too.
      val dx = (draw.bounds.left - left) / scaleX
      val dy = (draw.bounds.top - top) / scaleY
      val size = Size(draw.localWidth.toFloat(), draw.localHeight.toFloat())
      canvas.save()
      canvas.translate(dx, dy)
      scope.draw(densityScope, LayoutDirection.Ltr, canvas, size) {
        val lambda = draw.lambda
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
