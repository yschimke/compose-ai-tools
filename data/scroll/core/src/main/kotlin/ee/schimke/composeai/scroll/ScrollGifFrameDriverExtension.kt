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

data class ScrollGifFramePlan(
  val contentExtentPxHint: Float,
  val viewportPx: Float,
  val density: Float,
  val frameIntervalMs: Int = DEFAULT_SCROLL_GIF_FRAME_INTERVAL_MS,
) {
  init {
    require(contentExtentPxHint >= 0f) { "Content extent must be non-negative." }
    require(viewportPx >= 0f) { "Viewport size must be non-negative." }
    require(density >= 0f) { "Density must be non-negative." }
    require(frameIntervalMs >= 0) { "Frame interval must be non-negative." }
  }

  val effectiveFrameIntervalMs: Int
    get() =
      if (frameIntervalMs == 0) {
        DEFAULT_SCROLL_GIF_FRAME_INTERVAL_MS
      } else {
        frameIntervalMs
      }
}

data class ScrollGifFrame(
  val frame: ExtensionFrame,
  val scrollDeltaPx: Float,
) {
  init {
    require(scrollDeltaPx >= 0f) { "Scroll delta must be non-negative." }
  }
}

data class ScrollGifFrameSequence(
  val frames: List<ScrollGifFrame>,
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
          "frameDelaysMs" to frames.map { it.frame.delayMillis },
          "contentExtentPxHint" to contentExtentPxHint,
          "viewportPx" to viewportPx,
          "density" to density,
        ),
    )
}

object ScrollGifExtensionStateKeys {
  val Frames: ExtensionStateKey<ScrollGifFrameSequence> =
    ExtensionStateKey(
      owner = DataExtensionId(ScrollPreviewExtension.KIND_GIF),
      name = "frames",
      type = ScrollGifFrameSequence::class.java,
    )
}

/**
 * Clean data-extension hook for planning animated scroll-GIF frames.
 *
 * The extension owns the calibrated scroll deltas and their normalized frame projection. Hosts can
 * bind [scrollFrames] to their own scroll/capture implementation without hardcoding scroll-GIF
 * timing rules in the daemon or renderer.
 */
class ScrollGifFrameDriverExtension(private val plan: ScrollGifFramePlan) :
  NormalizedFrameDriverExtension(
    id = DataExtensionId(ScrollPreviewExtension.KIND_GIF),
    constraints =
      DataExtensionConstraints(
        phase = DataExtensionPhase.Scenario,
        lifecycle = DataExtensionLifecycle.MultiFrame,
        provides =
          setOf(
            DataExtensionCapability(ScrollPreviewExtension.KIND_GIF),
            DataExtensionCapability("scroll/frames"),
          ),
      ),
  ) {
  fun scrollFrames(context: ExtensionFrameContext): ScrollGifFrameSequence {
    val steps =
      buildGifScrollScript(
        contentExtentPxHint = plan.contentExtentPxHint,
        viewportPx = plan.viewportPx,
        density = plan.density,
        frameIntervalMs = plan.effectiveFrameIntervalMs,
      )
    if (steps.isEmpty()) {
      return exportFrames(
        context,
        ScrollGifFrameSequence(
          frames =
            listOf(
              ScrollGifFrame(
                frame = ExtensionFrame(index = 0, fraction = 0f, timeMillis = 0L, delayMillis = 0),
                scrollDeltaPx = 0f,
              )
            ),
          contentExtentPxHint = plan.contentExtentPxHint,
          viewportPx = plan.viewportPx,
          density = plan.density,
        ),
      )
    }

    val frames = mutableListOf<ScrollGifFrame>()
    var timeMillis = 0L
    var scrolledPx = 0f
    frames +=
      ScrollGifFrame(
        frame =
          ExtensionFrame(
            index = 0,
            fraction = 0f,
            timeMillis = timeMillis,
            delayMillis = HOLD_START_MS,
          ),
        scrollDeltaPx = 0f,
      )
    steps.forEachIndexed { index, step ->
      timeMillis += frames.last().frame.delayMillis
      scrolledPx = (scrolledPx + step.scrollPx).coerceAtMost(plan.contentExtentPxHint)
      frames +=
        ScrollGifFrame(
          frame =
            ExtensionFrame(
              index = index + 1,
              fraction = (scrolledPx / plan.contentExtentPxHint).coerceIn(0f, 1f),
              timeMillis = timeMillis,
              delayMillis = step.delayMs,
            ),
          scrollDeltaPx = step.scrollPx,
        )
    }

    return exportFrames(
      context,
      ScrollGifFrameSequence(
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
    frames: ScrollGifFrameSequence,
  ): ScrollGifFrameSequence {
    context.exportState(ScrollGifExtensionStateKeys.Frames, staticExtensionState(frames))
    return frames
  }
}

const val DEFAULT_SCROLL_GIF_FRAME_INTERVAL_MS: Int = 80
