package ee.schimke.composeai.preview.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.round
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Composable that wraps preview content in a launcher-widget-shaped grid-cell container.
 *
 * The container resolves its width and height by multiplying [cellSize] by the current cell count
 * on each axis (plus [cellSpacing] gaps between cells), so a `2×3` cell widget at a `cellSize =
 * 72.dp` / `cellSpacing = 8.dp` grid measures `2*72 + 1*8 = 152.dp` wide by `3*72 + 2*8 = 232.dp`
 * tall — the same arithmetic Android's launcher uses when it lays a widget into its `N×M` cell
 * grid.
 *
 * `cells` is the user-facing target. It is coerced into the `minCells`..`maxCells` range before
 * reaching the layout pass — so a parent that's still hooked up to a slider going past the
 * configured max simply pegs at `maxCells` rather than overdrawing the container, the same way a
 * real launcher's resize handle stops at the widget's `minResizeWidth` / `minResizeHeight`.
 *
 * **Animation between sizes.** When [cells] changes (e.g. a live-preview slider or a stacked
 * `@Preview` value), the container walks through every whole-cell stop between the previous and new
 * sizes — see [launcherWidgetStops] — animating the displayed dp size between consecutive stops
 * with a [stepDurationMillis] tween, and holding for [holdMillis] at each intermediate stop so the
 * cell snap reads visually. The final stop is not held: the animation ends at the target.
 *
 * The step path depends on [resizeOrder]. Real Android launcher widgets have edge resize handles,
 * not corner handles — the user grabs one edge and drags it, so width and height never change
 * simultaneously in a single gesture. [LauncherResizeOrder.WidthFirst] (the default) and
 * [LauncherResizeOrder.HeightFirst] mirror that two-gesture path; pick
 * [LauncherResizeOrder.Diagonal] when you want both axes to change in lock-step instead.
 *
 * Example: with `cells` going from `1×1` to `4×2` under [LauncherResizeOrder.WidthFirst], the
 * container animates through `1×1 → 2×1 → 3×1 → 4×1 → 4×2`, pausing briefly at each intermediate
 * stop. Under [LauncherResizeOrder.Diagonal] the same resize collapses to `1×1 → 2×1 → 3×2 → 4×2`.
 *
 * @param cells target whole-cell size; clamped into the `minCells`..`maxCells` range.
 * @param cellSize one cell's edge length in dp. Defaults to `72.dp`, roughly the cell size of a
 *   Pixel launcher's `5×5` grid on a 411dp screen.
 * @param cellSpacing dp gap between adjacent cells. Defaults to `8.dp`.
 * @param minCells inclusive lower bound on the cell count (per axis).
 * @param maxCells inclusive upper bound on the cell count (per axis).
 * @param resizeOrder how the per-axis steps are ordered while the container walks from its previous
 *   cell count to [cells]. Defaults to [LauncherResizeOrder.WidthFirst] because real launcher
 *   resize handles are non-diagonal.
 * @param stepDurationMillis time the container takes to animate between two adjacent whole-cell
 *   stops. Set to `0` to disable the smooth segment and snap directly.
 * @param holdMillis dwell time at each intermediate whole-cell stop; ignored at the final stop. Set
 *   to `0` to remove the pause and animate through stops continuously.
 * @param modifier outer modifier applied to the container; do NOT use this to size the container —
 *   the container drives its own size from [cells]. Use it for backgrounds, borders, padding
 *   outside the cell box, etc.
 * @param content slot for the widget body. Receives a [BoxScope] so the body can position itself
 *   relative to the cell box (e.g. `Modifier.align(Alignment.Center)`).
 */
@Composable
fun LauncherWidgetContainer(
  cells: LauncherWidgetSize,
  modifier: Modifier = Modifier,
  cellSize: Dp = 72.dp,
  cellSpacing: Dp = 8.dp,
  minCells: LauncherWidgetSize = LauncherWidgetSize(1, 1),
  maxCells: LauncherWidgetSize = LauncherWidgetSize(5, 5),
  resizeOrder: LauncherResizeOrder = LauncherResizeOrder.WidthFirst,
  stepDurationMillis: Int = 220,
  holdMillis: Int = 140,
  content: @Composable BoxScope.() -> Unit,
) {
  val target = remember(cells, minCells, maxCells) { cells.coerceIn(minCells, maxCells) }
  val widthCells = remember { Animatable(target.width.toFloat()) }
  val heightCells = remember { Animatable(target.height.toFloat()) }

  LaunchedEffect(target, resizeOrder, stepDurationMillis, holdMillis) {
    val from =
      LauncherWidgetSize(
        widthCells.value.roundToIntAtLeast(minCells.width),
        heightCells.value.roundToIntAtLeast(minCells.height),
      )
    val stops = launcherWidgetStops(from, target, resizeOrder)
    // stops[0] is the current position; skip it.
    for (i in 1 until stops.size) {
      val stop = stops[i]
      if (stepDurationMillis <= 0) {
        widthCells.snapTo(stop.width.toFloat())
        heightCells.snapTo(stop.height.toFloat())
      } else {
        coroutineScope {
          launch { widthCells.animateTo(stop.width.toFloat(), tween(stepDurationMillis)) }
          launch { heightCells.animateTo(stop.height.toFloat(), tween(stepDurationMillis)) }
        }
      }
      // Pause at every intermediate stop, but not at the final target — the animation ends
      // there and the next change to `cells` will pick up from this exact position.
      if (i < stops.lastIndex && holdMillis > 0) {
        delay(holdMillis.toLong())
      }
    }
  }

  val w = widthCells.value
  val h = heightCells.value
  val widthDp = cellSize * w + cellSpacing * (w - 1f).coerceAtLeast(0f)
  val heightDp = cellSize * h + cellSpacing * (h - 1f).coerceAtLeast(0f)

  Box(modifier = modifier.size(widthDp, heightDp), content = content)
}

// `Float.roundToInt()` would round 0.49 → 0, which would let a stale animator value briefly
// undershoot the configured minimum during a resize. Floor at the min so the starting cell of
// the next walk is always a valid whole-cell within bounds.
private fun Float.roundToIntAtLeast(min: Int): Int = max(min, round(this).toInt())
