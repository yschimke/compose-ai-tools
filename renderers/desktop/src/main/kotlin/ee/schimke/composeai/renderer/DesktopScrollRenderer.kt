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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
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
 * Mode driving the desktop scroll capture path. Mirrors the driven subset of
 * `ee.schimke.composeai.discovery.ScrollMode`.
 *
 * `TOP` is absent on purpose: it *is* the undriven first viewport, which the default single-frame
 * [renderPreview] path in [DesktopRendererMain] already produces, so routing it here would only
 * cost a second composition. `END` needs the drive and so lives here, even though — unlike LONG /
 * GIF — its output is an ordinary single-frame PNG rather than a data product.
 */
enum class DesktopScrollMode {
  LONG,
  GIF,
  END,
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
   * `@Preview(fontScale = ...)`. Carried on `Density.fontScale` — applied to the test scene density
   * and re-provided as `LocalDensity` so scrolled text previews honour the scale, matching the
   * single-frame [renderPreview] path. `1.0f` is the no-op default.
   */
  fontScale: Float = 1.0f,
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

  // `ar-XB` and every real RTL locale (`ar`, `he`, `fa`, …) mirror the captured slices — see
  // [rendersRightToLeft].
  val rtl = rendersRightToLeft(localeTag)
  var captured = false

