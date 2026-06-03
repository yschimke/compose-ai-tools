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
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MainTestClock
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.scroll.DEFAULT_LONG_SCROLL_STEP_FRACTION
import ee.schimke.composeai.scroll.HOLD_END_MS
import ee.schimke.composeai.scroll.HOLD_START_MS
import ee.schimke.composeai.scroll.ScrollAxis
import ee.schimke.composeai.scroll.ScrollGifEncoder
import ee.schimke.composeai.scroll.SliceCapture
import ee.schimke.composeai.scroll.buildGifScrollScript
import ee.schimke.composeai.scroll.stitchSlices
import java.io.File
import javax.imageio.ImageIO
import okio.FileSystem
import okio.Path.Companion.toPath
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage

/**
 * Mode driving the desktop scroll capture path. Mirrors the LONG / GIF subset of
 * `ee.schimke.composeai.discovery.ScrollMode` — TOP / END are still served by the default
 * single-frame [renderPreview] path in [DesktopRendererMain] because they don't need to leave the
 * existing [ImageComposeScene]-based pipeline.
 */
enum class DesktopScrollMode {
  LONG,
  GIF,
}

/**
 * Renders a `@ScrollingPreview(modes = [LONG, GIF])` capture on Compose Desktop using
 * `runComposeUiTest`.
 *
 * Why `runComposeUiTest` rather than [androidx.compose.ui.ImageComposeScene]: `ImageComposeScene`
 * is a barebones rendering surface — no `mainClock`, no semantic-node interactions.
 * `runComposeUiTest` (in `org.jetbrains.compose.ui:ui-test`) gives the renderer what the
 * Robolectric path on Android gets from `AndroidComposeTestRule`: paused virtual time (so
 * animations terminate deterministically), `SemanticsNodeInteractionsProvider.onAllNodes` for
 * finding the scrollable by axis-range key, and `captureToImage()` for per-frame PNGs.
 *
 * Scroll dispatching matches the Android driver in `:data-scroll-android` byte for byte —
 * `SemanticsActions.ScrollBy` invoked on the first node whose semantics declare the requested
 * axis-range, with `mainClock.advanceTimeBy(...)` between calls so `animateScrollBy` lands before
 * the next read of `range.value` / `range.maxValue`. The frame-planning + stitching + GIF-encoder
 * primitives are all pure JVM (in `:data-scroll-core`) and shared with the Android side.
 *
 * Returns `true` when [outputFile] was written; `false` for "no scrollable found" / "encoder
 * declined". Throws — propagated by the caller — when reflective composable invocation fails.
 */
@OptIn(ExperimentalTestApi::class)
fun renderScrollPreview(
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
  scrollMode: DesktopScrollMode,
  axis: ScrollAxis,
  maxScrollPx: Int,
  frameIntervalMs: Int,
  /**
   * Classloader used to resolve [className] (and any `@PreviewWrapper` provider). B2.0 — the daemon
   * (`:daemon:desktop`'s `RenderEngine.runScrollScenario`) loads user previews from a disposable
   * child classloader (CLASSLOADER.md), not the app classpath, so a bare `Class.forName(className)`
   * — which resolves against this module's own loader — would miss the freshly-recompiled preview
   * class. When non-null it's used for the reflective class lookup and threaded into the wrapper
   * resolution below. The standalone CLI / Gradle path (`DesktopRendererMain`) passes null and
   * keeps the app-classloader behaviour.
   */
  classLoader: ClassLoader? = null,
  fileSystem: FileSystem = SystemFileSystem,
): Boolean {
  val clazz =
    if (classLoader != null) Class.forName(className, true, classLoader)
    else Class.forName(className)
  val composableMethod =
    if (previewArgs.isEmpty()) clazz.getDeclaredComposableMethod(functionName)
    else findComposableMethodForScroll(clazz, functionName, previewArgs)

  val pseudolocale = ee.schimke.composeai.data.pseudolocale.Pseudolocale.fromTag(localeTag)
  var captured = false

  runSkikoComposeUiTest(
    size = Size(widthPx.toFloat(), heightPx.toFloat()),
    density = Density(density),
  ) {
    // `runSkikoComposeUiTest` (vs. the parameterless `runComposeUiTest`) sizes the underlying
    // SkikoComposeUiTest scene to the requested viewport. Without this the scene defaults to
    // 1024 × 768 and `LazyColumn` lays out items against that, yielding slices whose width is
    // the default scene width — not the user's `@Preview(widthDp/heightDp)`.
    //
    // `mainClock.autoAdvance = false` mirrors the Android renderer's paused-clock contract:
    // animations only progress when the renderer advances time itself, so infinite transitions
    // / fling decays can't hang capture.
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
            LocalInspectionMode provides true,
            androidx.compose.ui.platform.LocalLayoutDirection provides
              androidx.compose.ui.unit.LayoutDirection.Rtl,
          ) {
            inner()
          }
        } else {
          CompositionLocalProvider(LocalInspectionMode provides true) { inner() }
        }
      }
      baseProviders {
        val body: @Composable () -> Unit = {
          Box(modifier = Modifier.fillMaxSize().background(bgColor)) {
            InvokeScrollComposable(composableMethod, null, previewArgs)
          }
        }
        if (wrapperClassName != null) {
          InvokeScrollWrappedComposable(wrapperClassName, classLoader, body)
        } else {
          body()
        }
      }
    }

    // Two clock ticks so LaunchedEffect / first composition lays out before we probe semantics.
    // Mirrors the two scene.render() calls in the default DesktopRendererMain path.
    mainClock.advanceTimeByFrame()
    mainClock.advanceTimeByFrame()

    val host = ComposeUiTestScrollHost(this, mainClock)

    captured =
      when (scrollMode) {
        DesktopScrollMode.LONG ->
          captureLong(
            host = host,
            axis = axis,
            viewportPx = heightPx,
            density = density,
            maxScrollPx = maxScrollPx,
            outputFile = outputFile,
            fileSystem = fileSystem,
          )
        DesktopScrollMode.GIF ->
          captureGif(
            host = host,
            axis = axis,
            viewportPx = heightPx,
            density = density,
            maxScrollPx = maxScrollPx,
            frameIntervalMs = frameIntervalMs,
            outputFile = outputFile,
            fileSystem = fileSystem,
          )
      }
  }
  return captured
}

