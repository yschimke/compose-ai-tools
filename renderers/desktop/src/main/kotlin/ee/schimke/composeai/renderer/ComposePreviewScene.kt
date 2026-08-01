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
 * The [ImageComposeScene][androidx.compose.ui.ImageComposeScene] pixel dimensions for a preview
 * render. Fixed axes use the requested frame size verbatim; a wrapped axis widens to fit a min/max
 * size bound so the composable isn't clipped to the sandbox before the intrinsic-size crop runs
 * (the crop still trims the PNG back to the measured size). Only ever widens, never shrinks.
 *
 * Shared verbatim by both desktop render bodies so the scene they measure in is the same size.
 */
fun composePreviewSceneSize(
  widthPx: Int,
  heightPx: Int,
  wrapWidth: Boolean,
  wrapHeight: Boolean,
  sizeBounds: PreviewSizeBounds = PreviewSizeBounds(),
): IntSize {
  val w =
    if (wrapWidth) maxOf(widthPx, sizeBounds.minWidthPx ?: 0, sizeBounds.maxWidthPx ?: 0)
    else widthPx
  val h =
    if (wrapHeight) maxOf(heightPx, sizeBounds.minHeightPx ?: 0, sizeBounds.maxHeightPx ?: 0)
    else heightPx
  return IntSize(w, h)
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
 * [onMeasured] is invoked exactly once per measure pass, and only when at least one axis wraps.
 */
@Composable
fun ComposePreviewContentBox(
  wrapWidth: Boolean,
  wrapHeight: Boolean,
  backgroundColor: Color,
  sizeBounds: PreviewSizeBounds = PreviewSizeBounds(),
  onMeasured: (width: Int, height: Int) -> Unit,
  content: @Composable () -> Unit,
) {
  val boxModifier =
    if (wrapWidth || wrapHeight) {
      Modifier.layout { measurable, constraints ->
          // Size-mode bounds (Max / Min / Within) clamp the wrapped-axis measure: a max bound
          // lowers
          // the sandbox ceiling so the composable can't grow past it; a min bound raises the floor
          // so
          // it can't collapse below it. Both are clamped to the scene's available space so a bound
          // larger than the (already-enlarged) scene can't produce an impossible constraint. Absent
          // bounds keep the AS-parity wrap behaviour (min = 0, max = sandbox).
          val maxWBound =
            sizeBounds.maxWidthPx?.coerceAtMost(constraints.maxWidth) ?: constraints.maxWidth
          val maxHBound =
            sizeBounds.maxHeightPx?.coerceAtMost(constraints.maxHeight) ?: constraints.maxHeight
          val minWBound = (sizeBounds.minWidthPx ?: 0).coerceIn(0, maxWBound)
          val minHBound = (sizeBounds.minHeightPx ?: 0).coerceIn(0, maxHBound)
          val wrapped =
            Constraints(
              minWidth = if (wrapWidth) minWBound else constraints.maxWidth,
              maxWidth = if (wrapWidth) maxWBound else constraints.maxWidth,
              minHeight = if (wrapHeight) minHBound else constraints.maxHeight,
              maxHeight = if (wrapHeight) maxHBound else constraints.maxHeight,
            )
          val placeable = measurable.measure(wrapped)
          onMeasured(placeable.width, placeable.height)
          layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        .background(backgroundColor)
    } else {
      Modifier.fillMaxSize().background(backgroundColor)
    }
  Box(modifier = boxModifier, propagateMinConstraints = true) { content() }
}
