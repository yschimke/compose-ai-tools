package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntSize
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import ee.schimke.composeai.glimmer.GlimmerEnvironment as ConnectorGlimmerEnvironment
import ee.schimke.composeai.glimmer.GlimmerEnvironmentCompositor
import ee.schimke.composeai.motion.ApngEncoder
import ee.schimke.composeai.motion.InteractionScript
import ee.schimke.composeai.motion.MotionGesture
import ee.schimke.composeai.motion.apngDelayFor
import ee.schimke.composeai.scroll.ScrollGifEncoder
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders an `@InteractionPreview` capture on Robolectric: a scripted pointer gesture driven
 * against the live composition on a paused clock, encoded as an animated APNG (or GIF).
 *
 * The desktop sibling ([`renderInteractionPreview`][ee.schimke.composeai.renderer]) has existed
 * since the annotation landed; this backend had nothing, so an Android or Wear catalog that reached
 * for the annotation got no capture *and* lost the component's ordinary still to the failure that
 * followed (issue #4215). The shape mirrors the desktop one deliberately — same one-tick settle,
 * same resolve-targets-once rule, same derived recording window out of `:data-motion-core` — so the
 * two backends cannot drift into recording different things from one annotation.
 *
 * ### What is genuinely Robolectric-specific
 *
 * Exactly one step: advancing the **main looper** alongside `mainClock` on every frame. Compose's
 * test clock does not drive Android platform animations, and Material's ripple is a platform
 * `RippleDrawable` — without the looper advance the state layer sits frozen at frame 0 for the
 * whole recording while the Compose-side animation plays, which is the half of a press response a
 * reader is most likely to be looking for. The same reasoning is why
 * [handleAnimatedCapture][RobolectricRenderTest] idles the looper per frame.
 *
 * ### The pointer is real, and that is the whole point
 *
 * Nothing here forges an interaction: the renderer dispatches `down` / `up` at the resolved node's
 * centre and lets the component respond through its own `Modifier.clickable` / `toggleable` wiring.
 * A capture built by emitting `PressInteraction.Press` onto a `MutableInteractionSource` would show
 * a component that *looks* pressed without anything having pressed it — it would document the
 * author's belief about the component rather than the component.
 *
 * Returns `true` when [outputFile] was written; `false` to let the caller report that the slot
 * produced nothing.
 */
@OptIn(ExperimentalRoborazziApi::class)
internal fun handleInteractionCapture(
  rule: AndroidComposeTestRule<*, ComponentActivity>,
  interaction: InteractionCapture,
  previewId: String,
  isRound: Boolean,
  outputFile: File,
  wrapWidth: Boolean,
  wrapHeight: Boolean,
  padArgb: Int,
  measuredContent: () -> IntSize?,
  glimmerEnvironment: ConnectorGlimmerEnvironment? = null,
  onClockAdvanced: (Long) -> Unit = {},
): Boolean {
  val frameInterval = interaction.frameIntervalMs.coerceAtLeast(1)
  val timeline =
    InteractionScript.timeline(
      gesture = interaction.gesture.toMotionGesture(),
      targets = interaction.targets,
      holdMs = interaction.holdMs,
      gapMs = interaction.gapMs,
      leadInMs = interaction.leadInMs,
    )
  val totalDuration = timeline.cappedDurationMs
  // The cap truncates the *script*, not merely the frame budget. A script longer than the window
  // has events beyond it, and the budget alone would not stop them being dispatched — a coarse
  // interval deliberately samples past the last admitted event to show its effect, and a release
  // scheduled past the cap would ride along on one of those frames. Filtering here means the
  // recording can only ever contain gestures the window admits; anything still held when the
  // window closes is released by the cleanup below.
  val admittedEvents = timeline.events.filter { it.atMs <= totalDuration }
  // Enough samples to cover the window *and* to record the component's response to the last
  // scripted event. Flooring the duration alone is not enough: an interval as coarse as the script
  // itself yields a single sample at elapsed 0, which — with any lead-in at all — dispatches
  // nothing and publishes a resting frame documenting no interaction.
  //
  // The `+ 2` buys the frame *after* the one the last event lands on: a component responds to a
  // release on the following frame, so stopping at the event's own frame would record the gesture
  // having been dispatched and never its effect.
  //
  // For every ordinary frame rate the duration term is far larger and this floor never binds.
  val lastEventMs = admittedEvents.lastOrNull()?.atMs ?: 0
  val framesToLastEvent =
    if (lastEventMs <= 0) 1 else (lastEventMs + frameInterval - 1) / frameInterval + 2
  val frameCount = maxOf(totalDuration / frameInterval, framesToLastEvent).coerceAtLeast(1)

  val framesDir = File(outputFile.parentFile, "${outputFile.nameWithoutExtension}_gesture_frames")
  framesDir.deleteRecursively()
  framesDir.mkdirs()

  val frameOptions =
    RoborazziOptions(recordOptions = RoborazziOptions.RecordOptions(applyDeviceCrop = isRound))
  val stableDialogCrop = DialogWindowCapture.StableDialogCrop()

  // Read off the clock rather than recomputed from the script: the settle tick, the per-frame
  // advances and the frame count all move independently, and a caller's bookkeeping that
  // re-derives any of them drifts the moment one changes. Reported in `finally`, so a capture that
  // threw part-way still credits exactly the time it actually drove.
  val clockBefore = rule.mainClock.currentTime

  try {
    // One tick so first composition + layout land: the target nodes have no bounds to aim at until
    // layout has run, and frame 0 should show the component at rest rather than unlaid-out.
    rule.mainClock.advanceTimeByFrame()

    val targetCentres = resolveInteractionTargets(rule, interaction.targets, previewId)

    val frameFiles = mutableListOf<File>()
    // The largest frame the capture produced and the largest content it measured, both tracked
    // across the whole recording rather than read once at rest — see [interactionCropSize].
    var widestFrame = 0
    var tallestFrame = 0
    var widestContent = 0
    var tallestContent = 0

    var elapsed = 0
    var nextEvent = 0
    var pointerDown = false
    repeat(frameCount) { index ->
      while (nextEvent < admittedEvents.size && admittedEvents[nextEvent].atMs <= elapsed) {
        val event = admittedEvents[nextEvent]
        // Non-null by construction: `resolveInteractionTargets` was given the same target list the
        // timeline was expanded from, and refuses the whole capture for an index it can't resolve.
        val centre = targetCentres.getValue(event.target)
        DialogWindowCapture.resolveCaptureRoot(rule).interaction.performTouchInput {
          if (event.down) down(centre) else up()
        }
        pointerDown = event.down
        nextEvent++
      }
      val frameFile = File(framesDir, "frame_%05d.png".format(index))
      val frame =
        captureDecodableFrame(frameFile, role = "interaction") { f ->
          stableDialogCrop.captureFrame(rule = rule, file = f, roborazziOptions = frameOptions)
        }
      frameFiles += frameFile
      if (frame.width > widestFrame) widestFrame = frame.width
      if (frame.height > tallestFrame) tallestFrame = frame.height
      measuredContent()?.let { measured ->
        if (measured.width > widestContent) widestContent = measured.width
        if (measured.height > tallestContent) tallestContent = measured.height
      }

      rule.mainClock.advanceTimeBy(frameInterval.toLong())
      // The Robolectric-specific half of the advance — see this file's kdoc. Material's ripple and
      // every other platform animation run on the main looper, not on Compose's test clock.
      org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        .idleFor(java.time.Duration.ofMillis(frameInterval.toLong()))
      elapsed += frameInterval
    }

    // Release anything still held. A capture that ends mid-press leaves the pointer down when the
    // composition is torn down, and a reader looping the file would see the component stuck in its
    // pressed state at the loop point with no release to explain it.
    //
    // Keyed on the pointer's actual state, not on whether timeline events remain: a script the
    // duration cap truncates *between* gestures has both a released pointer and pending events, and
    // an `up()` with nothing down is rejected by the injector — which would fail an otherwise
    // complete recording at the last step.
    if (pointerDown) {
      DialogWindowCapture.resolveCaptureRoot(rule).interaction.performTouchInput { up() }
    }

    if (frameFiles.isEmpty()) return false

    val crop =
      interactionCropSize(
        measured = IntSize(widestContent, tallestContent),
        wrapWidth = wrapWidth,
        wrapHeight = wrapHeight,
        frameSize = IntSize(widestFrame, tallestFrame),
      )
    val written =
      encodeInteractionFrames(
        frameFiles = frameFiles,
        outputFile = outputFile,
        format = interaction.format,
        frameIntervalMs = frameInterval,
        crop = crop,
        padArgb = padArgb,
        glimmerEnvironment = glimmerEnvironment,
      ) ?: return false

    System.err.println(
      "@InteractionPreview on '$previewId': ${interaction.gesture} × " +
        "${interaction.targets.size} target(s), encoded ${frameFiles.size} frame(s) over " +
        "${totalDuration}ms @ ${frameInterval}ms at ${crop.width}×${crop.height} → " +
        "${written.name}."
    )
    return true
  } finally {
    framesDir.deleteRecursively()
    onClockAdvanced(rule.mainClock.currentTime - clockBefore)
  }
}

/** The shared-script spelling of this gesture. */
internal fun InteractionGesture.toMotionGesture(): MotionGesture =
  when (this) {
    InteractionGesture.TAP -> MotionGesture.TAP
    InteractionGesture.PRESS_AND_HOLD -> MotionGesture.PRESS_AND_HOLD
  }

/**
 * Resolves each requested target index to a centre point in root coordinates.
 *
 * Indices address the composition's **clickable nodes in traversal order**, which is the order the
 * author wrote them — index 2 of a five-destination navigation bar is the third item in the source.
 * That is the right addressing scheme for a catalog because it is stable across density,
 * breakpoint, and theme (unlike pixel coordinates) and across locale (unlike matching on label
 * text, which this repo's 17 locales would invalidate).
 *
 * Every target is resolved **once**, up front, against the composition at rest. Re-resolving per
 * gesture would look more robust and be less so: a component that reflows as it responds — a
 * navigation bar whose selected item widens, a container mid-shape-morph — moves the node the
 * *next* index refers to, so a script written against the resting layout would silently start
 * hitting different things partway through the recording.
 */
private fun resolveInteractionTargets(
  rule: AndroidComposeTestRule<*, ComponentActivity>,
  targets: List<Int>,
  previewId: String,
): Map<Int, Offset> {
  val nodes: List<SemanticsNode> = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
  val requested = targets.distinct().sorted()
  val missing = requested.filter { it >= nodes.size }
  // Fail loudly rather than recording a gesture that landed on empty space. A recording of a
  // component not responding is indistinguishable from a component that cannot respond, and
  // answering that question is the artifact's entire job — so a script that has drifted out of
  // range has to stop the capture, not publish a plausible-looking file.
  check(missing.isEmpty()) {
    val advice =
      if (nodes.isEmpty()) {
        "Nothing in this preview is clickable, so there is no interaction to capture."
      } else {
        "Valid indices are 0..${nodes.size - 1} — check the `targets` on the annotation " +
          "against the composable's clickable children."
      }
    "@InteractionPreview on '$previewId': target index ${missing.joinToString()} out of range — " +
      "the preview has ${nodes.size} clickable node(s). $advice"
  }
  return requested.associateWith { index ->
    val bounds = nodes[index].boundsInRoot
    Offset(bounds.center.x, bounds.center.y)
  }
}

/**
 * The size every frame of the capture is normalised to: the measured content on a wrapped axis, the
 * captured frame on a fixed one, never larger than the frames actually produced.
 *
 * Mirrors the still path's [cropPngTopLeft] so a component's motion capture and its sibling PNG
 * come out the same size — which is what lets a catalog show one in place of the other without the
 * card resizing under the reader. Without it a Wear switch published its capture at the whole
 * 454×454 round device beside a 217×136 still.
 *
 * [measured] is the **maximum** across the recording, not the resting measurement: a component that
 * expands mid-gesture — a menu opening, a card growing into its detail state — is bigger at frame
 * 90 than at frame 0, and cropping to the resting size would cut the expansion off exactly when it
 * becomes the thing worth looking at.
 */
internal fun interactionCropSize(
  measured: IntSize,
  wrapWidth: Boolean,
  wrapHeight: Boolean,
  frameSize: IntSize,
): IntSize =
  IntSize(
    (if (wrapWidth && measured.width > 0) measured.width else frameSize.width).coerceIn(
      1,
      frameSize.width.coerceAtLeast(1),
    ),
    (if (wrapHeight && measured.height > 0) measured.height else frameSize.height).coerceIn(
      1,
      frameSize.height.coerceAtLeast(1),
    ),
  )

/**
 * Normalises every frame to [crop] and encodes them into [outputFile]'s container.
 *
 * Every frame of a capture must be the same size — an APNG's frames share one `IHDR`, a GIF's share
 * one logical screen — but a wrap-measured recording does not hand them over that way. A frame is
 * trimmed to [crop] from the top-left (where the still path takes its crop from) and any remainder
 * is filled with [padArgb], the backdrop the preview already composes against.
 *
 * Nothing is ever resampled. Scaling a frame up to the target would soften the component against
 * its own sharp sibling still, and — worse for what these captures document — a component that
 * expands would appear to *shrink* as the canvas grew around it, which is the opposite of the
 * motion being recorded.
 *
 * Returns `null` when the encoder declined, so the caller reports the slot as unfilled rather than
 * leaving a truncated file behind.
 */
private fun encodeInteractionFrames(
  frameFiles: List<File>,
  outputFile: File,
  format: MotionFormat,
  frameIntervalMs: Int,
  crop: IntSize,
  padArgb: Int,
  glimmerEnvironment: ConnectorGlimmerEnvironment?,
): File? {
  outputFile.parentFile?.mkdirs()
  // Normalised in place, one frame at a time: the APNG encoder copies each frame's `IDAT` through
  // verbatim, so a frame already at the crop size never gets decoded at all — which is every frame
  // of the fixed-size case and nearly every frame of the wrapped one.
  frameFiles.forEach { file -> padOrTrimFramePng(file, crop, padArgb) }

  if (glimmerEnvironment != null) {
    // Glimmer is captured as ordinary opaque RGB on black; only once the complete raw frame exists
    // does the connector ADD-composite the selected world. The still path does this post-capture on
    // the PNG, but that pass is `.png`-only — a motion container has to composite per frame before
    // encoding, exactly as the animated and focus-GIF handlers do, and preserve the raw capture
    // beside the output.
    val raw =
      outputFile.resolveSibling("${outputFile.nameWithoutExtension}.raw.${outputFile.extension}")
    encodeFrameFiles(frameFiles, raw, format, frameIntervalMs) ?: return null
    frameFiles.forEach { file ->
      val composited =
        GlimmerEnvironmentCompositor.composite(
          FramePngReader.decode(file, role = "interaction"),
          glimmerEnvironment,
        )
      ImageIO.write(composited, "PNG", file)
    }
  }

  return encodeFrameFiles(frameFiles, outputFile, format, frameIntervalMs)
}

/** Stitches already-normalised [frameFiles] into [out]'s container. */
private fun encodeFrameFiles(
  frameFiles: List<File>,
  out: File,
  format: MotionFormat,
  frameIntervalMs: Int,
): File? =
  when (format) {
    MotionFormat.APNG -> {
      val (num, den) = apngDelayFor(frameIntervalMs)
      ApngEncoder.encodeFromPngFrames(
        frames = frameFiles,
        delayNumerator = num,
        delayDenominator = den,
        loopCount = 0,
        out = out,
      )
      out
    }
    MotionFormat.GIF ->
      ScrollGifEncoder.encode(
        frames = frameFiles.map { FramePngReader.decode(it, role = "interaction") },
        outputFile = out,
        frameDelaysMs = IntArray(frameFiles.size) { frameIntervalMs },
      )
  }

/** Rewrites [file] at exactly [target], or leaves it untouched when it already is. */
private fun padOrTrimFramePng(file: File, target: IntSize, padArgb: Int) {
  val decoded = ImageIO.read(file) ?: return
  if (decoded.width == target.width && decoded.height == target.height) return
  ImageIO.write(padOrTrimFrame(decoded, target, padArgb), "PNG", file)
}

/** [BufferedImage] counterpart of [padOrTrimFramePng], anchored top-left. */
private fun padOrTrimFrame(image: BufferedImage, target: IntSize, padArgb: Int): BufferedImage {
  val w = target.width.coerceAtLeast(1)
  val h = target.height.coerceAtLeast(1)
  if (w == image.width && h == image.height) return image
  val canvas = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
  val g = canvas.createGraphics()
  try {
    if (padArgb ushr 24 != 0) {
      g.color = java.awt.Color(padArgb, true)
      g.fillRect(0, 0, w, h)
    }
    // `getSubimage` returns a view sharing the parent's raster; drawing it into the canvas copies
    // the pixels out, so nothing pins a full-size backing raster past this call.
    val visible = image.getSubimage(0, 0, minOf(w, image.width), minOf(h, image.height))
    g.drawImage(visible, 0, 0, null)
  } finally {
    g.dispose()
  }
  return canvas
}