/**
 * Thin adapter pairing the ComposeUiTest's semantic-query surface with its paused main clock. Used
 * by the scroll-drive helpers below so the dispatch logic mirrors the Android driver's
 * `(rule.onAllNodes(...), rule.mainClock.advanceTimeBy(...))` shape one-to-one.
 */
@OptIn(ExperimentalTestApi::class)
private class ComposeUiTestScrollHost(
  val provider: SemanticsNodeInteractionsProvider,
  val mainClock: MainTestClock,
)

private const val DRIVE_ADVANCE_MS_PER_STEP = 250L
private const val DRIVE_MAX_ITERATIONS = 30
private const val SETTLED_EPSILON_PX = 0.5f
private const val POST_SCROLL_SETTLE_MS = 1000L

@OptIn(ExperimentalTestApi::class)
private fun captureLong(
  host: ComposeUiTestScrollHost,
  axis: ScrollAxis,
  viewportPx: Int,
  density: Float,
  maxScrollPx: Int,
  outputFile: File,
  fileSystem: FileSystem = SystemFileSystem,
): Boolean {
  val slicesDir = File(outputFile.parentFile, "${outputFile.nameWithoutExtension}_slices")
  slicesDir.deleteRecursively()
  slicesDir.mkdirs()
  val stepPx = viewportPx * DEFAULT_LONG_SCROLL_STEP_FRACTION

  val slices = mutableListOf<SliceCapture>()
  try {
    val firstScroll = remainingScrollPx(host, axis)
    if (firstScroll <= SETTLED_EPSILON_PX && !axisHasScrollable(host, axis)) {
      System.err.println(
        "@ScrollingPreview(LONG) on $outputFile: no scrollable composable — skipping."
      )
      return false
    }

    // Capture slice 0 at the unscrolled top.
    val initialFile = File(slicesDir, "slice_0.png")
    captureRootFrame(host, initialFile, fileSystem)
    slices += SliceCapture(scrolledLayoutPx = 0f, file = initialFile)

    val cap = if (maxScrollPx > 0) maxScrollPx.toFloat() else Float.POSITIVE_INFINITY
    var scrolledPx = 0f
    repeat(DRIVE_MAX_ITERATIONS) {
      val remaining = remainingScrollPx(host, axis)
      if (remaining <= SETTLED_EPSILON_PX) return@repeat
      val headroom = (cap - scrolledPx).coerceAtLeast(0f)
      if (headroom <= SETTLED_EPSILON_PX) return@repeat
      val step = minOf(stepPx, remaining, headroom)
      if (!performScroll(host, axis, step)) return@repeat
      scrolledPx += step
      val sliceFile = File(slicesDir, "slice_${slices.size}.png")
      captureRootFrame(host, sliceFile, fileSystem)
      slices += SliceCapture(scrolledLayoutPx = scrolledPx, file = sliceFile)
    }

    if (slices.size < 2) {
      // No actual scroll happened — the content fits within one viewport. Write the single
      // capture as-is so consumers see SOMETHING rather than a missing file.
      slices.firstOrNull()?.file?.copyTo(outputFile, overwrite = true)
      return outputFile.exists()
    }
    stitchSlices(slices, viewportLayoutPx = viewportPx, outputFile = outputFile) ?: return false
    System.err.println("@ScrollingPreview(LONG) on $outputFile: stitched ${slices.size} slices.")
    return true
  } finally {
    slicesDir.deleteRecursively()
  }
}

