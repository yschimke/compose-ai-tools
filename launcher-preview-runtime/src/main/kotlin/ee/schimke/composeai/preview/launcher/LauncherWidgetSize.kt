package ee.schimke.composeai.preview.launcher

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Whole-cell size passed to [Modifier.launcherWidgetCells], measured in launcher-grid cells.
 *
 * Both [width] and [height] are expressed as cell counts (the same units Android's launcher /
 * AppWidget host uses when it asks "how many cells wide and tall is this widget?"). The value is
 * always coerced into the surrounding modifier's `minCells` / `maxCells` bounds before it ever
 * reaches the layout pass — see [LauncherWidgetSize.coerceIn].
 */
data class LauncherWidgetSize(val width: Int, val height: Int) {
  init {
    // Negative cell counts are nonsensical (no launcher widget is `-1` cells wide). Zero is
    // allowed so a caller can express "below min" on either axis — `coerceIn` then lifts that
    // up to the surrounding container's `minCells`.
    require(width >= 0) { "LauncherWidgetSize.width must be >= 0, was $width" }
    require(height >= 0) { "LauncherWidgetSize.height must be >= 0, was $height" }
  }

  /**
   * Clamps each axis into the inclusive range `min`..`max`. Mirrors [Int.coerceIn] per axis; exists
   * so call sites don't have to spell out the two-axis clamp every time the user-facing target
   * changes.
   */
  fun coerceIn(min: LauncherWidgetSize, max: LauncherWidgetSize): LauncherWidgetSize {
    require(min.width <= max.width && min.height <= max.height) {
      "LauncherWidgetSize bounds are inverted: min=$min max=$max"
    }
    return LauncherWidgetSize(
      width.coerceIn(min.width, max.width),
      height.coerceIn(min.height, max.height),
    )
  }
}

/**
 * How [Modifier.launcherWidgetCells] orders its per-axis steps when animating between sizes.
 *
 * Real launcher widgets have edge handles, not corner handles — the user grabs one edge and drags
 * it, so width and height never change simultaneously in a single gesture. [WidthFirst] and
 * [HeightFirst] mirror that: the animation runs the width axis to the target while height holds
 * steady (or vice versa), then runs the other axis. [Diagonal] is the relaxed mode that advances
 * both axes in lock-step — useful when you want the resize to "read" as a single gesture rather
 * than two sequential drags, e.g. in marketing material or design mockups.
 */
enum class LauncherResizeOrder {
  /**
   * Both axes change in lock-step along the diagonal. A `1×1 → 4×2` resize visits four cells (`1×1,
   * 2×1, 3×2, 4×2`) — `max(|dw|, |dh|) + 1` stops total.
   */
  Diagonal,
  /**
   * Width animates to the target first, then height. A `1×1 → 4×2` resize visits five cells (`1×1,
   * 2×1, 3×1, 4×1, 4×2`).
   */
  WidthFirst,
  /**
   * Height animates to the target first, then width. A `1×1 → 4×2` resize visits five cells (`1×1,
   * 1×2, 2×2, 3×2, 4×2`).
   */
  HeightFirst,
}

/**
 * Whole-cell stops the container walks through when animating from [from] to [to] under [order].
 *
 * For [LauncherResizeOrder.Diagonal] the number of stops is `max(|dw|, |dh|) + 1` — the diagonal
 * distance, not the L1 sum, so a `1×1 → 4×2` resize visits four cells (`1×1, 2×1, 3×2, 4×2`).
 * Per-axis cell value at step `i` is rounded proportionally:
 *
 * widthCells(i) = from.width + round((to.width - from.width) * i / n) heightCells(i) =
 * from.height + round((to.height - from.height) * i / n)
 *
 * For [LauncherResizeOrder.WidthFirst] / [LauncherResizeOrder.HeightFirst] the stops walk one axis
 * to completion before touching the other. Number of stops is `|dw| + |dh| + 1` (L1 distance) — one
 * cell per launcher-handle drag of either axis.
 *
 * Returned list always includes both endpoints. When `from == to` it collapses to a single stop so
 * the caller's loop terminates cleanly without special-casing zero-delta resizes.
 */
internal fun launcherWidgetStops(
  from: LauncherWidgetSize,
  to: LauncherWidgetSize,
  order: LauncherResizeOrder = LauncherResizeOrder.WidthFirst,
): List<LauncherWidgetSize> {
  if (from == to) return listOf(from)
  return when (order) {
    LauncherResizeOrder.Diagonal -> diagonalStops(from, to)
    LauncherResizeOrder.WidthFirst -> axisFirstStops(from, to, widthFirst = true)
    LauncherResizeOrder.HeightFirst -> axisFirstStops(from, to, widthFirst = false)
  }
}

private fun diagonalStops(
  from: LauncherWidgetSize,
  to: LauncherWidgetSize,
): List<LauncherWidgetSize> {
  val dw = to.width - from.width
  val dh = to.height - from.height
  val n = maxOf(abs(dw), abs(dh))
  return (0..n).map { i ->
    LauncherWidgetSize(
      from.width + (dw.toDouble() * i / n).roundToInt(),
      from.height + (dh.toDouble() * i / n).roundToInt(),
    )
  }
}

private fun axisFirstStops(
  from: LauncherWidgetSize,
  to: LauncherWidgetSize,
  widthFirst: Boolean,
): List<LauncherWidgetSize> {
  val stops = mutableListOf(from)
  if (widthFirst) {
    walkAxis(from.width, to.width) { w -> stops.add(LauncherWidgetSize(w, from.height)) }
    walkAxis(from.height, to.height) { h -> stops.add(LauncherWidgetSize(to.width, h)) }
  } else {
    walkAxis(from.height, to.height) { h -> stops.add(LauncherWidgetSize(from.width, h)) }
    walkAxis(from.width, to.width) { w -> stops.add(LauncherWidgetSize(w, to.height)) }
  }
  return stops
}

private inline fun walkAxis(from: Int, to: Int, emit: (Int) -> Unit) {
  if (from == to) return
  val step = if (to > from) 1 else -1
  var v = from
  while (v != to) {
    v += step
    emit(v)
  }
}
