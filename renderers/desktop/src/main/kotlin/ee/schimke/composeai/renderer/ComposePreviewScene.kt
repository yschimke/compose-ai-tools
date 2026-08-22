package ee.schimke.composeai.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt

/**
 * Content-size bounds for the wrapped axes — the Max / Min / Within size modes. Only consulted on
 * an axis that wraps; a `null` on either end keeps the Android-Studio-parity wrap (min = 0, max =
 * sandbox). A max bound lowers the wrap ceiling, a min bound raises the floor.
 *
 * The two desktop render bodies (the daemon [ee.schimke.composeai.daemon] `RenderEngine` and the
 * one-shot [DesktopRendererMain] fork) both feed these into [composePreviewSceneSize] and
 * [ComposePreviewContentBox], so a batch bundle re-render and a `compose-preview serve` render size
 * the same preview identically.
 */
data class PreviewSizeBounds(
  val minWidthPx: Int? = null,
  val minHeightPx: Int? = null,
  val maxWidthPx: Int? = null,
  val maxHeightPx: Int? = null,
)

/**
 * Per-edge capture gutter in **pixels** — the transparent margin the capture bounds are extended by
 * so a shadow / focus ring / overhanging badge drawn outside the composable's own bounds isn't
 * cropped at the image edge (`@CaptureGutter`, m3-catalog#179).
 *
 * The gutter lives here, in the capture, rather than as padding inside the preview body, because
 * those two are only equivalent from the component's side. Padding measures the component in a
 * smaller box; a gutter measures it in exactly the box it had and grows the canvas around it. That
 * is what lets a consumer treat "canvas minus gutter" as the component and lay a gutter-carrying
 * sticker out flush with its gutter-less siblings.
 *
 * Pixels rather than dp because everything else the scene math takes is pixels; the dp→px
 * conversion happens once at each renderer's entry point, against that render's own density.
 */
data class PreviewCaptureGutter(
  val startPx: Int = 0,
  val topPx: Int = 0,
  val endPx: Int = 0,
  val bottomPx: Int = 0,
) {
  /** Total pixels the gutter adds across the horizontal axis. */
  val horizontalPx: Int
    get() = startPx + endPx

  /** Total pixels the gutter adds across the vertical axis. */
  val verticalPx: Int
    get() = topPx + bottomPx

  /** True when no edge carries a gutter — the renderer then keeps its pre-gutter path verbatim. */
  fun isEmpty(): Boolean = horizontalPx == 0 && verticalPx == 0

  /** The left-edge inset under [layoutDirection]; `start` is leading, so RTL swaps the pair. */
  fun leftPx(layoutDirection: LayoutDirection): Int =
    if (layoutDirection == LayoutDirection.Rtl) endPx else startPx

  companion object {
    val None = PreviewCaptureGutter()

    /**
     * A gutter declared in dp, resolved at [density]. Each edge rounds independently — the same
     * rule Compose's own dp→px conversion uses — so a 4dp gutter at 2.625× is 11px a side rather
     * than one side absorbing the rounding.
     */
    fun ofDp(startDp: Int, topDp: Int, endDp: Int, bottomDp: Int, density: Float) =
      PreviewCaptureGutter(
        startPx = (startDp * density).roundToInt().coerceAtLeast(0),
        topPx = (topDp * density).roundToInt().coerceAtLeast(0),
        endPx = (endDp * density).roundToInt().coerceAtLeast(0),
        bottomPx = (bottomDp * density).roundToInt().coerceAtLeast(0),
      )
  }
}

/**
 * The [ImageComposeScene][androidx.compose.ui.ImageComposeScene] pixel dimensions for a preview
 * render. Fixed axes use the requested frame size verbatim; a wrapped axis widens to fit a min/max
 * size bound so the composable isn't clipped to the sandbox before the intrinsic-size crop runs
 * (the crop still trims the PNG back to the measured size). Only ever widens, never shrinks.
 *
 * A [gutter] is added on top, on BOTH kinds of axis. That is what keeps its promise that the
 * component measures exactly what it measured without one: [ComposePreviewContentBox] hands the
 * child the scene minus the gutter, so a fixed axis still measures the declared frame and a wrapped
 * axis still wraps against the same sandbox — the extra pixels are canvas, never constraint.
 *
 * Shared verbatim by both desktop render bodies so the scene they measure in is the same size.
 */
fun composePreviewSceneSize(
  widthPx: Int,
  heightPx: Int,
  wrapWidth: Boolean,
  wrapHeight: Boolean,
  sizeBounds: PreviewSizeBounds = PreviewSizeBounds(),
  gutter: PreviewCaptureGutter = PreviewCaptureGutter.None,
): IntSize {
  val w =
    if (wrapWidth) maxOf(widthPx, sizeBounds.minWidthPx ?: 0, sizeBounds.maxWidthPx ?: 0)
    else widthPx
  val h =
    if (wrapHeight) maxOf(heightPx, sizeBounds.minHeightPx ?: 0, sizeBounds.maxHeightPx ?: 0)
    else heightPx
  return IntSize(w + gutter.horizontalPx, h + gutter.verticalPx)
}

