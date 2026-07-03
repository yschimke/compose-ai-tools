package ee.schimke.composeai.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.reflect.ComposableMethod
import androidx.compose.runtime.reflect.getDeclaredComposableMethod
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.scroll.ScrollGifEncoder
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage

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
  fontScale: Float = 1.0f,
  classLoader: ClassLoader? = null,
) {
  if (showCurves) {
    System.err.println(
      "@AnimatedPreview(showCurves = true) on ${outputFile.name}: the curve-strip overlay isn't " +
        "supported on the desktop backend yet — emitting a screenshot-only GIF."
    )
  }

  val clazz =
    if (classLoader != null) Class.forName(className, true, classLoader)
    else Class.forName(className)
  val composableMethod =
    if (previewArgs.isEmpty()) clazz.getDeclaredComposableMethod(functionName)
    else findComposableMethodForAnimation(clazz, functionName, previewArgs)

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

  val pseudolocale = ee.schimke.composeai.data.pseudolocale.Pseudolocale.fromTag(localeTag)
  val sceneDensity = Density(density, fontScale)

  val frames = mutableListOf<BufferedImage>()
  val delays = mutableListOf<Int>()

  runSkikoComposeUiTest(
    size = Size(widthPx.toFloat(), heightPx.toFloat()),
    density = sceneDensity,
  ) {
    mainClock.autoAdvance = false

    setContent {
      val bgColor =
        when {
          backgroundColor != 0L -> Color(backgroundColor.toInt())
          showBackground -> Color.White
          else -> Color.Transparent
        }
      val baseProviders: @Composable (@Composable () -> Unit) -> Unit = { inner ->
        if (pseudolocale?.isRtl == true) {
          CompositionLocalProvider(
            LocalInspectionMode provides false,
            LocalDensity provides sceneDensity,
            androidx.compose.ui.platform.LocalLayoutDirection provides
              androidx.compose.ui.unit.LayoutDirection.Rtl,
          ) {
            inner()
          }
        } else {
          CompositionLocalProvider(
            LocalInspectionMode provides false,
            LocalDensity provides sceneDensity,
          ) {
            inner()
          }
        }
      }
      baseProviders {
        val body: @Composable () -> Unit = {
          Box(modifier = Modifier.fillMaxSize().background(bgColor)) {
            InvokeAnimatedComposable(composableMethod, null, previewArgs)
          }
        }
        if (wrapperClassName != null) {
          InvokeAnimatedWrappedComposable(wrapperClassName, classLoader, body)
        } else {
          body()
        }
      }
    }

    // One tick so first composition + layout land before the first capture; the animation phase at
    // frame 0 is then read at (near) its start value.
    mainClock.advanceTimeByFrame()

    repeat(frameCount) {
      frames += captureRootBufferedImage()
      delays += frameInterval
      mainClock.advanceTimeBy(frameInterval.toLong())
    }
  }

  val written =
    ScrollGifEncoder.encode(
      frames = frames,
      outputFile = outputFile,
      frameDelaysMs = delays.toIntArray(),
    )
      ?: throw IllegalStateException(
        "@AnimatedPreview: GIF encoder declined for ${outputFile.name}"
      )
  System.err.println(
    "@AnimatedPreview on ${written.name}: encoded ${frames.size} frame(s) over ${totalDuration}ms " +
      "@ ${frameInterval}ms."
  )
}

/**
 * Captures the rendered root as a [BufferedImage] via Skia PNG round-trip — same path
 * [DesktopScrollRenderer]'s `captureRootFrame` uses, but in-memory (no temp slice files needed for
 * a fixed-position animation capture).
 */
@OptIn(ExperimentalTestApi::class)
private fun androidx.compose.ui.test.SkikoComposeUiTest.captureRootBufferedImage(): BufferedImage {
  val bitmap = onRoot().captureToImage()
  val skiaImage = SkiaImage.makeFromBitmap(bitmap.asSkiaBitmap())
  try {
    val pngData =
      skiaImage.encodeToData(EncodedImageFormat.PNG)
        ?: error("Failed to encode animation frame to PNG")
    try {
      return ImageIO.read(pngData.bytes.inputStream())
        ?: error("Animation frame PNG couldn't be decoded back to a BufferedImage")
    } finally {
      pngData.close()
    }
  } finally {
    skiaImage.close()
  }
}

@Composable
private fun InvokeAnimatedComposable(
  composableMethod: ComposableMethod,
  instance: Any?,
  previewArgs: List<Any?>,
) {
  composableMethod.invoke(currentComposer, instance, *previewArgs.toTypedArray())
}

@Composable
private fun InvokeAnimatedWrappedComposable(
  wrapperFqn: String,
  classLoader: ClassLoader?,
  body: @Composable () -> Unit,
) {
  val resolved =
    androidx.compose.runtime.remember(wrapperFqn, classLoader) {
      val cls =
        if (classLoader != null) Class.forName(wrapperFqn, true, classLoader)
        else Class.forName(wrapperFqn)
      val instance = cls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
      val method = cls.getDeclaredComposableMethod("Wrap", Function2::class.java)
      method to instance
    }
  resolved.first.invoke(currentComposer, resolved.second, body)
}

private fun findComposableMethodForAnimation(
  clazz: Class<*>,
  name: String,
  previewArgs: List<Any?>,
): ComposableMethod {
  val argCount = previewArgs.size
  val candidate =
    clazz.declaredMethods.firstOrNull { m -> m.name == name && m.parameterCount >= argCount + 2 }
      ?: throw NoSuchMethodException(
        "Couldn't find composable method $name on ${clazz.name} taking $argCount parameter(s)"
      )
  val declaredTypes = candidate.parameterTypes.take(argCount).toTypedArray()
  return clazz.getDeclaredComposableMethod(name, *declaredTypes)
}
