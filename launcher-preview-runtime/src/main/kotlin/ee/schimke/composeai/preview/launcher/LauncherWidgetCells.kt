package ee.schimke.composeai.preview.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
 * Sizes a composable to a launcher-widget-shaped cell grid.
 *
 * The modifier resolves its width and height by multiplying [cellSize] by the current cell count on
 * each axis (plus [cellSpacing] gaps between cells), so a `2×3` cell widget at `cellSize = 72.dp` /
 * `cellSpacing = 8.dp` measures `2*72 + 1*8 = 152.dp` wide by `3*72 + 2*8 = 232.dp` tall — the same
 * arithmetic Android's launcher uses when it lays a widget into its `N×M` cell grid. The chain ends
 * with [Modifier.size]; any later sizing modifier in the chain wins, which is the standard Compose
 * modifier-order semantics.
 *
 * [cells] is the user-facing target. It is coerced into the `minCells`..`maxCells` range before
 * reaching the layout pass — so a parent slider going past the configured max simply pegs at
 * `maxCells` rather than overdrawing the modifier, the same way a real launcher's resize handle
 * stops at the widget's `minResizeWidth` / `minResizeHeight`.
 *
 * **Animation between sizes.** When [cells] changes (e.g. a live-preview slider or a stacked
 * `@Preview` value), the modifier walks through every whole-cell stop between the previous and new
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
 * modifier animates through `1×1 → 2×1 → 3×1 → 4×1 → 4×2`, pausing briefly at each intermediate
 * stop. Under [LauncherResizeOrder.Diagonal] the same resize collapses to `1×1 → 2×1 → 3×2 → 4×2`.
 *
 * Usage:
 * ```
 * Box(
 *   modifier = Modifier
 *     .launcherWidgetCells(LauncherWidgetSize(2, 3))
 *     .background(MaterialTheme.colorScheme.primaryContainer)
 * ) { … }
 * ```
 *
 * @param cells target whole-cell size; clamped into the `minCells`..`maxCells` range.
 * @param cellSize one cell's edge length in dp. Defaults to `72.dp`, roughly the cell size of a
 *   Pixel launcher's `5×5` grid on a 411dp screen.
 * @param cellSpacing dp gap between adjacent cells. Defaults to `8.dp`.
 * @param minCells inclusive lower bound on the cell count (per axis).
 * @param maxCells inclusive upper bound on the cell count (per axis).
 * @param resizeOrder how the per-axis steps are ordered while the modifier walks from its previous
 *   cell count to [cells]. Defaults to [LauncherResizeOrder.WidthFirst] because real launcher
 *   resize handles are non-diagonal.
 * @param stepDurationMillis time the modifier takes to animate between two adjacent whole-cell
 *   stops. Set to `0` to disable the smooth segment and snap directly.
 * @param holdMillis dwell time at each intermediate whole-cell stop; ignored at the final stop. Set
 *   to `0` to remove the pause and animate through stops continuously.
 */
@Composable
fun Modifier.launcherWidgetCells(
  cells: LauncherWidgetSize,
  cellSize: Dp = 72.dp,
  cellSpacing: Dp = 8.dp,
  minCells: LauncherWidgetSize = LauncherWidgetSize(1, 1),
  maxCells: LauncherWidgetSize = LauncherWidgetSize(5, 5),
  resizeOrder: LauncherResizeOrder = LauncherResizeOrder.WidthFirst,
  stepDurationMillis: Int = 220,
  holdMillis: Int = 140,
): Modifier {
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
  return this.size(widthDp, heightDp)
}

// `Float.roundToInt()` would round 0.49 → 0, which would let a stale animator value briefly
// undershoot the configured minimum during a resize. Floor at the min so the starting cell of
// the next walk is always a valid whole-cell within bounds.
private fun Float.roundToIntAtLeast(min: Int): Int = max(min, round(this).toInt())
