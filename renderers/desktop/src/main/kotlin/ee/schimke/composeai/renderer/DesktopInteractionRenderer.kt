package ee.schimke.composeai.renderer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import java.io.File

/**
 * Renders an `@InteractionPreview` capture on Compose Desktop: a scripted pointer gesture driven
 * against the live composition on a paused clock, encoded as an animated APNG (or GIF).
 *
 * ### Why this can be an ordinary render rather than a recording session
 *
 * A scripted interaction needs exactly two capabilities, and this backend's test harness already
 * has both — they were simply never pointed at each other:
 * * a **pausable clock**, so the motion is sampled deterministically instead of at whatever rate
 *   the machine happened to manage. [renderAnimatedPreview] drives this.
 * * **real pointer injection**, so the component's own `Modifier.clickable` / `toggleable` wiring
 *   emits its own `PressInteraction`, ripple and state-layer changes. [renderFocusedPreview] drives
 *   this for a single held press.
 *
 * `runSkikoComposeUiTest` is the one desktop surface that offers both, which is why this sits in
 * the render lane next to its siblings rather than behind the daemon's recording protocol. The
 * capture is reproducible frame-for-frame: virtual time only moves when this loop moves it, so the
 * same script yields the same bytes on a loaded CI machine and an idle laptop alike.
 *
 * ### The pointer is real, and that is the whole point
 *
 * Nothing here forges an interaction. The renderer dispatches `down` / `up` at the resolved node's
 * centre and lets the component respond — the same reasoning [DesktopFocusRenderer] documents for
 * its pressed captures. A capture built by emitting `PressInteraction.Press` onto a
 * `MutableInteractionSource` from a `LaunchedEffect` would show a component that *looks* pressed
 * without anything having pressed it, which documents the author's belief about the component
 * rather than the component. The distinction matters most exactly where these captures are aimed: a
 * Material 3 Expressive container morphs into its pressed shape through its own interaction
 * plumbing, so a forged press would show the shape and hide whether the plumbing works.
 *
 * `LocalInspectionMode` is provided as `false` for the same reason [renderAnimatedPreview] does it
 * — components short-circuit animations in inspection mode, and here it goes further: a catalog
 * sticker commonly freezes its own state under inspection so a baked PNG can't depend on being
 * tapped. Under a real interaction capture we want precisely the opposite, so the composition runs
 * in its live-lane configuration.
 */
