package ee.schimke.composeai.scroll

import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.AndroidComposeTestRule

/**
 * Drives the first scrollable composable matching [axis] to the end of its content, by repeatedly
 * calling `SemanticsActions.ScrollBy` with the remaining delta and advancing the paused
 * [AndroidComposeTestRule.mainClock].
 *
 * Loops because:
 * - `LazyList` / `LazyColumn` reports `maxValue` progressively as items materialize — the first
 *   `ScrollBy` call doesn't know the final extent.
 * - `Modifier.verticalScroll` with a `ScrollState` reports the total content extent up front, but
 *   its `animateScrollBy` under the hood still takes multiple frames of virtual time to settle on
 *   the target.
 *
 * Returns when the remaining delta is ≈ 0 or when [maxScrollPx] (if > 0) is exhausted. Safe no-op
 * if no scrollable is found — caller captures whatever the composition has drawn.
 */
@Suppress("LongParameterList")
fun driveScrollToEnd(
  rule: AndroidComposeTestRule<*, *>,
  axis: ScrollAxis,
  maxScrollPx: Int,
  maxIterations: Int = DEFAULT_MAX_ITERATIONS,
  advanceMsPerStep: Long = DEFAULT_ADVANCE_MS_PER_STEP,
): ScrollDriveResult {
  val axisKey =
    when (axis) {
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
    val range: ScrollAxisRange =
      node.config.getOrNull(axisKey) ?: return ScrollDriveResult.Completed(scrolledPx)
    val scrollByAction =
      node.config.getOrNull(SemanticsActions.ScrollBy)?.action
        ?: return ScrollDriveResult.Completed(scrolledPx)

    // Deliberately still sized from the axis range, unlike [driveScrollByViewport].
    //
    // The placeholder problem is real here too — a plain `LazyColumn` claims `100.0` before it has
    // been scrolled — but this drive *self-corrects*: one 100 px step is enough for the container
    // to publish its true extent, and the next iteration jumps the rest. It reaches the end either
    // way, in two or three iterations instead of one.
    //
    // Enlarging the step anyway is not free: `ScrollBy` animates, so dispatching more than the
    // remaining distance changes the velocity the animation runs at and shifts where the content
    // settles by the time the frame is captured. Measured — it re-rendered the Wear `EdgeButton`
    // sticker to different bytes. A drive that already lands correctly is not worth perturbing for
    // an iteration or two.
    val remaining = (range.maxValue() - range.value()).coerceAtLeast(0f)
    if (remaining <= SETTLED_EPSILON_PX) return ScrollDriveResult.Completed(scrolledPx)

    val headroom = (cap - scrolledPx).coerceAtLeast(0f)
    if (headroom <= SETTLED_EPSILON_PX) return ScrollDriveResult.CapReached(scrolledPx)

    val step = minOf(remaining, headroom)
    val (dx, dy) =
      when (axis) {
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
 * Drives a scrollable by exactly [stepPx] per iteration and invokes [onSlice] with the cumulative
 * scrolled pixel count once at offset 0 (before the first scroll) and again after each successful
 * step. Used by the `LONG` scroll-capture path to take one screenshot per viewport- height of
 * content, which the caller then stitches into one tall PNG.
 *
 * Differs from [driveScrollToEnd] in that each step is a fixed size, not "all remaining" — the
 * caller wants a slice per viewport, not a single jump to the bottom.
 *
 * **Every step is verified against the content, not the scroller's word for it.** `ScrollBy`
 * dispatches `animateScrollBy`, and a scroller's `ScrollAxisRange.value` is not a pixel position on
 * a lazy list (a `LazyColumn` publishes `index × 500 + offset`; a Wear `ScalingLazyColumn` inherits
 * that), so neither "did the spring land yet" nor "how far did the content move" can be read off
 * the semantics range. Instead the driver snapshots the on-screen positions of the scrollable's
 * descendant semantics nodes before the step ([ContentAnchors]), then after dispatching:
 * 1. advances the paused clock frame by frame until those positions stop changing
 *    ([SETTLE_MAX_FRAMES] bound), so a slice is never captured mid-animation;
 * 2. measures how far the content actually travelled (the median displacement of the nodes seen on
 *    both sides of the step) and, when that misses [stepPx] by more than [LANDING_TOLERANCE_PX]
 *    while the scroller still has room, dispatches a corrective `ScrollBy` for the shortfall /
 *    overshoot — up to [MAX_CORRECTIONS] times — until the slice sits where the plan put it.
 *
 * The offset handed to [onSlice] is the *measured* cumulative travel whenever anchors were
 * available, so the stitcher's hint is the truth about the pixels rather than an estimate. Every
 * step's outcome is reported to [onStep] (see [ScrollStep]) so the caller can surface a step that
 * never landed as a render warning instead of a silently mis-stitched seam.
 */
@Suppress("LongParameterList")
fun driveScrollByViewport(
  rule: AndroidComposeTestRule<*, *>,
  axis: ScrollAxis,
  stepPx: Float,
  maxScrollPx: Int,
  maxIterations: Int = DEFAULT_MAX_ITERATIONS,
  advanceMsPerStep: Long = DEFAULT_ADVANCE_MS_PER_STEP,
  onStep: ((ScrollStep) -> Unit)? = null,
  /** Verbose per-frame trace of the anchor bookkeeping, for a developer keeping slices. */
  trace: ((String) -> Unit)? = null,
  onSlice: (scrolledPx: Float) -> Unit,
): ScrollDriveResult {
  require(stepPx > 0f) { "stepPx must be positive, got $stepPx" }

  val axisKey =
    when (axis) {
      ScrollAxis.VERTICAL -> SemanticsProperties.VerticalScrollAxisRange
      ScrollAxis.HORIZONTAL -> SemanticsProperties.HorizontalScrollAxisRange
    }
  val scrollables = rule.onAllNodes(SemanticsMatcher.keyIsDefined(axisKey)).fetchSemanticsNodes()
  if (scrollables.isEmpty()) return ScrollDriveResult.NoScrollable

  val interaction = rule.onAllNodes(SemanticsMatcher.keyIsDefined(axisKey))[0]
  val cap = if (maxScrollPx > 0) maxScrollPx.toFloat() else Float.POSITIVE_INFINITY

  // First slice captures the initial (unscrolled) frame.
  onSlice(0f)

  // Two totals, deliberately. [scrolledPx] is how far the content is believed to have *moved* —
  // the stitching hint, and what callers report. [dispatchedPx] is how many pixels were actually
  // handed to `ScrollBy`, and is the only honest basis for enforcing [maxScrollPx]: `creditedPx`
  // can credit less than was dispatched (a clamped tail on a container whose `value` is exact),
  // and deriving headroom from an under-credited total would quietly let a capped drive scroll
  // past its cap by up to a full stride.
  var scrolledPx = 0f
  var dispatchedPx = 0f
  repeat(maxIterations) { iteration ->
    val node = interaction.fetchSemanticsNode()
    val range: ScrollAxisRange =
      node.config.getOrNull(axisKey) ?: return ScrollDriveResult.Completed(scrolledPx)
    val scrollByAction =
      node.config.getOrNull(SemanticsActions.ScrollBy)?.action
        ?: return ScrollDriveResult.Completed(scrolledPx)

    val headroom = (cap - dispatchedPx).coerceAtLeast(0f)
    if (headroom <= SETTLED_EPSILON_PX) return ScrollDriveResult.CapReached(scrolledPx)

    // Stride by the requested viewport fraction and let the scroller clamp itself at its content
    // end.
    //
    // Clamping against `maxValue - value` first is what broke this: that is not always a usable
    // pixel extent. A plain `LazyColumn` publishes a placeholder `100.0` until it has been
    // scrolled once (measured in the renderer's `WearTlcScrollSemanticsProbeTest`, where it then
    // jumps to `5010.0`), so a 400 px stride became a 100 px one — four times the slices and four
    // times the frames, and against [DEFAULT_MAX_ITERATIONS] a quarter of the reach, silently
    // truncating a long list. Wear's `TransformingLazyColumn` reports a true extent from the first
    // frame and was never affected; it keeps its exact strides through [creditedPx].
    val before = range.value()
    val anchorsBefore = ContentAnchors.snapshot(interaction, axis)
    trace?.invoke("step $iteration before: ${ContentAnchors.describe(anchorsBefore)}")
    val step = minOf(stepPx, headroom)
    dispatchScrollBy(scrollByAction, axis, step)
    dispatchedPx += step
    rule.mainClock.advanceTimeBy(advanceMsPerStep)
    var settle = ContentAnchors.settle(rule, interaction, axis, trace)

    // Verify the landing against the pixels the content actually moved, and correct it.
    var measured = ContentAnchors.displacement(anchorsBefore, settle.anchors)
    trace?.invoke(
      "step $iteration after: ${ContentAnchors.describe(settle.anchors)} measured=$measured " +
        "deltas=${ContentAnchors.deltas(anchorsBefore, settle.anchors)}"
    )
    var corrections = 0
    var correctedPx = 0f
    while (
      measured != null &&
        kotlin.math.abs(measured - step) > LANDING_TOLERANCE_PX &&
        corrections < MAX_CORRECTIONS
    ) {
      val shortfall = step - measured
      // Fell short because the content ran out, not because the spring is still travelling:
      // accept the clamped landing.
      if (shortfall > 0f && remainingOf(interaction, axisKey) <= SETTLED_EPSILON_PX) break
      dispatchScrollBy(scrollByAction, axis, shortfall)
      correctedPx += shortfall
      corrections++
      rule.mainClock.advanceTimeBy(advanceMsPerStep)
      settle = ContentAnchors.settle(rule, interaction, axis, trace)
      measured = ContentAnchors.displacement(anchorsBefore, settle.anchors)
      trace?.invoke(
        "step $iteration correction $corrections by $shortfall: " +
          "${ContentAnchors.describe(settle.anchors)} measured=$measured " +
          "deltas=${ContentAnchors.deltas(anchorsBefore, settle.anchors)}"
      )
    }

    val after = interaction.fetchSemanticsNode().config.getOrNull(axisKey)?.value?.invoke()
    val credited = measured ?: creditedPx(before, after, step)
    val landed =
      measured == null ||
        kotlin.math.abs(measured - step) <= LANDING_TOLERANCE_PX ||
        remainingOf(interaction, axisKey) <= SETTLED_EPSILON_PX
    onStep?.invoke(
      ScrollStep(
        index = iteration,
        requestedPx = step,
        measuredPx = measured,
        reportedPx = if (after == null) null else after - before,
        corrections = corrections,
        correctedPx = correctedPx,
        settleFrames = settle.frames,
        settled = settle.settled,
        landed = landed,
        anchors = settle.anchors.size,
      )
    )
    scrolledPx += credited
    // Nothing moved ⇒ the content end, so don't emit another slice of the same frame.
    val moved =
      if (measured != null) kotlin.math.abs(measured) > SETTLED_EPSILON_PX
      else after == null || kotlin.math.abs(after - before) > SETTLED_EPSILON_PX
    if (!moved) return ScrollDriveResult.Completed(scrolledPx)

    onSlice(scrolledPx)
  }
  return ScrollDriveResult.IterationCapReached(scrolledPx)
}

/**
 * What one stride of [driveScrollByViewport] did, for the caller's diagnostics.
 *
 * [measuredPx] is how far the content really moved, read off the descendant semantics bounds
 * ([ContentAnchors]); `null` when the scrollable exposes no measurable descendants (then the driver
 * falls back to the scroller's own [reportedPx] / the requested stride, exactly as before).
 * [landed] is false only when the step measurably missed [requestedPx] after every correction and
 * the scroller still had room — the one case the stitched seam cannot be trusted.
 */
data class ScrollStep(
  val index: Int,
  val requestedPx: Float,
  val measuredPx: Float?,
  val reportedPx: Float?,
  val corrections: Int,
  val correctedPx: Float,
  val settleFrames: Int,
  val settled: Boolean,
  val landed: Boolean,
  val anchors: Int,
) {
  /** One-line, human-readable summary for logs and the warnings sidecar. */
  fun describe(): String =
    "step $index: requested ${"%.1f".format(java.util.Locale.ROOT, requestedPx)}px, " +
      (if (measuredPx == null) "content unmeasured (no anchors)"
      else "content moved ${"%.1f".format(java.util.Locale.ROOT, measuredPx)}px") +
      (if (corrections > 0)
        " after $corrections correction(s) (${"%.1f".format(java.util.Locale.ROOT, correctedPx)}px)"
      else "") +
      (if (reportedPx != null)
        ", scroller reported ${"%.1f".format(java.util.Locale.ROOT, reportedPx)}"
      else "") +
      ", settled in $settleFrames frame(s)" +
      (if (!settled) " (still moving)" else "") +
      (if (!landed) " — DID NOT LAND" else "")
}

private fun dispatchScrollBy(
  scrollByAction: (Float, Float) -> Boolean,
  axis: ScrollAxis,
  deltaPx: Float,
) {
  val (dx, dy) =
    when (axis) {
      ScrollAxis.VERTICAL -> 0f to deltaPx
      ScrollAxis.HORIZONTAL -> deltaPx to 0f
    }
  scrollByAction.invoke(dx, dy)
}

private fun remainingOf(
  interaction: androidx.compose.ui.test.SemanticsNodeInteraction,
  axisKey: androidx.compose.ui.semantics.SemanticsPropertyKey<ScrollAxisRange>,
): Float {
  val range = interaction.fetchSemanticsNode().config.getOrNull(axisKey) ?: return 0f
  return (range.maxValue() - range.value()).coerceAtLeast(0f)
}

/**
 * The on-screen positions of a scrollable's descendant semantics nodes, keyed by node id — the only
 * pixel-accurate account of where the content is that a lazy list offers. Semantics ids are stable
 * for as long as a composable stays composed, so a node present before and after a scroll pins the
 * exact distance the content travelled, whatever the scroller's `ScrollAxisRange` says.
 */
internal object ContentAnchors {
  /**
   * The outcome of [settle]: the last snapshot, how many frames it took, and whether it stopped.
   */
  data class Settled(val anchors: Map<Int, Float>, val frames: Int, val settled: Boolean)

  /**
   * Leading edge (top for vertical, left for horizontal) of every descendant, by semantics id.
   *
   * Read off `positionInRoot`, never `boundsInRoot`: the latter is clipped to the ancestors'
   * bounds, so an item sliding out under the top edge of the viewport reports `top = 0` however far
   * it has gone, and its displacement saturates at wherever it started. Measured on horologist's
   * sectioned list: three of four tracked items had been clipped that way, the median "moved 292"
   * against a real 307, and every correction the driver then sent moved the content another 15 px
   * without the measurement changing at all.
   */
  fun snapshot(
    interaction: androidx.compose.ui.test.SemanticsNodeInteraction,
    axis: ScrollAxis,
  ): Map<Int, Float> {
    val out = HashMap<Int, Float>()
    fun walk(node: androidx.compose.ui.semantics.SemanticsNode) {
      for (child in node.children) {
        val size = child.size
        if (size.width > 0 && size.height > 0) {
          val position = child.positionInRoot
          out[child.id] = if (axis == ScrollAxis.VERTICAL) position.y else position.x
        }
        walk(child)
      }
    }
    walk(interaction.fetchSemanticsNode())
    return out
  }

  /**
   * Advances the paused clock one frame at a time until two consecutive snapshots agree (every node
   * seen in both within [SETTLED_EPSILON_PX]), or [SETTLE_MAX_FRAMES] is spent.
   */
  fun settle(
    rule: AndroidComposeTestRule<*, *>,
    interaction: androidx.compose.ui.test.SemanticsNodeInteraction,
    axis: ScrollAxis,
    trace: ((String) -> Unit)? = null,
  ): Settled {
    var previous = snapshot(interaction, axis)
    repeat(SETTLE_MAX_FRAMES) { frame ->
      rule.mainClock.advanceTimeByFrame()
      val current = snapshot(interaction, axis)
      trace?.invoke("  settle frame ${frame + 1}: ${describe(current)}")
      if (stable(previous, current)) return Settled(current, frame + 1, settled = true)
      previous = current
    }
    return Settled(previous, SETTLE_MAX_FRAMES, settled = false)
  }

  /** Compact `id@pos` listing of [anchors], sorted by id, for [driveScrollByViewport]'s trace. */
  fun describe(anchors: Map<Int, Float>): String =
    anchors.entries
      .sortedBy { it.key }
      .joinToString(" ") { "${it.key}@${"%.1f".format(java.util.Locale.ROOT, it.value)}" }

  /** Every per-node delta between two snapshots, for the trace. */
  fun deltas(before: Map<Int, Float>, after: Map<Int, Float>): String =
    before.entries
      .sortedBy { it.key }
      .mapNotNull { (id, pos) -> after[id]?.let { "$id:${"%.1f".format(pos - it)}" } }
      .joinToString(" ")

  private fun stable(a: Map<Int, Float>, b: Map<Int, Float>): Boolean {
    var common = 0
    for ((id, pos) in a) {
      val other = b[id] ?: continue
      common++
      if (kotlin.math.abs(pos - other) > SETTLED_EPSILON_PX) return false
    }
    // No shared node between two frames means the whole population was replaced — a lazy list
    // mid-fling — so that is not stability either, unless there is simply nothing to compare.
    return common > 0 || (a.isEmpty() && b.isEmpty())
  }

  /**
   * Median distance the content travelled between two snapshots, positive in the scroll direction;
   * `null` when no node is present in both (nothing to measure against).
   */
  fun displacement(before: Map<Int, Float>, after: Map<Int, Float>): Float? {
    val deltas = ArrayList<Float>()
    for ((id, pos) in before) {
      val now = after[id] ?: continue
      deltas += pos - now
    }
    if (deltas.isEmpty()) return null
    deltas.sort()
    return deltas[deltas.size / 2]
  }
}

/**
 * Drives a scrollable back to position 0 on the given axis. Mirrors [driveScrollToEnd] in the
 * opposite direction.
 *
 * Used when a later capture mode in a multi-mode `@ScrollingPreview` needs to start from the top
 * but an earlier mode (END / LONG / a prior GIF) left the scrollable at its content end. All
 * captures within one preview share the same `setContent` composition, so scroll state persists
 * across them by default — see issue #154.
 */
@Suppress("LongParameterList")
fun driveScrollToStart(
  rule: AndroidComposeTestRule<*, *>,
  axis: ScrollAxis,
  maxIterations: Int = DEFAULT_MAX_ITERATIONS,
  advanceMsPerStep: Long = DEFAULT_ADVANCE_MS_PER_STEP,
): ScrollDriveResult {
  val axisKey =
    when (axis) {
      ScrollAxis.VERTICAL -> SemanticsProperties.VerticalScrollAxisRange
      ScrollAxis.HORIZONTAL -> SemanticsProperties.HorizontalScrollAxisRange
    }
  val scrollables = rule.onAllNodes(SemanticsMatcher.keyIsDefined(axisKey)).fetchSemanticsNodes()
  if (scrollables.isEmpty()) return ScrollDriveResult.NoScrollable

  val interaction = rule.onAllNodes(SemanticsMatcher.keyIsDefined(axisKey))[0]
  var scrolledPx = 0f

  repeat(maxIterations) {
    val node = interaction.fetchSemanticsNode()
    val range: ScrollAxisRange =
      node.config.getOrNull(axisKey) ?: return ScrollDriveResult.Completed(scrolledPx)
    val scrollByAction =
      node.config.getOrNull(SemanticsActions.ScrollBy)?.action
        ?: return ScrollDriveResult.Completed(scrolledPx)

    val current = range.value()
    if (current <= SETTLED_EPSILON_PX) return ScrollDriveResult.Completed(scrolledPx)

    val (dx, dy) =
      when (axis) {
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
 * Single-step scroll helper used by the scripted `ScrollMode.GIF` walk. Scrolls by `min(deltaPx,
 * remaining)` on the first scrollable matching [axis], advances virtual time so `animateScrollBy`
 * settles, and returns the pixels actually consumed (0 if no scrollable or already at end).
 *
 * Unlike [driveScrollByViewport], this does not emit intermediate callbacks or iterate — the caller
 * owns per-step capture and timing. Kept tiny so the GIF script builder can shape the sequence
 * (slow ramp, fling decay, inter-fling holds) without fighting the driver.
 */
fun driveScrollBy(
  rule: AndroidComposeTestRule<*, *>,
  axis: ScrollAxis,
  deltaPx: Float,
  advanceMsPerStep: Long = DEFAULT_ADVANCE_MS_PER_STEP,
): Float {
  if (deltaPx <= 0f) return 0f
  val axisKey =
    when (axis) {
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
  val (dx, dy) =
    when (axis) {
      ScrollAxis.VERTICAL -> 0f to step
      ScrollAxis.HORIZONTAL -> step to 0f
    }
  scrollByAction.invoke(dx, dy)
  rule.mainClock.advanceTimeBy(advanceMsPerStep)
  return step
}

/**
 * Returns the remaining scrollable extent on [axis] — `maxValue - value` — or `0` if no scrollable
 * is mounted. Used as an up-front hint when building the GIF scroll script; final length is still
 * adaptive at runtime because LazyList reports its max progressively.
 */
fun remainingScrollPx(rule: AndroidComposeTestRule<*, *>, axis: ScrollAxis): Float {
  val axisKey =
    when (axis) {
      ScrollAxis.VERTICAL -> SemanticsProperties.VerticalScrollAxisRange
      ScrollAxis.HORIZONTAL -> SemanticsProperties.HorizontalScrollAxisRange
    }
  val scrollables = rule.onAllNodes(SemanticsMatcher.keyIsDefined(axisKey)).fetchSemanticsNodes()
  if (scrollables.isEmpty()) return 0f
  val node = rule.onAllNodes(SemanticsMatcher.keyIsDefined(axisKey))[0].fetchSemanticsNode()
  val range: ScrollAxisRange = node.config.getOrNull(axisKey) ?: return 0f
  return (range.maxValue() - range.value()).coerceAtLeast(0f)
}

sealed interface ScrollDriveResult {
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
/**
 * How much of a dispatched [requested] stride to count as travelled.
 *
 * Prefers the movement the scroller actually reported. `ScrollAxisRange.value` is trustworthy on a
 * container that knows its extent — Wear's `TransformingLazyColumn` reports the exact clamped tail,
 * so the final slice is credited 353 px rather than the 400 px asked for — but it is not
 * universally so: a plain `LazyColumn`'s `value` leaps to its newly-discovered extent (0 → 5010 for
 * a single 1000 px scroll) the first time it is scrolled. A reported move larger than what was
 * dispatched is therefore a re-scaling, not travel, and the requested distance is the better
 * estimate.
 *
 * Callers treat the total as a hint — the Android stitcher decides placement by pixel matching — so
 * this only has to be right enough to keep the hint useful.
 */
private fun creditedPx(before: Float, after: Float?, requested: Float): Float {
  if (after == null) return requested
  val moved = after - before
  return when {
    // Didn't move: the content end. Crediting the requested stride here would overstate the total
    // by a full step on the last, wasted iteration of every drive.
    kotlin.math.abs(moved) <= SETTLED_EPSILON_PX -> 0f
    moved > 0f && moved <= requested + 1f -> moved
    else -> requested
  }
}

private const val DEFAULT_MAX_ITERATIONS = 30
private const val DEFAULT_ADVANCE_MS_PER_STEP = 250L

/**
 * How far a measured landing may miss the requested stride before [driveScrollByViewport] sends a
 * corrective `ScrollBy`. One layout pixel: the stitcher only needs the hint to be close, but a
 * stride that is short by a whole item because the spring had not landed is a mis-stitched seam.
 */
private const val LANDING_TOLERANCE_PX = 1f

/**
 * Corrective strides per step. Two is plenty for a spring tail; more is a scroller fighting back.
 */
private const val MAX_CORRECTIONS = 3

/**
 * Frames of paused-clock time [ContentAnchors.settle] will spend waiting for the content to stop
 * moving after a `ScrollBy` — about a second at 16 ms, the same order as the post-scroll settle the
 * renderer already grants an EdgeButton reveal.
 */
private const val SETTLE_MAX_FRAMES = 60

// Sub-pixel remainder from fractional density scaling shouldn't keep us spinning.
private const val SETTLED_EPSILON_PX = 0.5f