  val sceneDensity = Density(density, fontScale)
  runSkikoComposeUiTest(
    size = Size(widthPx.toFloat(), heightPx.toFloat()),
    density = sceneDensity,
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
        if (rtl) {
          CompositionLocalProvider(
            LocalInspectionMode provides true,
            LocalDensity provides sceneDensity,
            androidx.compose.ui.platform.LocalLayoutDirection provides
              androidx.compose.ui.unit.LayoutDirection.Rtl,
          ) {
            inner()
          }
        } else {
          CompositionLocalProvider(
            LocalInspectionMode provides true,
            LocalDensity provides sceneDensity,
          ) {
            inner()
          }
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
        DesktopScrollMode.END ->
          captureEnd(
            host = host,
            axis = axis,
            viewportPx = if (axis == ScrollAxis.HORIZONTAL) widthPx else heightPx,
            maxScrollPx = maxScrollPx,
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

/**
 * Viewports [captureGif]'s script plans for up front. The real extent isn't knowable before the
 * scroll runs, so the script covers a few screens and the tail-fling loop continues from there
 * until the content stops moving.
 */
private const val GIF_PLANNED_VIEWPORTS = 4

/**
 * Virtual frames [captureEnd] lets an animated `ScrollBy` run for before it re-reads the axis
 * range. ~0.5 s, comfortably past Compose's default scroll animation, so each iteration measures a
 * landed scroll rather than one still in flight.
 */
private const val END_STEP_SETTLE_FRAMES = 32

/**
 * Iteration bound for [captureEnd]'s drive. Higher than [DRIVE_MAX_ITERATIONS] because a
 * `LazyColumn` only firms up its estimated extent as items compose, so reaching a long list's true
 * end takes several rounds of "jump to the end I can currently see". The loop exits on the first
 * iteration that makes no progress, so this is a runaway guard rather than the usual exit.
 */
private const val END_DRIVE_MAX_ITERATIONS = 60

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
    if (!axisHasScrollable(host, axis)) {
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
    for (iteration in 0 until DRIVE_MAX_ITERATIONS) {
      val headroom = (cap - scrolledPx).coerceAtLeast(0f)
      if (headroom <= SETTLED_EPSILON_PX) break
      // A viewport-relative stride in *pixels*, which is the unit `ScrollBy` speaks. Deliberately
      // not clamped by the axis range — see [ScrollAnchor] for why that number can't size a step.
      val step = minOf(stepPx, headroom)
      val anchor = scrollAnchor(host, axis)
      if (!performScroll(host, axis, step)) break
      // What the content actually did. Short of the content end this equals `step`; on the last
      // stride the scroller clamps and it is less, which is what keeps the final slice from being
      // stitched below where it really sits. A recycled anchor means the step landed in full (the
      // node travelled far enough to be discarded), so fall back to the requested distance.
      val advanced = anchor?.let { anchorShiftPx(host, axis, it) } ?: step
      if (advanced <= SETTLED_EPSILON_PX) break
      scrolledPx += advanced
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

/**
 * `@ScrollingPreview(modes = [END])` — the scrollable driven to its content end, then one frame.
 *
 * Unlike [captureLong] / [captureGif] this writes an ordinary preview PNG, so it is the one scroll
 * mode whose product is the sticker itself. That is what makes it worth driving rather than
 * approximating: the bottom-anchored chrome a screen reveals only once its list settles — a Wear
 * `ScreenScaffold`'s `EdgeButton`, a "load more" footer, a scroll-linked app bar — is simply absent
 * from the resting-top capture the desktop renderer produced before.
 *
 * The drive is [captureLong]'s loop with the per-slice capture removed: step by the remaining
 * distance, bounded by [DRIVE_MAX_ITERATIONS] and by the annotation's `maxScrollPx` cap, until the
 * axis range reports nothing left. Stepping rather than one big jump keeps a `LazyColumn` composing
 * the items it passes, so item-count-dependent layout settles the same way it does under a real
 * fling.
 *
 * Then [POST_SCROLL_SETTLE_MS] of virtual time before the shot, for the same reason the GIF path
 * settles before its final frame: chrome that animates *in response to* the scroll landing would
 * otherwise be caught mid-reveal.
 *
 * Returns `false` when the axis carries no scrollable — the caller falls back to the undriven frame
 * rather than failing, since an END capture of a non-scrolling screen is exactly its top.
 */
@OptIn(ExperimentalTestApi::class)
private fun captureEnd(
  host: ComposeUiTestScrollHost,
  axis: ScrollAxis,
  viewportPx: Int,
  maxScrollPx: Int,
  outputFile: File,
  fileSystem: FileSystem = SystemFileSystem,
): Boolean {
  if (!axisHasScrollable(host, axis)) {
    System.err.println(
      "@ScrollingPreview(END) on $outputFile: no scrollable composable on axis ${axis.name} — " +
        "capturing the initial frame."
    )
    return false
  }

  val cap = if (maxScrollPx > 0) maxScrollPx.toFloat() else Float.POSITIVE_INFINITY
  var scrolledPx = 0f
  for (step in 0 until END_DRIVE_MAX_ITERATIONS) {
    val headroom = (cap - scrolledPx).coerceAtLeast(0f)
    if (headroom <= SETTLED_EPSILON_PX) break

    val anchor = scrollAnchor(host, axis)
    // A viewport-sized stride rather than one jump to a claimed end: the axis range can't tell us
    // where the end is (see [ScrollAnchor]), and stepping keeps a lazy container composing the
    // items it passes, which is how its extent firms up at all.
    performScrollDelta(host, axis, minOf(viewportPx.toFloat(), headroom))
    // `SemanticsActions.ScrollBy` animates and [performScrollDelta] only advances a fixed slice of
    // virtual time. Let the animation land, or the next iteration measures a scroll still in
    // flight and the drive creeps instead of converging.
    repeat(END_STEP_SETTLE_FRAMES) { host.mainClock.advanceTimeByFrame() }

    val advanced = anchor?.let { anchorShiftPx(host, axis, it) } ?: viewportPx.toFloat()
    if (advanced <= SETTLED_EPSILON_PX) break
    scrolledPx += advanced
  }

  val settleFrames = (POST_SCROLL_SETTLE_MS / 16L).toInt()
  repeat(settleFrames) { host.mainClock.advanceTimeByFrame() }

  captureRootFrame(host, outputFile, fileSystem)
  System.err.println(
    "@ScrollingPreview(END) on $outputFile: scrolled ${scrolledPx.toInt()}px to the content end."
  )
  return true
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
    if (!axisHasScrollable(host, axis)) {
      System.err.println(
        "@ScrollingPreview(GIF) on $outputFile: no scrollable composable — skipping."
      )
      return false
    }

    val cap = if (maxScrollPx > 0) maxScrollPx.toFloat() else Float.POSITIVE_INFINITY
    // How far the script should plan for. There is no honest extent to read up front — the axis
    // range is item-space on a lazy container (see [ScrollAnchor]) and zero until it measures — so
    // plan a few viewports and let the tail-fling loop below carry on until the content actually
    // stops moving. Under-planning is cheap; the tail covers it.
    val extentHint = minOf(viewportPx * GIF_PLANNED_VIEWPORTS.toFloat(), cap)

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
      // Driven on measured movement rather than the axis range: `maxValue` is item-space on a
      // lazy container (see [ScrollAnchor]), so "how much is left" isn't a question it can answer.
      // The bursts simply continue until a step moves nothing, which is the content end.
      while (safety < safetyCap) {
        val headroom = (cap - scrolledPx).coerceAtLeast(0f)
        if (headroom <= SETTLED_EPSILON_PX) break
        var step = flingStepPx
        var burstMoved = false
        while (step >= flingMinPx && (cap - scrolledPx) > SETTLED_EPSILON_PX) {
          val target = minOf(step, cap - scrolledPx)
          if (target <= SETTLED_EPSILON_PX) break
          val anchor = scrollAnchor(host, axis)
          if (performScrollDelta(host, axis, target) <= SETTLED_EPSILON_PX) break
          val advanced = anchor?.let { anchorShiftPx(host, axis, it) } ?: target
          if (advanced <= SETTLED_EPSILON_PX) break
          burstMoved = true
          scrolledPx += advanced
          captureFrame(effectiveFrameInterval)
          step *= 0.85f
          safety++
          if (safety >= safetyCap) break
        }
        if (!burstMoved) break
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

/**
 * A composed descendant of the scroller, pinned by semantics id, used to measure how far the
 * content actually moved between two frames.
 *
 * `ScrollAxisRange` cannot do this job, and treating it as though it could is what made long
 * captures wrong. It is not a pixel budget: a `LazyColumn` publishes `maxValue` in item space (a
 * flat `100.0` for the 30-row fixture) and a `DateRangePicker` publishes `0.0` until its months
 * measure, while `SemanticsActions.ScrollBy` takes *pixels*. Sizing a step as `min(stepPx,
 * maxValue - value)` therefore turned a 336 px stride into a ~2 px one and stitched thirteen slices
 * into a single-viewport image.
 *
 * Geometry doesn't lie: a node that is composed both before and after a step moved by exactly the
 * distance the content scrolled.
 */
private data class ScrollAnchor(val id: Int, val edgePx: Float)

/**
 * Picks an anchor from the nodes currently inside the scroller — the one furthest along [axis],
 * i.e. nearest the leading edge the content scrolls *towards*.
 *
 * That choice is what keeps the anchor alive across a step: a lazy container recycles what leaves
 * the viewport, so a node at the trailing edge is the first to be discarded, while the leading-edge
 * one is still composed after an 80%-viewport stride (and, past the end, doesn't move at all —
 * which is exactly the signal the drive stops on).
 */
@OptIn(ExperimentalTestApi::class)
private fun scrollAnchor(host: ComposeUiTestScrollHost, axis: ScrollAxis): ScrollAnchor? {
  val axisKey =
    when (axis) {
      ScrollAxis.VERTICAL -> SemanticsProperties.VerticalScrollAxisRange
      ScrollAxis.HORIZONTAL -> SemanticsProperties.HorizontalScrollAxisRange
    }
  val scroller =
    host.provider
      .onAllNodes(SemanticsMatcher.keyIsDefined(axisKey))
      .fetchSemanticsNodes()
      .firstOrNull() ?: return null
  var best: ScrollAnchor? = null
  fun edgeOf(node: SemanticsNode): Float =
    if (axis == ScrollAxis.HORIZONTAL) node.boundsInRoot.left else node.boundsInRoot.top
  fun walk(node: SemanticsNode) {
    val edge = edgeOf(node)
    if (edge.isFinite() && (best == null || edge > best!!.edgePx)) {
      best = ScrollAnchor(id = node.id, edgePx = edge)
    }
    node.children.forEach(::walk)
  }
  scroller.children.forEach(::walk)
  return best
}

/**
 * How far the content moved since [anchor] was taken, or `null` when that node is no longer
 * composed (recycled out) and the distance can't be measured.
 */
@OptIn(ExperimentalTestApi::class)
private fun anchorShiftPx(
  host: ComposeUiTestScrollHost,
  axis: ScrollAxis,
  anchor: ScrollAnchor,
): Float? {
  val axisKey =
    when (axis) {
      ScrollAxis.VERTICAL -> SemanticsProperties.VerticalScrollAxisRange
      ScrollAxis.HORIZONTAL -> SemanticsProperties.HorizontalScrollAxisRange
    }
  val scroller =
    host.provider
      .onAllNodes(SemanticsMatcher.keyIsDefined(axisKey))
      .fetchSemanticsNodes()
      .firstOrNull() ?: return null
  var found: Float? = null
  fun walk(node: SemanticsNode) {
    if (node.id == anchor.id) {
      found = if (axis == ScrollAxis.HORIZONTAL) node.boundsInRoot.left else node.boundsInRoot.top
    }
    node.children.forEach(::walk)
  }
  scroller.children.forEach(::walk)
  return found?.let { anchor.edgePx - it }
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
  val scrollByAction = node.config.getOrNull(SemanticsActions.ScrollBy)?.action ?: return 0f
  // Dispatch the full requested distance and let the scroller clamp itself at its content end.
  //
  // This used to be clamped to `maxValue - value` first, which looks defensive and is in fact the
  // bug that made every long capture wrong: those are not pixels. A `LazyColumn` publishes
  // `maxValue` in item space — a flat `100.0` for a 30-row list — so a 336 px stride was clamped
  // to a couple of pixels, thirteen slices stitched into a single-viewport image, and a
  // `DateRangePicker` (which publishes `0.0` until its months measure) refused to scroll at all.
  // See [ScrollAnchor]: distance travelled is a question for geometry, not for the axis range.
  val (dx, dy) =
    when (axis) {
      ScrollAxis.VERTICAL -> 0f to deltaPx
      ScrollAxis.HORIZONTAL -> deltaPx to 0f
    }
  scrollByAction.invoke(dx, dy)
  host.mainClock.advanceTimeBy(DRIVE_ADVANCE_MS_PER_STEP)
  return deltaPx
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