@OptIn(ExperimentalTestApi::class)
fun renderInteractionPreview(
  className: String,
  functionName: String,
  widthPx: Int,
  heightPx: Int,
  density: Float,
  showBackground: Boolean,
  backgroundColor: Long,
  outputFile: File,
  wrapperClassName: String?,
  previewArgs: List<Any?>,
  localeTag: String?,
  spec: InteractionSpec,
  uiMode: Int = 0,
  fontScale: Float = 1.0f,
  wrapWidth: Boolean = false,
  wrapHeight: Boolean = false,
  sizeBounds: PreviewSizeBounds = PreviewSizeBounds(),
  classLoader: ClassLoader? = null,
) {
  val composableMethod = resolveMotionComposable(className, functionName, previewArgs, classLoader)

  val frameInterval = spec.frameIntervalMs.coerceAtLeast(1)
  val timeline = spec.timeline()
  val totalDuration = timeline.durationMs.coerceAtMost(MAX_INTERACTION_DURATION_MS)
  val frameCount = (totalDuration / frameInterval).coerceAtLeast(1)

  val rtl = rendersRightToLeft(localeTag)
  val sceneDensity = Density(density, fontScale)
  val sceneSize = composePreviewSceneSize(widthPx, heightPx, wrapWidth, wrapHeight, sizeBounds)
  val bgColor =
    when {
      backgroundColor != 0L -> Color(backgroundColor.toInt())
      showBackground -> Color.White
      else -> Color.Transparent
    }

  val result =
    recordMotionCapture(
      outputFile = outputFile,
      format = spec.format,
      frameIntervalMs = frameInterval,
      padArgb = bgColor.toArgb(),
    ) { collector, forcedCrop ->
      val bounds = MotionBoundsTracker()
      // Until the first measure lands there is nothing to crop to, so the pass starts committed to
      // the whole scene and narrows once the content has been measured.
      var crop = forcedCrop ?: sceneSize

      runSkikoComposeUiTest(
        size = Size(sceneSize.width.toFloat(), sceneSize.height.toFloat()),
        density = sceneDensity,
      ) {
        mainClock.autoAdvance = false

        setContent {
          MotionCaptureRoot(
            rtl = rtl,
            sceneDensity = sceneDensity,
            uiMode = uiMode,
            wrapWidth = wrapWidth,
            wrapHeight = wrapHeight,
            backgroundColor = bgColor,
            sizeBounds = sizeBounds,
            onMeasured = bounds::observe,
            wrapperClassName = wrapperClassName,
            classLoader = classLoader,
          ) {
            InvokeMotionComposable(composableMethod, null, previewArgs)
          }
        }

        // One tick so first composition + layout land: the target nodes don't have bounds to aim at
        // until layout has run, and frame 0 should show the component at rest rather than
        // unlaid-out. It is also what gives [bounds] the resting measurement the crop comes from.
        mainClock.advanceTimeByFrame()

        if (forcedCrop == null) {
          crop = motionCropSize(bounds.size, wrapWidth, wrapHeight, widthPx, heightPx, sceneSize)
        }

        // Resolve every target ONCE, up front, against the composition at rest. Re-resolving per
        // gesture would look more robust and be less so: a component that reflows as it responds (a
        // navigation bar whose selected item widens, a container mid-shape-morph) would move the
        // node the *next* index refers to, so a script written against the resting layout would
        // silently start hitting different things partway through the recording.
        val targetCentres = resolveTargetCentres(spec.targets, outputFile.name)

        var elapsed = 0
        var nextEvent = 0
        repeat(frameCount) {
          while (nextEvent < timeline.events.size && timeline.events[nextEvent].atMs <= elapsed) {
            val event = timeline.events[nextEvent]
            // Non-null by construction: `resolveTargetCentres` is given the same target list the
            // timeline was expanded from, and refuses the whole render for an index it can't
            // resolve.
            val centre = targetCentres.getValue(event.target)
            onRoot().performTouchInput { if (event.down) down(centre) else up() }
            nextEvent++
          }
          val frame = captureMotionSurfacePngBytes()
          observeMotionRootBounds(bounds)
          collector.capture(frame, crop)
          mainClock.advanceTimeBy(frameInterval.toLong())
          elapsed += frameInterval
        }

        // Release anything still held. A capture that ends mid-press leaves the pointer down when
        // the composition is torn down, and a reader looping the file would see the component stuck
        // in its pressed state at the loop point with no release to explain it.
        if (nextEvent < timeline.events.size) {
          onRoot().performTouchInput { up() }
        }
      }

      MotionPass(crop = crop, observed = bounds.size)
    }

  System.err.println(
    "@InteractionPreview on ${result.file.name}: ${spec.gesture} × ${spec.targets.size} " +
      "target(s), encoded ${result.frameCount} frame(s) over ${totalDuration}ms @ " +
      "${frameInterval}ms at ${result.crop.width}×${result.crop.height} " +
      "(${spec.format.name.lowercase()}" +
      (if (result.reRecorded) ", re-recorded for growth" else "") +
      ")."
  )
}

/**
 * Resolves each requested target index to a centre point in root coordinates.
 *
 * Indices address the composition's **clickable nodes in traversal order**, which is the order the
 * author wrote them — index 2 of a five-destination navigation bar is the third
 * `ShortNavigationBarItem` in the source. Two properties make that the right addressing scheme for
 * a catalog: it is stable across density, breakpoint and theme (unlike pixel coordinates), and it
 * is stable across locale (unlike matching on label text — this repo's catalog renders in 17 of
 * them). It is also unaffected by RTL mirroring, so one script documents both directions.
 */