/**
 * The Android-Studio-parity wrap-measure box that both desktop render bodies place the preview
 * content in.
 *
 * On a wrapped axis it measures [content] with a relaxed (min = 0, clamped by [sizeBounds]) bound
 * against the sandbox max and sizes the box to the child's intrinsic size — so the captured tree
 * (and the figma-svg / wireframe / semantics derived from it) reflects the preview's natural size
 * instead of a fixed frame that clips or reflows wide content. The measured pixel size is reported
 * through [onMeasured] so the caller can crop the PNG to it. `.background` paints on that intrinsic
 * box so a sticker's backdrop is content-sized, not sandbox-sized. Fixed axes keep the sandbox
 * constraint so `fillMax*` / `LazyColumn` still have a finite viewport.
 *
 * `propagateMinConstraints = true` pushes the wrapper's min bounds down onto the composable itself:
 * fixed axes become Studio-tight, while wrapped axes remain loose unless Min / Within size modes
 * add a floor.
 *
 * A [gutter] insets the child inside this box: the child is measured against the scene **minus**
 * the gutter (so both kinds of axis hand it exactly the constraint it had without one), placed at
 * the gutter's leading/top offset, and the box reports `child + gutter` as its size. The gutter is
 * therefore in the crop and in `.background`, and out of the child's measure — which is the whole
 * difference between this and padding the preview body.
 *
 * [onMeasured] is invoked exactly once per measure pass, when at least one axis wraps OR a gutter
 * is applied. A fully-fixed, gutter-less preview keeps the plain `fillMaxSize` box and reports
 * nothing, exactly as before.
 */
@Composable
fun ComposePreviewContentBox(
  wrapWidth: Boolean,
  wrapHeight: Boolean,
  backgroundColor: Color,
  sizeBounds: PreviewSizeBounds = PreviewSizeBounds(),
  gutter: PreviewCaptureGutter = PreviewCaptureGutter.None,
  onMeasured: (width: Int, height: Int) -> Unit,
  content: @Composable () -> Unit,
) {
  val boxModifier =
    if (wrapWidth || wrapHeight || !gutter.isEmpty()) {
      // `.background` sits OUTSIDE the layout modifier so it paints the size that modifier reports
      // — the measured content PLUS the gutter. Inside it, it would take the child's size and the
      // gutter would come out transparent, which on a `showBackground = true` capture is a
      // transparent border around a white sticker. With no gutter the two orders are identical
      // (the box is the child), so nothing moves for a preview that doesn't declare one.
      Modifier.background(backgroundColor).layout { measurable, constraints ->
        // The gutter is canvas, not constraint: take it off the top before anything else looks at
        // the available space, so every bound below — and the child's own measure — sees the
        // scene the preview would have had without one.
        val availW = (constraints.maxWidth - gutter.horizontalPx).coerceAtLeast(0)
        val availH = (constraints.maxHeight - gutter.verticalPx).coerceAtLeast(0)
        // Size-mode bounds (Max / Min / Within) clamp the wrapped-axis measure: a max bound
        // lowers
        // the sandbox ceiling so the composable can't grow past it; a min bound raises the floor
        // so
        // it can't collapse below it. Both are clamped to the scene's available space so a bound
        // larger than the (already-enlarged) scene can't produce an impossible constraint. Absent
        // bounds keep the AS-parity wrap behaviour (min = 0, max = sandbox).
        val maxWBound = sizeBounds.maxWidthPx?.coerceAtMost(availW) ?: availW
        val maxHBound = sizeBounds.maxHeightPx?.coerceAtMost(availH) ?: availH
        val minWBound = (sizeBounds.minWidthPx ?: 0).coerceIn(0, maxWBound)
        val minHBound = (sizeBounds.minHeightPx ?: 0).coerceIn(0, maxHBound)
        val wrapped =
          Constraints(
            minWidth = if (wrapWidth) minWBound else availW,
            maxWidth = if (wrapWidth) maxWBound else availW,
            minHeight = if (wrapHeight) minHBound else availH,
            maxHeight = if (wrapHeight) maxHBound else availH,
          )
        val placeable = measurable.measure(wrapped)
        // Clamped to the scene: a child that measured larger than the space we offered (a
        // min-constraint floor it can't honour) must not push the box past the surface we can
        // actually capture.
        val boxWidth = (placeable.width + gutter.horizontalPx).coerceAtMost(constraints.maxWidth)
        val boxHeight = (placeable.height + gutter.verticalPx).coerceAtMost(constraints.maxHeight)
        onMeasured(boxWidth, boxHeight)
        layout(boxWidth, boxHeight) {
          placeable.place(gutter.leftPx(layoutDirection), gutter.topPx)
        }
      }
    } else {
      Modifier.fillMaxSize().background(backgroundColor)
    }
  Box(modifier = boxModifier, propagateMinConstraints = true) { content() }
}
