package ee.schimke.composeai.scroll

import ee.schimke.composeai.data.render.extensions.DataExtensionCapability
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionLifecycle
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.compose.ExtensionFrame
import ee.schimke.composeai.data.render.extensions.compose.ExtensionFrameContext
import ee.schimke.composeai.data.render.extensions.compose.ExtensionFrameSequence
import ee.schimke.composeai.data.render.extensions.compose.ExtensionStateKey
import ee.schimke.composeai.data.render.extensions.compose.NormalizedFrameDriverExtension
import ee.schimke.composeai.data.render.extensions.compose.staticExtensionState
import kotlin.math.ceil

data class ScrollLongFramePlan(
  val contentExtentPxHint: Float,
  val viewportPx: Float,
  val density: Float,
  val stepFractionOfViewport: Float = DEFAULT_LONG_SCROLL_STEP_FRACTION,
) {
  init {
    require(contentExtentPxHint >= 0f) { "Content extent must be non-negative." }
    require(viewportPx >= 0f) { "Viewport size must be non-negative." }
    require(density >= 0f) { "Density must be non-negative." }
    require(stepFractionOfViewport > 0f && stepFractionOfViewport <= 1f) {
      "Step fraction must be in the (0..1] range."
    }
  }

  val stepPx: Float
    get() = viewportPx * stepFractionOfViewport
}

data class ScrollLongFrame(val frame: ExtensionFrame, val scrollDeltaPx: Float) {
  init {
    require(scrollDeltaPx >= 0f) { "Scroll delta must be non-negative." }
  }
}

data class ScrollLongFrameSequence(
  val frames: List<ScrollLongFrame>,
  val contentExtentPxHint: Float,
  val viewportPx: Float,
  val density: Float,
) {
  val scrollDeltasPx: List<Float>
    get() = frames.map { it.scrollDeltaPx }

  fun asExtensionFrameSequence(): ExtensionFrameSequence =
    ExtensionFrameSequence(
      frames = frames.map { it.frame },
      extras =
        mapOf(
          "axis" to "vertical",
          "scrollDeltasPx" to scrollDeltasPx,
          "contentExtentPxHint" to contentExtentPxHint,
          "viewportPx" to viewportPx,
          "density" to density,
        ),
    )
}

object ScrollLongExtensionStateKeys {
  val Frames: ExtensionStateKey<ScrollLongFrameSequence> =
    ExtensionStateKey(
      owner = DataExtensionId(ScrollPreviewExtension.KIND_LONG),
      name = "frames",
      type = ScrollLongFrameSequence::class.java,
    )
}

/**
 * Clean data-extension hook for planning long-scroll slice frames.
 *
 * Mirrors [ScrollGifFrameDriverExtension]: the extension owns the per-slice scroll deltas (uniform
 * stride at [ScrollLongFramePlan.stepFractionOfViewport] of the viewport, less than one viewport
 * so consecutive captures overlap for the content-aware stitcher) and their normalized frame
 * projection. Hosts bind the planned deltas to their own scroll/capture implementation without
 * hardcoding scroll-LONG stride rules in the daemon or renderer.
 *
 * The plan is best-effort against [ScrollLongFramePlan.contentExtentPxHint]; the renderer still
 * truncates when the live scrollable reports `remaining ≈ 0` so a `LazyList` that materialises
 * fewer items than the hint suggested doesn't over-scroll.
 */
class ScrollLongFrameDriverExtension(private val plan: ScrollLongFramePlan) :
  NormalizedFrameDriverExtension(
    id = DataExtensionId(ScrollPreviewExtension.KIND_LONG),
    constraints =
      DataExtensionConstraints(
        phase = DataExtensionPhase.Scenario,
        lifecycle = DataExtensionLifecycle.MultiFrame,
        provides =
          setOf(
            DataExtensionCapability(ScrollPreviewExtension.KIND_LONG),
            DataExtensionCapability("scroll/frames"),
          ),
      ),
  ) {
  fun scrollFrames(context: ExtensionFrameContext): ScrollLongFrameSequence {
    val frames = mutableListOf<ScrollLongFrame>()
    // First slice captures the initial (unscrolled) frame.
    frames +=
      ScrollLongFrame(
        frame = ExtensionFrame(index = 0, fraction = 0f, timeMillis = 0L, delayMillis = 0),
        scrollDeltaPx = 0f,
      )

    if (plan.contentExtentPxHint > 0f && plan.viewportPx > 0f && plan.stepPx > 0f) {
      val totalSteps = ceil(plan.contentExtentPxHint / plan.stepPx).toInt()
      var scrolledPx = 0f
      var index = 1
      repeat(totalSteps) {
        val remaining = plan.contentExtentPxHint - scrolledPx
        val delta = minOf(plan.stepPx, remaining)
        if (delta <= 0f) return@repeat
        scrolledPx += delta
        frames +=
          ScrollLongFrame(
            frame =
              ExtensionFrame(
                index = index,
                fraction = (scrolledPx / plan.contentExtentPxHint).coerceIn(0f, 1f),
                timeMillis = 0L,
                delayMillis = 0,
              ),
            scrollDeltaPx = delta,
          )
        index += 1
      }
    }

    return exportFrames(
      context,
      ScrollLongFrameSequence(
        frames = frames,
        contentExtentPxHint = plan.contentExtentPxHint,
        viewportPx = plan.viewportPx,
        density = plan.density,
      ),
    )
  }

  override fun frames(context: ExtensionFrameContext): ExtensionFrameSequence =
    scrollFrames(context).asExtensionFrameSequence()

  private fun exportFrames(
    context: ExtensionFrameContext,
    frames: ScrollLongFrameSequence,
  ): ScrollLongFrameSequence {
    context.exportState(ScrollLongExtensionStateKeys.Frames, staticExtensionState(frames))
    return frames
  }
}

/**
 * Default per-step stride for [ScrollLongFrameDriverExtension]: 80 % of the viewport. Less than one
 * full viewport so consecutive slice pairs have ~20 % physical overlap for the content-aware
 * stitcher to lock onto. Mirrors the historic `handleLongCapture` value.
 */
const val DEFAULT_LONG_SCROLL_STEP_FRACTION: Float = 0.8f