@OptIn(ExperimentalTestApi::class)
private fun SkikoComposeUiTest.resolveTargetCentres(
  targets: List<Int>,
  outputName: String,
): Map<Int, Offset> {
  val nodes: List<SemanticsNode> = onAllNodes(hasClickAction()).fetchSemanticsNodes()
  val requested = targets.distinct().sorted()
  val missing = requested.filter { it >= nodes.size }
  // Fail loudly rather than recording a gesture that landed on empty space. A recording of a
  // component not responding is indistinguishable from a component that cannot respond, and
  // answering that question is the artifact's entire job — so a script that has drifted out of
  // range has to stop the render, not publish a plausible-looking file.
  check(missing.isEmpty()) {
    "@InteractionPreview on $outputName: target index ${missing.joinToString()} out of range — " +
      "the preview has ${nodes.size} clickable node(s) (valid indices 0..${nodes.size - 1}). " +
      if (nodes.isEmpty())
        "Nothing in this preview is clickable, so there is no interaction to capture."
      else "Check the `targets` on the annotation against the composable's clickable children."
  }
  return requested.associateWith { index ->
    val bounds = nodes[index].boundsInRoot
    Offset(bounds.center.x, bounds.center.y)
  }
}

/** The declared script, as the renderer receives it. */
data class InteractionSpec(
  val gesture: InteractionGestureKind,
  val targets: List<Int>,
  val holdMs: Int,
  val gapMs: Int,
  val leadInMs: Int,
  val frameIntervalMs: Int,
  val format: MotionFormatKind,
) {
  /**
   * Expands the script into an ordered event list plus the total window to capture.
   *
   * The duration is *derived* here rather than declared anywhere: it is the lead-in plus, per
   * target, one press and one settle window. That keeps a script and its recording from disagreeing
   * about how long the component was given to respond — a hand-set duration that fell short would
   * cut the last spring off mid-flight, which is the exact thing being documented.
   */
  fun timeline(): InteractionTimeline {
    val pressMs = if (gesture == InteractionGestureKind.PRESS_AND_HOLD) holdMs else TAP_PRESS_MS
    val events = mutableListOf<InteractionEvent>()
    var cursor = leadInMs
    for (target in targets) {
      events += InteractionEvent(atMs = cursor, target = target, down = true)
      events += InteractionEvent(atMs = cursor + pressMs, target = target, down = false)
      cursor += pressMs + gapMs
    }
    return InteractionTimeline(events = events, durationMs = cursor)
  }
}

/** One pointer transition on the virtual timeline. */
data class InteractionEvent(val atMs: Int, val target: Int, val down: Boolean)

/** The expanded script: what to dispatch when, and how long to keep capturing. */
data class InteractionTimeline(val events: List<InteractionEvent>, val durationMs: Int)

/** Mirrors `ee.schimke.composeai.preview.InteractionGesture` on the renderer side. */
enum class InteractionGestureKind {
  TAP,
  PRESS_AND_HOLD,
}

/** Mirrors `ee.schimke.composeai.preview.MotionFormat` on the renderer side. */
enum class MotionFormatKind {
  APNG,
  GIF,
}

/**
 * Pointer-down dwell for a [InteractionGestureKind.TAP], in ms.
 *
 * Long enough that the press is a real, observable state — Compose's ripple and Material's state
 * layer both start on `down`, and a `down`/`up` inside one frame would document a component that
 * changed state without ever appearing to be touched. Short enough to stay a tap: it sits well
 * under the long-press threshold, so a component that distinguishes the two takes the tap branch.
 */
private const val TAP_PRESS_MS = 90

/**
 * Hard cap on a captured interaction window. Higher than the animation path's 5s because an
 * interaction is inherently a sequence — five taps with a settle window each is legitimately longer
 * than any single animation — but still bounded, since every frame is a full-size PNG in the
 * output.
 */
private const val MAX_INTERACTION_DURATION_MS = 10_000
