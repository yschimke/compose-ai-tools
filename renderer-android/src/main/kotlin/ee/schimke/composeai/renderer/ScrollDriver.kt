package ee.schimke.composeai.renderer

import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.AndroidComposeTestRule

/**
 * Drives the first scrollable composable matching [axis] to the end of its
 * content, by repeatedly calling `SemanticsActions.ScrollBy` with the
 * remaining delta and advancing the paused [AndroidComposeTestRule.mainClock].
 *
 * Loops because:
 *  - `LazyList` / `LazyColumn` reports `maxValue` progressively as items
 *    materialize — the first `ScrollBy` call doesn't know the final extent.
 *  - `Modifier.verticalScroll` with a `ScrollState` reports the total content
 *    extent up front, but its `animateScrollBy` under the hood still takes
 *    multiple frames of virtual time to settle on the target.
 *
 * Returns when the remaining delta is ≈ 0 or when [maxScrollPx] (if > 0) is
 * exhausted. Safe no-op if no scrollable is found — caller captures whatever
 * the composition has drawn.
 */
@Suppress("LongParameterList")
internal fun driveScrollToEnd(
    rule: AndroidComposeTestRule<*, *>,
    axis: ScrollAxis,
    maxScrollPx: Int,
    maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    advanceMsPerStep: Long = DEFAULT_ADVANCE_MS_PER_STEP,
): ScrollDriveResult {
    val axisKey = when (axis) {
        ScrollAxis.VERTICAL -> SemanticsProperties.VerticalScrollAxisRange
        ScrollAxis.HORIZONTAL -> SemanticsProperties.HorizontalScrollAxisRange
    }
    val scrollables = rule.onAllNodes(SemanticsMatcher.keyIsDefined(axisKey)).fetchSemanticsNodes()
    if (scrollables.isEmpty()) return ScrollDriveResult.NoScrollable

    // Match the first scrollable — same node across iterations via the
    // SemanticsNodeInteraction, so config reads see up-to-date maxValue.
    val interaction = rule.onAllNodes(SemanticsMatcher.keyIsDefined(axisKey))[0]

    val cap = if (maxScrollPx > 0) maxScrollPx.toFloat() else Float.POSITIVE_INFINITY
    var scrolledPx = 0f

    repeat(maxIterations) {
        val node = interaction.fetchSemanticsNode()
        val range: ScrollAxisRange = node.config.getOrNull(axisKey)
            ?: return ScrollDriveResult.Completed(scrolledPx)
        val scrollByAction = node.config.getOrNull(SemanticsActions.ScrollBy)?.action
            ?: return ScrollDriveResult.Completed(scrolledPx)

        val remaining = (range.maxValue() - range.value()).coerceAtLeast(0f)
        if (remaining <= SETTLED_EPSILON_PX) return ScrollDriveResult.Completed(scrolledPx)

        val headroom = (cap - scrolledPx).coerceAtLeast(0f)
        if (headroom <= SETTLED_EPSILON_PX) return ScrollDriveResult.CapReached(scrolledPx)

        val step = minOf(remaining, headroom)
        val (dx, dy) = when (axis) {
            ScrollAxis.VERTICAL -> 0f to step
            ScrollAxis.HORIZONTAL -> step to 0f
        }
        scrollByAction.invoke(dx, dy)
        scrolledPx += step

        // ScrollBy dispatches animateScrollBy — the scroll doesn't land until
        // virtual time advances enough for the animation to complete.
        rule.mainClock.advanceTimeBy(advanceMsPerStep)
    }
    return ScrollDriveResult.IterationCapReached(scrolledPx)
}

/**
 * Drives a scrollable by exactly [stepPx] per iteration and invokes
 * [onSlice] with the cumulative scrolled pixel count once at offset 0
 * (before the first scroll) and again after each successful step. Used
 * by the `LONG` scroll-capture path to take one screenshot per viewport-
 * height of content, which the caller then stitches into one tall PNG.
 *
 * Differs from [driveScrollToEnd] in that each step is a fixed size, not
 * "all remaining" — the caller wants a slice per viewport, not a single
 * jump to the bottom.
 */
