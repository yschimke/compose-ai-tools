package ee.schimke.composeai.renderer

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import java.io.File

/**
 * Hard cap on the captured window, mirroring `@AnimatedPreview`'s 5000ms bound, to keep GIFs sane.
 */
private const val MAX_ANIMATION_DURATION_MS = 5000

/**
 * Fallback per-frame interval when the caller passes `0` (≈30fps), matching the annotation default.
 */
private const val DEFAULT_ANIMATION_FRAME_INTERVAL_MS = 33

/**
 * Window used when `durationMs == 0` (the annotation's auto-detect sentinel). The Android renderer
 * answers auto-detect by asking `PreviewAnimationClock` how long the discovered animations run and
 * falls back to 1500ms when nothing was measured; the desktop test harness doesn't expose that
 * inspection surface (see the [renderAnimatedPreview] note on [showCurves]), so auto-detect here
 * always resolves to the same fallback the Android path uses when measurement comes up empty.
 * Matches `DEFAULT_ANIMATION_DURATION_MS` in the `preview-annotations` KDoc and Android's
 * `AUTO_DURATION_FALLBACK_MS`.
 */
private const val AUTO_DURATION_FALLBACK_MS = 1500

/**
 * Renders an `@AnimatedPreview` capture on Compose Desktop as an animated GIF — the desktop
 * counterpart of the Android renderer's paused-clock animation path.
 *
 * Like [renderScrollPreview], this leaves the barebones [androidx.compose.ui.ImageComposeScene]
 * behind for `runSkikoComposeUiTest`, the one desktop surface that gives the renderer a
 * `mainClock`. With `mainClock.autoAdvance = false` the composition's animations (an
 * `InfiniteTransition`, a `LaunchedEffect` driving `withFrameNanos`, a tween) only progress when
 * the renderer advances time itself — so the capture is deterministic and infinite animations can't
 * hang it. The loop advances `mainClock` by [frameIntervalMs] across [durationMs], capturing the
 * root each step, then hands the frames to the shared [ScrollGifEncoder].
 *
 * Unlike the scroll path there's no "no scrollable found" decline — any composable produces frames
 * (a static one just yields identical frames) — so this always writes [outputFile] or throws.
 *
 * [durationMs] follows the annotation contract: a positive value is the explicit window; `0` (the
 * auto-detect sentinel, and the annotation default) captures [AUTO_DURATION_FALLBACK_MS] because
 * this backend can't measure the discovered animations — see the constant's KDoc.
 *
 * `LocalInspectionMode` is provided as `false` here (the scroll / single-frame paths use `true`):
 * some components short-circuit their animations when they detect preview/inspection mode, and an
 * animation capture specifically wants them to tick. This mirrors the Android `@AnimatedPreview`
 * renderer, which also drops out of inspection mode so animations actually run.
 *
 * [showCurves] is accepted for parity with the annotation but not yet honoured on desktop — the
 * Android curve-strip overlay leans on `PreviewAnimationClock` / ui-tooling animation inspection
 * that the desktop test harness doesn't expose. When `true` the renderer logs a note and emits the
 * screenshot-only GIF.
 */
@OptIn(ExperimentalTestApi::class)
fun renderAnimatedPreview(
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
  durationMs: Int,
  frameIntervalMs: Int,
  showCurves: Boolean,
  format: MotionFormatKind = MotionFormatKind.GIF,
  uiMode: Int = 0,
  fontScale: Float = 1.0f,
  wrapWidth: Boolean = false,
  wrapHeight: Boolean = false,
  sizeBounds: PreviewSizeBounds = PreviewSizeBounds(),
  classLoader: ClassLoader? = null,
) {
  if (showCurves) {
    System.err.println(
      "@AnimatedPreview(showCurves = true) on ${outputFile.name}: the curve-strip overlay isn't " +
        "supported on the desktop backend yet — emitting a screenshot-only GIF."
    )
  }

  val composableMethod = resolveMotionComposable(className, functionName, previewArgs, classLoader)

  val frameInterval =
    if (frameIntervalMs > 0) frameIntervalMs else DEFAULT_ANIMATION_FRAME_INTERVAL_MS
  // `0` is the annotation's auto-detect sentinel. Desktop can't measure the animation (no
  // PreviewAnimationClock inspection on this harness), so it takes the same fallback window the
  // Android path uses when measurement finds nothing — a real multi-frame GIF rather than the
  // single PNG frame this case used to degrade to (issue #2190).
  val autoDetect = durationMs <= 0
  if (autoDetect) {
    System.err.println(
      "@AnimatedPreview(durationMs = 0) on ${outputFile.name}: duration auto-detection isn't " +
        "supported on the desktop backend — capturing the ${AUTO_DURATION_FALLBACK_MS}ms fallback " +
        "window (set an explicit durationMs to override)."
    )
  }
  val requestedDuration = if (autoDetect) AUTO_DURATION_FALLBACK_MS else durationMs
  val totalDuration = requestedDuration.coerceIn(frameInterval, MAX_ANIMATION_DURATION_MS)
  val frameCount = (totalDuration / frameInterval).coerceAtLeast(1)

  // `ar-XB` and every real RTL locale (`ar`, `he`, `fa`, …) mirror the captured frames — see
  // [rendersRightToLeft].
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
      format = format,
      frameIntervalMs = frameInterval,
      padArgb = bgColor.toArgb(),
    ) { collector, forcedCrop ->
      val bounds = MotionBoundsTracker()
      var crop: IntSize = forcedCrop ?: sceneSize

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

        // One tick so first composition + layout land before the first capture; the animation phase
        // at frame 0 is then read at (near) its start value, and the wrap box has reported the
        // resting size the frames are cropped to.
        mainClock.advanceTimeByFrame()

        if (forcedCrop == null) {
          crop = motionCropSize(bounds.size, wrapWidth, wrapHeight, widthPx, heightPx, sceneSize)
        }

        repeat(frameCount) {
          collector.capture(captureRootPngBytes(), crop)
          mainClock.advanceTimeBy(frameInterval.toLong())
        }
      }

      MotionPass(crop = crop, observed = bounds.size)
    }

  System.err.println(
    "@AnimatedPreview on ${result.file.name}: encoded ${result.frameCount} frame(s) over " +
      "${totalDuration}ms @ ${frameInterval}ms at ${result.crop.width}×${result.crop.height} " +
      "(${format.name.lowercase()}" +
      (if (result.reRecorded) ", re-recorded for growth" else "") +
      ")."
  )
}