@OptIn(ExperimentalTestApi::class)
private fun captureGif(
  host: ComposeUiTestScrollHost,
  axis: ScrollAxis,
  viewportPx: Int,
  density: Float,
  maxScrollPx: Int,
  frameIntervalMs: Int,
  outputFile: File,
  fileSystem: FileSystem = SystemFileSystem,
): Boolean {
  val framesDir = File(outputFile.parentFile, "${outputFile.nameWithoutExtension}_gif_frames")
  framesDir.deleteRecursively()
  framesDir.mkdirs()

  val effectiveFrameInterval =
    if (frameIntervalMs > 0) frameIntervalMs else ScrollGifEncoder.DEFAULT_FRAME_DELAY_MS

  val frameFiles = mutableListOf<File>()
  val frameDelays = mutableListOf<Int>()

  fun captureFrame(delayMs: Int) {
    val frameFile = File(framesDir, "frame_${frameFiles.size}.png")
    captureRootFrame(host, frameFile, fileSystem)
    frameFiles += frameFile
    frameDelays += delayMs
  }

  try {
    val initialRemaining = remainingScrollPx(host, axis)
    if (initialRemaining <= SETTLED_EPSILON_PX && !axisHasScrollable(host, axis)) {
      System.err.println(
        "@ScrollingPreview(GIF) on $outputFile: no scrollable composable — skipping."
      )
      return false
    }

    val cap = if (maxScrollPx > 0) maxScrollPx.toFloat() else Float.POSITIVE_INFINITY
    val extentHint = minOf(initialRemaining, cap)

    // Hold-start: one long-dwell frame at scroll position 0 so the viewer can read the top
    // before motion begins.
    captureFrame(HOLD_START_MS)

    // Walk the scripted plan. Mirrors the Android `handleGifCapture` shape — slow ramp, fling
    // bursts with geometric decay, inter-fling dwells.
    val steps =
      buildGifScrollScript(
        contentExtentPxHint = extentHint,
        viewportPx = viewportPx.toFloat(),
        density = density,
        frameIntervalMs = effectiveFrameInterval,
      )

    var scrolledPx = 0f
    var scriptHitEnd = false
    for (step in steps) {
      if (step.scrollPx > 0f) {
        val headroom = (cap - scrolledPx).coerceAtLeast(0f)
        val target = minOf(step.scrollPx, headroom)
        if (target <= SETTLED_EPSILON_PX) {
          scriptHitEnd = true
          break
        }
        val actual = performScrollDelta(host, axis, target)
        if (actual <= SETTLED_EPSILON_PX) {
          scriptHitEnd = true
          break
        }
        scrolledPx += actual
      } else {
        // Inter-fling dwell — advance virtual time so animations mid-composition keep ticking
        // honestly across the hold, but no scroll.
        host.mainClock.advanceTimeBy(effectiveFrameInterval.toLong())
      }
      captureFrame(step.delayMs)
    }

    // Tail flings — LazyList reports maxValue progressively, so the upfront hint can under-cover.
    // Emit additional bursts against the live remaining until exhausted or capped.
    if (!scriptHitEnd) {
      val flingStepPx = 120f * density
      val flingMinPx = 12f * density
      val safetyCap = 200
      var safety = 0
      while (safety < safetyCap) {
        val liveRemaining = remainingScrollPx(host, axis)
        if (liveRemaining <= SETTLED_EPSILON_PX) break
        val headroom = (cap - scrolledPx).coerceAtLeast(0f)
        if (headroom <= SETTLED_EPSILON_PX) break
        var step = flingStepPx
        while (
          step >= flingMinPx &&
            remainingScrollPx(host, axis) > SETTLED_EPSILON_PX &&
            (cap - scrolledPx) > SETTLED_EPSILON_PX
        ) {
          val target = minOf(step, remainingScrollPx(host, axis), cap - scrolledPx)
          if (target <= SETTLED_EPSILON_PX) break
          val actual = performScrollDelta(host, axis, target)
          if (actual <= SETTLED_EPSILON_PX) break
          scrolledPx += actual
          captureFrame(effectiveFrameInterval)
          step *= 0.85f
          safety++
          if (safety >= safetyCap) break
        }
      }
    }

    // Settle: let any animations triggered by the scroll-end land before the final frame.
    val settleFrames = (POST_SCROLL_SETTLE_MS / 16L).toInt()
    repeat(settleFrames) { host.mainClock.advanceTimeByFrame() }

    // Hold-end: long dwell on the settled final frame.
    captureFrame(HOLD_END_MS)

    if (frameFiles.isEmpty()) return false

    val frames = frameFiles.map {
      ImageIO.read(fileSystem.read(it.path.toPath()) { readByteArray() }.inputStream())
        ?: error("Failed to read GIF frame PNG: $it")
    }
    val written =
      ScrollGifEncoder.encode(
        frames = frames,
        outputFile = outputFile,
        frameDelaysMs = frameDelays.toIntArray(),
      ) ?: return false
    System.err.println(
      "@ScrollingPreview(GIF) on $outputFile: encoded ${frames.size} frames → ${written.name}."
    )
    return true
  } finally {
    framesDir.deleteRecursively()
  }
}