@Suppress("LongParameterList")
internal fun driveScrollByViewport(
    rule: AndroidComposeTestRule<*, *>,
    axis: ScrollAxis,
    stepPx: Float,
    maxScrollPx: Int,
    maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    advanceMsPerStep: Long = DEFAULT_ADVANCE_MS_PER_STEP,
    onSlice: (scrolledPx: Float) -> Unit,
): ScrollDriveResult {
    require(stepPx > 0f) { "stepPx must be positive, got $stepPx" }

    val axisKey = when (axis) {
        ScrollAxis.VERTICAL -> SemanticsProperties.VerticalScrollAxisRange
        ScrollAxis.HORIZONTAL -> SemanticsProperties.HorizontalScrollAxisRange
    }
    val scrollables = rule.onAllNodes(SemanticsMatcher.keyIsDefined(axisKey)).fetchSemanticsNodes()
    if (scrollables.isEmpty()) return ScrollDriveResult.NoScrollable

    val interaction = rule.onAllNodes(SemanticsMatcher.keyIsDefined(axisKey))[0]
    val cap = if (maxScrollPx > 0) maxScrollPx.toFloat() else Float.POSITIVE_INFINITY

    // First slice captures the initial (unscrolled) frame.
    onSlice(0f)

    var scrolledPx = 0f
    repeat(maxIterations) {
        val node = interaction.fetchSemanticsNode()
        val range: ScrollAxisRange = node.config.getOrNull(axisKey)
            ?: return ScrollDriveResult.Completed(scrolledPx)
        val scrollByAction = node.config.getOrNull(SemanticsActions.ScrollBy)?.action
            ?: return ScrollDriveResult.Completed(scrolledPx)

        val remaining = (range.maxValue() - range.value()).coerceAtLeast(0f)
        if (remaining <= SETTLED_EPSILON_PX) return ScrollDriveResult.Completed(scrolledPx)

        val headroom = (cap - scrolledPx).coerceAtLeast(0f)
        if (headroom <= SETTLED_EPSILON_PX) return ScrollDriveResult.CapReached(scrolledPx)

        val step = minOf(stepPx, remaining, headroom)
        val (dx, dy) = when (axis) {
            ScrollAxis.VERTICAL -> 0f to step
            ScrollAxis.HORIZONTAL -> step to 0f
        }
        scrollByAction.invoke(dx, dy)
        scrolledPx += step
        rule.mainClock.advanceTimeBy(advanceMsPerStep)

        onSlice(scrolledPx)
    }
    return ScrollDriveResult.IterationCapReached(scrolledPx)
}

/**
 * Drives a scrollable back to position 0 on the given axis. Mirrors
 * [driveScrollToEnd] in the opposite direction.
 *
 * Used when a later capture mode in a multi-mode `@ScrollingPreview` needs
 * to start from the top but an earlier mode (END / LONG / a prior GIF)
 * left the scrollable at its content end. All captures within one preview
 * share the same `setContent` composition, so scroll state persists across
 * them by default — see issue #154.
 */
@Suppress("LongParameterList")
internal fun driveScrollToStart(
    rule: AndroidComposeTestRule<*, *>,
    axis: ScrollAxis,
    maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    advanceMsPerStep: Long = DEFAULT_ADVANCE_MS_PER_STEP,
): ScrollDriveResult {
    val axisKey = when (axis) {
        ScrollAxis.VERTICAL -> SemanticsProperties.VerticalScrollAxisRange
        ScrollAxis.HORIZONTAL -> SemanticsProperties.HorizontalScrollAxisRange
    }
    val scrollables = rule.onAllNodes(SemanticsMatcher.keyIsDefined(axisKey)).fetchSemanticsNodes()
    if (scrollables.isEmpty()) return ScrollDriveResult.NoScrollable

    val interaction = rule.onAllNodes(SemanticsMatcher.keyIsDefined(axisKey))[0]
    var scrolledPx = 0f

    repeat(maxIterations) {
        val node = interaction.fetchSemanticsNode()
        val range: ScrollAxisRange = node.config.getOrNull(axisKey)
            ?: return ScrollDriveResult.Completed(scrolledPx)
        val scrollByAction = node.config.getOrNull(SemanticsActions.ScrollBy)?.action
            ?: return ScrollDriveResult.Completed(scrolledPx)

        val current = range.value()
        if (current <= SETTLED_EPSILON_PX) return ScrollDriveResult.Completed(scrolledPx)

        val (dx, dy) = when (axis) {
            ScrollAxis.VERTICAL -> 0f to -current
            ScrollAxis.HORIZONTAL -> -current to 0f
        }
        scrollByAction.invoke(dx, dy)
        scrolledPx += current

        // ScrollBy dispatches animateScrollBy — same timing invariant as
        // driveScrollToEnd; advance enough virtual time for the animation
        // to land before we read the axis range again.
        rule.mainClock.advanceTimeBy(advanceMsPerStep)
    }
    return ScrollDriveResult.IterationCapReached(scrolledPx)
}