@OptIn(ExperimentalTestApi::class)
private fun captureRootFrame(
  host: ComposeUiTestScrollHost,
  file: File,
  fileSystem: FileSystem = SystemFileSystem,
) {
  // SemanticsNodeInteractionsProvider.onRoot() returns the merged semantic root; captureToImage()
  // on it pulls an ImageBitmap of the rendered surface. ImageBitmap.asSkiaBitmap() exposes the
  // backing Skia bitmap, which we then encode as PNG (matching the default DesktopRendererMain
  // path that calls `scene.render().encodeToData(PNG)`).
  val bitmap = host.provider.onRoot().captureToImage()
  val skiaImage = SkiaImage.makeFromBitmap(bitmap.asSkiaBitmap())
  val pngData =
    skiaImage.encodeToData(EncodedImageFormat.PNG)
      ?: error("Failed to encode captured frame to PNG")
  try {
    file.parentFile?.mkdirs()
    fileSystem.write(file.path.toPath()) { write(pngData.bytes) }
  } finally {
    pngData.close()
    skiaImage.close()
  }
  // Defensive re-read to ensure the file is decodable by the stitcher's ImageIO path. Most CMP
  // bitmap formats decode fine — but on rare encoder oddities we want a hard failure here, not a
  // silent corrupt slice that the stitcher then chokes on with a confusing error.
  ImageIO.read(fileSystem.read(file.path.toPath()) { readByteArray() }.inputStream())
    ?: error("Captured frame written to $file but couldn't be decoded back")
}

@OptIn(ExperimentalTestApi::class)
private fun axisHasScrollable(host: ComposeUiTestScrollHost, axis: ScrollAxis): Boolean {
  val axisKey =
    when (axis) {
      ScrollAxis.VERTICAL -> SemanticsProperties.VerticalScrollAxisRange
      ScrollAxis.HORIZONTAL -> SemanticsProperties.HorizontalScrollAxisRange
    }
  return host.provider
    .onAllNodes(SemanticsMatcher.keyIsDefined(axisKey))
    .fetchSemanticsNodes()
    .isNotEmpty()
}

@OptIn(ExperimentalTestApi::class)
private fun remainingScrollPx(host: ComposeUiTestScrollHost, axis: ScrollAxis): Float {
  val axisKey =
    when (axis) {
      ScrollAxis.VERTICAL -> SemanticsProperties.VerticalScrollAxisRange
      ScrollAxis.HORIZONTAL -> SemanticsProperties.HorizontalScrollAxisRange
    }
  val nodes = host.provider.onAllNodes(SemanticsMatcher.keyIsDefined(axisKey)).fetchSemanticsNodes()
  val node = nodes.firstOrNull() ?: return 0f
  val range: ScrollAxisRange = node.config.getOrNull(axisKey) ?: return 0f
  return (range.maxValue() - range.value()).coerceAtLeast(0f)
}

@OptIn(ExperimentalTestApi::class)
private fun performScroll(
  host: ComposeUiTestScrollHost,
  axis: ScrollAxis,
  deltaPx: Float,
): Boolean = performScrollDelta(host, axis, deltaPx) > 0f

@OptIn(ExperimentalTestApi::class)
private fun performScrollDelta(
  host: ComposeUiTestScrollHost,
  axis: ScrollAxis,
  deltaPx: Float,
): Float {
  if (deltaPx <= 0f) return 0f
  val axisKey =
    when (axis) {
      ScrollAxis.VERTICAL -> SemanticsProperties.VerticalScrollAxisRange
      ScrollAxis.HORIZONTAL -> SemanticsProperties.HorizontalScrollAxisRange
    }
  val nodes = host.provider.onAllNodes(SemanticsMatcher.keyIsDefined(axisKey)).fetchSemanticsNodes()
  val node = nodes.firstOrNull() ?: return 0f
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
  host.mainClock.advanceTimeBy(DRIVE_ADVANCE_MS_PER_STEP)
  return step
}

@Composable
private fun InvokeScrollComposable(
  composableMethod: ComposableMethod,
  instance: Any?,
  previewArgs: List<Any?>,
) {
  composableMethod.invoke(currentComposer, instance, *previewArgs.toTypedArray())
}

@Composable
private fun InvokeScrollWrappedComposable(
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

private fun findComposableMethodForScroll(
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