/**
 * Single-step scroll helper used by the scripted `ScrollMode.GIF` walk.
 * Scrolls by `min(deltaPx, remaining)` on the first scrollable matching
 * [axis], advances virtual time so `animateScrollBy` settles, and returns
 * the pixels actually consumed (0 if no scrollable or already at end).
 *
 * Unlike [driveScrollByViewport], this does not emit intermediate callbacks
 * or iterate — the caller owns per-step capture and timing. Kept tiny so
 * the GIF script builder can shape the sequence (slow ramp, fling decay,
 * inter-fling holds) without fighting the driver.
 */
internal fun driveScrollBy(
    rule: AndroidComposeTestRule<*, *>,
    axis: ScrollAxis,
    deltaPx: Float,
    advanceMsPerStep: Long = DEFAULT_ADVANCE_MS_PER_STEP,
): Float {
    if (deltaPx <= 0f) return 0f
    val axisKey = when (axis) {
        ScrollAxis.VERTICAL -> SemanticsProperties.VerticalScrollAxisRange
        ScrollAxis.HORIZONTAL -> SemanticsProperties.HorizontalScrollAxisRange
    }
    val scrollables = rule.onAllNodes(SemanticsMatcher.keyIsDefined(axisKey)).fetchSemanticsNodes()
    if (scrollables.isEmpty()) return 0f

    val interaction = rule.onAllNodes(SemanticsMatcher.keyIsDefined(axisKey))[0]
    val node = interaction.fetchSemanticsNode()
    val range: ScrollAxisRange = node.config.getOrNull(axisKey) ?: return 0f
    val scrollByAction = node.config.getOrNull(SemanticsActions.ScrollBy)?.action ?: return 0f

    val remaining = (range.maxValue() - range.value()).coerceAtLeast(0f)
    if (remaining <= SETTLED_EPSILON_PX) return 0f

    val step = minOf(deltaPx, remaining)
    val (dx, dy) = when (axis) {
        ScrollAxis.VERTICAL -> 0f to step
        ScrollAxis.HORIZONTAL -> step to 0f
    }
    scrollByAction.invoke(dx, dy)
    rule.mainClock.advanceTimeBy(advanceMsPerStep)
    return step
}

/**
 * Returns the remaining scrollable extent on [axis] — `maxValue - value`
 * — or `0` if no scrollable is mounted. Used as an up-front hint when
 * building the GIF scroll script; final length is still adaptive at
 * runtime because LazyList reports its max progressively.
 */
internal fun remainingScrollPx(
    rule: AndroidComposeTestRule<*, *>,
    axis: ScrollAxis,
): Float {
    val axisKey = when (axis) {
        ScrollAxis.VERTICAL -> SemanticsProperties.VerticalScrollAxisRange
        ScrollAxis.HORIZONTAL -> SemanticsProperties.HorizontalScrollAxisRange
    }
    val scrollables = rule.onAllNodes(SemanticsMatcher.keyIsDefined(axisKey)).fetchSemanticsNodes()
    if (scrollables.isEmpty()) return 0f
    val node = rule.onAllNodes(SemanticsMatcher.keyIsDefined(axisKey))[0].fetchSemanticsNode()
    val range: ScrollAxisRange = node.config.getOrNull(axisKey) ?: return 0f
    return (range.maxValue() - range.value()).coerceAtLeast(0f)
}

internal sealed interface ScrollDriveResult {
    /** No scrollable composable found on the requested axis. */
    data object NoScrollable : ScrollDriveResult

    /** Reached `value == maxValue` (± epsilon). */
    data class Completed(val scrolledPx: Float) : ScrollDriveResult

    /** Annotation's `maxScrollPx` cap hit before the content ended. */
    data class CapReached(val scrolledPx: Float) : ScrollDriveResult

    /** [DEFAULT_MAX_ITERATIONS] reached without the scroll settling — usually a runaway LazyList. */
    data class IterationCapReached(val scrolledPx: Float) : ScrollDriveResult
}

// 30 iterations × 250ms of virtual time = 7.5s budget, enough for 100-ish
// LazyColumn items' worth of progressive materialization without runaway.
private const val DEFAULT_MAX_ITERATIONS = 30
private const val DEFAULT_ADVANCE_MS_PER_STEP = 250L

// Sub-pixel remainder from fractional density scaling shouldn't keep us spinning.
private const val SETTLED_EPSILON_PX = 0.5f
