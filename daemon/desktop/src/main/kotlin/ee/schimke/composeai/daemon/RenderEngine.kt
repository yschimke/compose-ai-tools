package ee.schimke.composeai.daemon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.reflect.ComposableMethod
import androidx.compose.runtime.reflect.getDeclaredComposableMethod
import androidx.compose.runtime.remember
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.LocalInspectionTables
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.SystemTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.daemon.devices.DeviceDimensions
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.layoutinspector.ComposeFigmaSvgProduct
import ee.schimke.composeai.data.render.PreviewBackends
import ee.schimke.composeai.data.render.PreviewContext
import ee.schimke.composeai.data.render.PreviewDeviceSpec
import ee.schimke.composeai.data.render.extensions.compose.ComposeDataExtensionPipeline
import ee.schimke.composeai.data.render.extensions.compose.RecordingExtensionCompositionSink
import ee.schimke.composeai.data.render.extensions.loadPreviewWrapperClass
import ee.schimke.composeai.data.theme.ThemePayload
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.preview.lottie.LottiePreview
import java.io.File
import java.util.Base64
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import org.jetbrains.skia.EncodedImageFormat

/**
 * Compose-Desktop render body for the preview daemon — the per-preview inner loop that turns a
 * resolved class+method reference into a PNG on disk.
 *
 * **Duplicated from
 * [`renderer-desktop`'s `DesktopRendererMain`](../../../../../../../renderers/desktop/src/main/kotlin/ee/schimke/composeai/renderer/DesktopRendererMain.kt).**
 * Per
 * [DESIGN.md § 7](../../../../../../docs/daemon/DESIGN.md#7-sharing-strategy--what-crosses-the-boundary)
 * the v1 render body lives in two places — the standalone renderer (existing CLI / Gradle path) and
 * the daemon — so the daemon doesn't depend on the renderer's `main()`-only entry point. v2's
 * reconciliation extracts the body into a shared helper. Until then any change to the render body
 * landed here also has to land in `:renderer-desktop`'s `renderPreview` (and vice versa); the bench
 * + CI pixel-diff (D2.2 / harness S1) will catch divergence.
 *
 * **What's duplicated, what isn't.** This is the "small composable, no PreviewParameter, no
 * wrapper" subset — the daemon's v1 surface only renders single previews from existing
 * `previews.json` discovery. The fan-out / `@PreviewParameter` / `@PreviewWrapper` paths from
 * `DesktopRendererMain` stay behind the standalone renderer for now; B-desktop.1.7+ revisits if the
 * harness needs them.
 *
 * **Threading contract.** Called from [DesktopHost]'s single render thread. [ImageComposeScene] is
 * instantiated *per render* (not held warm across renders) — see
 * [DESIGN.md § 9](../../../../../../docs/daemon/DESIGN.md#9-sandbox-lifecycle--no-mid-render-cancellation).
 * The "warm runtime" the daemon amortises here is the JVM + JIT + Skiko native bundle, not the
 * scene itself. Holding `ImageComposeScene` across renders would require restoring its content tree
 * between previews; the per-render construction cost is dominated by Skiko's `Surface` allocation,
 * which is cheap once the JVM has been through it once.
 *
 * **No-mid-render-cancellation invariant** (DESIGN § 9). [ImageComposeScene.close] is invoked from
 * a `try/finally` so the underlying Skia `Surface` always releases, even when the render body
 * throws. This is the desktop equivalent of Android's `bitmap.recycle()` discipline; without the
 * `finally`, a thrown render leaks one Skia `Surface` per submission until the JVM exits.
 */
class RenderEngine(
  /**
   * Directory under which PNG files are written. Defaults to the `composeai.render.outputDir`
   * system property (mirrors the Android side's contract); falls back to
   * `${user.dir}/.compose-preview-history/daemon-renders/` so unit tests don't need to set the
   * property.
   */
  private val outputDir: File =
    File(
      System.getProperty(OUTPUT_DIR_PROP)
        ?: "${System.getProperty("user.dir")}/.compose-preview-history/daemon-renders"
    ),
  private val dataDir: File = (outputDir.parentFile ?: outputDir).resolve("data"),
  private val previewContextCapture: PreviewContextCapture? = null,
  private val previewOverrideExtensions: PreviewOverrideExtensions =
    PreviewOverrideExtensions.Empty,
  private val frameNanoTime: () -> Long = System::nanoTime,
  private val fileSystem: FileSystem = SystemFileSystem,
) {

  /**
   * Renders one preview to a PNG on disk and returns a [RenderResult] populated with the absolute
   * `pngPath` and a `metrics` map containing `tookMs` (wall-clock of the render body, excluding
   * queue wait).
   *
   * @param spec what to render — class FQN, method name, sandbox dimensions.
   * @param requestId opaque id forwarded to the [RenderResult] so [DesktopHost]'s queue can demux.
   * @param classLoader classloader used to resolve [spec]'s class. B2.0 — see
   *   [CLASSLOADER.md](../../../../../../docs/daemon/CLASSLOADER.md). Pass the disposable child
   *   loader from [UserClassLoaderHolder.currentChildLoader] so a recompiled `Foo.kt`'s fresh
   *   bytecode is read on the next render. Defaults to the engine's own classloader for
   *   backward-compatible callers (unit tests that pre-load fixtures into the host classloader).
   */
  fun render(
    spec: RenderSpec,
    requestId: Long,
    classLoader: ClassLoader =
      RenderEngine::class.java.classLoader ?: ClassLoader.getSystemClassLoader(),
    /**
     * B2.3 — per-host measurement context. The host owns its own [SandboxLifecycleStats] (start
     * time + render counter); the engine simply reads from it at metrics-population time and bumps
     * the counter once per render. Defaults to a fresh per-call instance for unit tests that drive
     * the engine directly without a host wrapper — the resulting metrics are still populated, just
     * with a sandbox-age that resets on every test render.
     */
    sandboxStats: SandboxLifecycleStats = SandboxLifecycleStats(),
  ): RenderResult {
    // Issue #1604 — scroll-scenario dispatch. When the dispatcher's `data/fetch` re-render path
    // queues `mode=scroll-long` / `scroll-gif` (because the `ScrollDataProductRegistry` advertised
    // the kind as `requiresRerender = true` and the artefact was missing), leave the single-frame
    // `ImageComposeScene` path and drive `:renderer-desktop`'s `renderScrollPreview` instead. It
    // writes the stitched PNG (LONG) / animated GIF (GIF) to the same on-disk path the registry
    // (`ScrollDataProductRegistry.fileFor`) reads back, so the dispatcher's second fetch sees the
    // file and emits `Ok(path)`. Mirrors `:daemon:android`'s `RenderEngine.runScrollScenario`.
    if (spec.renderMode == SCROLL_LONG_RENDER_MODE || spec.renderMode == SCROLL_GIF_RENDER_MODE) {
      return runScrollScenario(spec = spec, requestId = requestId, classLoader = classLoader)
    }
    // Full-page figma-svg for a scrolling preview (`compose/figma-svg-long`). Renders at an
    // expanded
    // viewport so a virtualised LazyColumn composes every item, then emits the layered SVG over the
    // whole content instead of just the on-screen rows. SVG-only — the PNG scroll story stays LONG
    // /
    // GIF above. See docs/design/SCROLLING_SVG.md.
    if (spec.renderMode == FIGMA_SVG_LONG_RENDER_MODE) {
      return runScrollSvgScenario(spec = spec, requestId = requestId, classLoader = classLoader)
    }
    // kind=LOTTIE animated capture — sweep the asset's intrinsic timeline into a looping GIF
    // instead of capturing one still frame. Routed here (before the reflection-based setUp) because
    // a Lottie asset has no class to resolve; the render body lives in `:renderer-desktop`'s
    // `renderLottieGif`, mirroring how `runScrollScenario` delegates to `renderScrollPreview`.
    if (spec.kind == "LOTTIE" && spec.renderMode == LOTTIE_GIF_RENDER_MODE) {
      return runLottieGifScenario(spec = spec, requestId = requestId, classLoader = classLoader)
    }
    val trace = PerfettoTraceDataProducer.recorder(spec.outputBaseName, backend = "desktop")
    val state =
      trace.section("compose:setUp") {
        setUp(spec, classLoader, inspectionMode = spec.inspectionMode ?: true, trace = trace)
      }
    try {
      return trace.section("render:once") {
        renderOnce(state, requestId, sandboxStats = sandboxStats, trace = trace)
      }
    } finally {
      trace.section("compose:tearDown") { tearDown(state) }
      trace.write(dataDir)
    }
  }

  /**
   * v2 phase 1 of the render pipeline — see
   * [INTERACTIVE.md § 9](../../../../../../docs/daemon/INTERACTIVE.md#9-v2--click-dispatch-into-composition).
   *
   * Resolves the preview's class via [classLoader], allocates a fresh [ImageComposeScene] sized per
   * [spec], installs the context classloader, and seeds the composition. Does NOT render — call
   * [renderOnce] for that.
   *
   * **Two callers, two lifetimes.** The one-shot [render] wrapper calls setUp / renderOnce /
   * tearDown back-to-back; [DesktopInteractiveSession] calls setUp once at `interactive/start`,
   * renderOnce per `interactive/input`, tearDown at `interactive/stop`. Holding the scene across
   * inputs is what lets `remember { mutableStateOf(...) }` survive between clicks — the v2 payoff.
   *
   * **`inspectionMode = true`** for the one-shot path matches v1 behaviour: previews that branch on
   * `LocalInspectionMode.current` (e.g. to use stub data instead of network calls) hit the
   * inspection branch. Interactive sessions pass `false` so `pointerInput` modifiers fire and the
   * preview shows its real, click-aware behaviour.
   */
  @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
  fun setUp(
    spec: RenderSpec,
    classLoader: ClassLoader =
      RenderEngine::class.java.classLoader ?: ClassLoader.getSystemClassLoader(),
    inspectionMode: Boolean = true,
    trace: PerfettoTraceDataProducer.Recorder =
      PerfettoTraceDataProducer.recorder(spec.outputBaseName, backend = "desktop"),
  ): SceneState {
    outputDir.mkdirs()
    val outputFile = File(outputDir, "${spec.outputBaseName}.png")

    // kind=LOTTIE has no class to reflect — the asset is rendered directly via Compottie below.
    val isLottie = spec.kind == "LOTTIE"
    val composableMethod: ComposableMethod? =
      if (isLottie) {
        null
      } else {
        val clazz = Class.forName(spec.className, true, classLoader)
        clazz.getDeclaredComposableMethod(spec.functionName).also {
          // Kotlin `private fun` previews compile to JVM-private methods.
          // `getDeclaredComposableMethod` still resolves them (it scans `declaredMethods`), but the
          // reflective `invoke` in [InvokeComposable] would throw IllegalAccessException, so open
          // the method up first — mirrors `:renderer-android`'s ComposePreviewStrategy. Guarded
          // with
          // `runCatching`: a SecurityManager or strong module encapsulation can refuse, in which
          // case we still attempt the invoke (which succeeds for public/internal previews) rather
          // than failing resolution outright.
          runCatching { it.asMethod().isAccessible = true }
        }
      }

    // Self-diagnostic — surfaces in the VS Code extension's output channel as `[daemon stderr] …`.
    // Pairs with `[classloader] swap requested` / `allocate child loader` lines from
    // [UserClassLoaderHolder]. If `classFile` doesn't advance across saves the daemon is
    // re-rendering against bytecode that wasn't actually recompiled.
    if (isLottie) {
      System.err.println(
        "compose-ai-daemon: [setUp] lottie asset '${spec.assetPath}' " +
          "loaderId=${System.identityHashCode(classLoader).toString(16)}"
      )
    } else {
      val fingerprint =
        UserClassLoaderHolder.classFileFingerprint(classLoader, spec.className)
          ?: "fingerprint unavailable (class not on a file: URL)"
      System.err.println(
        "compose-ai-daemon: [setUp] ${spec.className}#${spec.functionName} " +
          "loaderId=${System.identityHashCode(classLoader).toString(16)} classFile=$fingerprint " +
          "inspectionMode=$inspectionMode"
      )
    }

    // Install the child classloader as the context classloader for the duration the scene is
    // alive (one render for the wrapper path, many renders for the interactive path). Compose's
    // reflection paths (notably PreviewParameter providers — see CLASSLOADER.md § Risks 2)
    // consult the context classloader; without this install they would miss user classes that
    // aren't on the parent's classpath. Restored in [tearDown].
    val previousContext = Thread.currentThread().contextClassLoader
    Thread.currentThread().contextClassLoader = classLoader

    val localeProviders = localeProviders(spec.localeTag)
    val themeFallbackCapture =
      if (previewContextCapture?.shouldCapture(spec.previewId, spec.renderMode) == true) {
        MaterialThemeFallbackCapture()
      } else {
        null
      }
    val slotTableCapture =
      if (previewContextCapture?.shouldCapture(spec.previewId, spec.renderMode) == true) {
        PreviewSlotTableCapture()
      } else {
        null
      }

    // PROTOCOL.md § 5 (`renderNow.overrides.fontScale`) — Compose Desktop has no resource-qualifier
    // system, but `LocalDensity` carries `fontScale` as part of `Density`, and `Text` / `TextStyle`
    // honour it. Threading through `ImageComposeScene`'s constructor makes the override visible to
    // layout (sp → px conversion) before the first measure pass. We re-provide the same `Density`
    // as `LocalDensity` below since a few ui-text/text-foundation paths read it directly during
    // composition rather than going through the scene density.
    val density = Density(spec.density, spec.fontScale ?: 1.0f)

    // Wrap-content measured size (width, height), written by the wrap `Modifier.layout` pass during
    // render and read by renderOnce to crop the PNG to the composable's intrinsic size. 0 = unset.
    val measuredContent = IntArray(2)

    // Resolve the effective Lottie timeline position and hold it in snapshot state so a held
    // session can live-scrub it. An explicit `overrides.lottie.progress` (a panel scrub) wins and
    // is remembered per preview; a render with no override (a save / warmup re-render through the
    // fresh-scene path) re-uses the last scrub so the captured frame — and the slider — stay pinned
    // at the scrubbed position. `LocalLottieProgress` reads `.value` inside the composition below,
    // so [DesktopInteractiveSession.dispatchLottieProgress] mutating this state recomposes the held
    // scene without a fresh setUp — the held-scene scrub path. See [LottieProgressController].
    val lottieProgressState: MutableState<Float?> =
      mutableStateOf(
        spec.overrides?.lottie?.progress?.also { p ->
          spec.previewId?.let { LottieProgressController.remember(it, p) }
        } ?: spec.previewId?.let { LottieProgressController.progressFor(it) }
      )
    val scene =
      try {
        ImageComposeScene(width = spec.widthPx, height = spec.heightPx, density = density)
      } catch (t: Throwable) {
        // Ensure we don't leave the context classloader installed if scene allocation fails before
        // the SceneState is even handed back to the caller (caller never gets a chance to call
        // tearDown in that case).
        Thread.currentThread().contextClassLoader = previousContext
        throw t
      }
    try {
      trace.section("compose:setContent") {
        scene.setContent {
          // PROTOCOL.md § 5 (`renderNow.overrides.uiMode`) — Compose Desktop's
          // `isSystemInDarkTheme()` reads `LocalSystemTheme.current` (foundation-desktop's
          // `DarkTheme.skiko.kt`). Override it here so `uiMode = "dark"` actually flips dark-aware
          // composables instead of falling through to the JVM's `org.jetbrains.skiko.SystemTheme`
          // probe.
          val systemTheme =
            when (spec.uiMode) {
              RenderSpec.SpecUiMode.DARK -> SystemTheme.Dark
              RenderSpec.SpecUiMode.LIGHT -> SystemTheme.Light
              null -> SystemTheme.Unknown
            }
          CompositionLocalProvider(
            LocalInspectionMode provides inspectionMode,
            // Slot mode: a `PreviewSlot` marker renders a labelled placeholder instead of its
            // content, so a structured-screen builder gets a visible slot map. Defaults false.
            ee.schimke.composeai.preview.slots.LocalSlotMode provides (spec.slotMode ?: false),
            // Cleared background ("crisp outline"): a composable drawing its own opaque fill drops
            // it to match the transparent harness background below. Defaults false.
            ee.schimke.composeai.preview.slots.LocalPreviewBackgroundCleared provides
              spec.clearBackground,
            androidx.compose.ui.LocalSystemTheme provides systemTheme,
            LocalDensity provides density,
            // Interactive Lottie scrubbing: a non-null progress lands the captured frame at that
            // timeline position, winning over the composable's authored progress (file-discovered
            // `LottiePreview` below, or any `@Preview` calling it). Read from snapshot state so a
            // held session's `dispatchLottieProgress` recomposes live; sticky across fresh renders
            // via [LottieProgressController] so an unrelated re-render keeps the scrubbed frame.
            ee.schimke.composeai.preview.lottie.LocalLottieProgress provides
              lottieProgressState.value,
            *localeProviders,
          ) {
            if (previewContextCapture?.shouldCapture(spec.previewId, spec.renderMode) == true) {
              CaptureMaterialTheme { _, typography, shapes, payload ->
                themeFallbackCapture?.capture(typography, shapes)
                themeFallbackCapture?.capture(payload)
              }
            }
            val content: @Composable () -> Unit = {
              val bgColor =
                when {
                  spec.clearBackground -> Color.Transparent
                  spec.backgroundColor != 0L -> Color(spec.backgroundColor.toInt())
                  spec.showBackground -> Color.White
                  else -> Color.Transparent
                }
              // AS-parity wrap: on a wrapped axis, measure the composable with a relaxed (min = 0)
              // constraint against the sandbox max and size the box to the child's intrinsic size,
              // so the captured tree (and the figma-svg / wireframe / semantics derived from it)
              // reflects the preview's natural size instead of a fixed frame that clips or reflows
              // wide content. `.background` paints on that intrinsic box so the sticker's backdrop
              // is
              // content-sized, not sandbox-sized. Fixed axes keep the sandbox constraint so
              // `fillMax*` / LazyColumn still have a finite viewport (mirrors DesktopRendererMain).
              val boxModifier =
                if (spec.wrapWidth || spec.wrapHeight) {
                  Modifier.layout { measurable, constraints ->
                      val wrapped =
                        androidx.compose.ui.unit.Constraints(
                          minWidth = if (spec.wrapWidth) 0 else constraints.minWidth,
                          maxWidth = constraints.maxWidth,
                          minHeight = if (spec.wrapHeight) 0 else constraints.minHeight,
                          maxHeight = constraints.maxHeight,
                        )
                      val placeable = measurable.measure(wrapped)
                      // Record the intrinsic size so renderOnce can crop the PNG to it on wrapped
                      // axes — otherwise a no-size preview's frame is the whole sandbox with
                      // content
                      // in the corner, not the natural-size capture (matches DesktopRendererMain).
                      measuredContent[0] = placeable.width
                      measuredContent[1] = placeable.height
                      layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
                    .background(bgColor)
                } else {
                  Modifier.fillMaxSize().background(bgColor)
                }
              Box(modifier = boxModifier) {
                ComposeDataExtensionPipeline.Apply(
                  extensions = previewOverrideExtensions.plan(spec.overrides),
                  previewId = spec.previewId,
                  renderMode = spec.renderMode,
                  sink = RecordingExtensionCompositionSink(),
                ) {
                  // Trampoline through a @Composable so the reflective invocation lands inside the
                  // running composition. Mirrors `:renderer-desktop`'s InvokeComposable. Honours
                  // `@PreviewWrapper(SomeProvider::class)` by routing through the wrapper's `Wrap`.
                  if (isLottie) {
                    // Drive the file-discovered Lottie through the draw-time `progress` lambda
                    // reading the snapshot state, so a held-session scrub (mutating
                    // `lottieProgressState`) repaints on the next `render()` by redraw alone — no
                    // recomposition required. This is the same redraw-only path `renderLottieGif`
                    // uses to sweep a single held scene into GIF frames. Shadow
                    // `LocalLottieProgress`
                    // to null here so the overload's draw-time `progress()` wins; the outer provide
                    // still targets user `@Preview`s that call `LottiePreview` themselves.
                    CompositionLocalProvider(
                      ee.schimke.composeai.preview.lottie.LocalLottieProgress provides null
                    ) {
                      LottiePreview(
                        asset = spec.assetPath.orEmpty(),
                        modifier = Modifier.fillMaxSize(),
                      ) {
                        lottieProgressState.value ?: 0f
                      }
                    }
                  } else {
                    InvokeWithOptionalWrapper(
                      composableMethod!!,
                      spec.wrapperClassName,
                      // A `themeProvider` override (an app-declared @ThemeCatalog
                      // `PreviewWrapperProvider` FQN) replaces the preview's own `@PreviewWrapper`
                      // —
                      // "render this preview under theme X" — but only when it resolves; a
                      // stale/misspelled FQN falls back to the declared wrapper.
                      themeProviderFqn = spec.overrides?.themeProvider,
                    )
                  }
                }
              }
            }
            // `@Preview(showSystemUi = true)` (issue #1930) — wrap the composition in the synthetic
            // [ee.schimke.composeai.renderer.SystemBarsFrame] so the daemon's desktop capture draws
            // the same Android phone chrome (status bar + gesture-nav pill) the Android renderer
            // and
            // the standalone `:renderer-desktop` path do, instead of a chrome-less surface. Dark
            // chrome follows the resolved [RenderSpec.uiMode]; skipped for round/Wear devices.
            val framed: @Composable () -> Unit = {
              if (
                ee.schimke.composeai.renderer.shouldApplySystemBars(
                  showSystemUi = spec.showSystemUi,
                  device = spec.device,
                  kind = spec.kind,
                )
              ) {
                ee.schimke.composeai.renderer.SystemBarsFrame(
                  // 0x20 == Configuration.UI_MODE_NIGHT_YES — the only bit SystemBarsFrame
                  // inspects.
                  uiMode = if (spec.uiMode == RenderSpec.SpecUiMode.DARK) 0x20 else 0
                ) {
                  content()
                }
              } else {
                content()
              }
            }
            if (slotTableCapture != null) {
              InspectablePreviewContent(slotTableCapture, framed)
            } else {
              framed()
            }
          }
        }
      }
    } catch (t: Throwable) {
      // setContent threw — close the scene to avoid leaking the Skia surface and restore the
      // context classloader before propagating.
      try {
        scene.close()
      } finally {
        Thread.currentThread().contextClassLoader = previousContext
      }
      throw t
    }
    return SceneState(
      spec = spec,
      classLoader = classLoader,
      scene = scene,
      density = density,
      outputFile = outputFile,
      previousContext = previousContext,
      slotTableCapture = slotTableCapture,
      themeFallbackCapture = themeFallbackCapture,
      lottieProgressState = lottieProgressState,
      measuredContent = measuredContent,
    )
  }

  internal fun currentFrameNanoTime(): Long = frameNanoTime()

  /**
   * v2 phase 2 — drive the held scene through enough frames to settle (two `scene.render()` calls,
   * same heuristic as the one-shot path) and encode the latest pixels to PNG. Reusable across
   * inputs in the interactive path; called exactly once by the [render] wrapper.
   *
   * [ImageComposeScene.render] defaults `nanoTime` to zero, and that timestamp is the frame clock
   * seen by `withFrameNanos` / Compose animations. The one-shot preview path keeps that old
   * deterministic frame-zero behavior, while live interactive preview passes monotonic wall-clock
   * timestamps so animations advance at real elapsed time instead of repainting a frozen timeline.
   */
  @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
  fun renderOnce(
    state: SceneState,
    requestId: Long,
    sandboxStats: SandboxLifecycleStats = SandboxLifecycleStats(),
    trace: PerfettoTraceDataProducer.Recorder =
      PerfettoTraceDataProducer.recorder(state.spec.outputBaseName, backend = "desktop"),
    useWallClockFrameTime: Boolean = false,
  ): RenderResult {
    val startNs = System.nanoTime()

    // Flush snapshot state written out-of-composition since the last render so the held scene
    // observes it before painting. The held-session scrub path mutates `lottieProgressState` from
    // the scene thread *between* renderOnce calls
    // (DesktopInteractiveSession.dispatchLottieProgress);
    // the file-Lottie content reads it in its draw-time `progress` lambda, but that read only
    // invalidates once apply-notifications fire. Same pairing `:renderers:desktop`'s
    // `renderLottieGif`
    // uses (`sendApplyNotifications()` after each write, before `render()`). Event-driven writes
    // (clicks) are flushed by the scene's own pointer processing; this covers the out-of-band
    // write.
    // Harmless for the one-shot path — nothing is pending right after setUp.
    androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()

    // Render two frames so any LaunchedEffect / animations have a tick to settle. Same reasoning
    // as `:renderer-desktop`'s renderPreview.
    trace.section("compose:frame") { renderFrame(state, useWallClockFrameTime) }
    val rawImage =
      trace.section("compose:captureFrame") { renderFrame(state, useWallClockFrameTime) }
    // AS-parity wrap crop: a no-size preview measured smaller than the sandbox, so crop the frame
    // to
    // the composable's intrinsic size on wrapped axes (content is placed at 0,0). Everything
    // downstream — the written PNG, renderFinished's pngPath, the figma-svg's frame crop — then
    // sees
    // the natural-size capture instead of the sandbox with the content in the corner.
    val image = cropToMeasured(rawImage, state)
    val previewContext = state.previewContext()

    val pngData =
      trace.section("render:encodePng") {
        image.encodeToData(EncodedImageFormat.PNG)
          ?: error(
            "Failed to encode image to PNG for ${state.spec.className}.${state.spec.functionName}"
          )
      }

    state.outputFile.parentFile?.mkdirs()
    trace.section("render:writePng") {
      fileSystem.write(state.outputFile.path.toPath()) { write(pngData.bytes) }
    }

    // Display filters — post-capture colour-matrix variants (grayscale/bedtime, invert,
    // daltonizer simulations). Gated on `composeai.displayfilter.filters` being non-empty so the
    // default render path stays free. Wrapped in try/catch so a filter failure does not strand
    // the PNG.
    val displayFilters = DisplayFilterConfig.fromSystemProperties()
    if (displayFilters.isNotEmpty()) {
      try {
        trace.section("displayfilter:variants") {
          DisplayFilterDataProducer.writeArtifacts(
            rootDir = dataDir,
            previewId = state.spec.outputBaseName,
            pngFile = state.outputFile,
            filters = displayFilters,
          )
        }
      } catch (t: Throwable) {
        System.err.println(
          "RenderEngine: displayfilter write failed for ${state.spec.outputBaseName}: " +
            "${t.javaClass.simpleName}: ${t.message}"
        )
      }
    }

    // Compose semantics (`compose/semantics` JSON sidecar) + wireframe — both derive from the held
    // scene's unmerged semantics root, matching the unmerged tree the Android producer uses so both
    // backends emit the same data. Always-on (requiresRerender = false) like the Android
    // post-capture extension, and wrapped in try/catch so a walk/bake failure never strands the
    // outputs.
    try {
      val root = state.scene.semanticsOwners.firstOrNull()?.unmergedRootSemanticsNode
      if (root != null) {
        trace.section("wireframe") {
          val previewId = state.spec.previewId ?: state.spec.outputBaseName
          // Pass the render density so percent-based corner radii (`CircleShape`) resolve to dp
          // instead of dropping out (#1908). dp-valued tokens (padding/gap/colours) don't use it.
          val density = state.spec.density
          val payload = ComposeSemanticsDataProducer.buildPayload(root, density)
          // `compose-semantics.json` — the plain `compose/semantics` data product the Android
          // ComposeSemanticsExtension writes per render. The desktop backend previously fed this
          // tree only into the wireframe / spatial / a11y views and never wrote the sidecar, so
          // `bundle pack --with-semantics` and design-parity found no semantics on desktop (#1885
          // follow-up). Write it from the same captured root.
          ComposeSemanticsDataProducer.writeArtifacts(
            rootDir = dataDir,
            previewId = previewId,
            root = root,
            density = density,
          )
          // `layout-inspector.json` — the `layout/inspector` data product. Previously Android-only:
          // the desktop registry advertised the kind but nothing wrote the file, so `data/fetch`
          // degraded to `NotAvailable` (#1903). Write it from the same captured root + slot tables,
          // through the CMP-portable `LayoutInspectorDataProducer` overload. This is the canonical
          // home for the modifier-derived design `tokens` the producer now resolves once and
          // mirrors
          // onto `compose/semantics`. The Z-sorted child walk is valid here because
          // `scene.render()` has already measured + drawn (and thus Z-sorted) the layout tree.
          LayoutInspectorDataProducer.writeArtifacts(
            rootDir = dataDir,
            previewId = previewId,
            root = root,
            slotTables = state.slotTableCapture?.snapshot().orEmpty(),
            density = density,
          )
          ComposeSemanticsWireframeDataProducer.writeSvg(
            rootDir = dataDir,
            previewId = previewId,
            payload = payload,
          )
          DesktopSemanticsWireframe.generate(
            payload = payload,
            destPng =
              dataDir.resolve(previewId).resolve(ComposeSemanticsWireframeDataProducer.FILE_PNG),
          )
          // Unified spatial-semantics tree (`compose/spatial-semantics`) — the degenerate
          // single-panel case for an ordinary preview: one `panel` at identity pose carrying this
          // same 2D tree. The XR batch render writes the real multi-panel tree to the same file.
          SpatialSemanticsDataProducer.writeSinglePanel(
            rootDir = dataDir,
            previewId = previewId,
            payload = payload,
          )
          // `compose/figma-svg` — the layered, editable SVG export (design fidelity, not the
          // schematic wireframe). Reuses the same captured root: the layout tree carries the
          // composable names + container tokens, the semantics `payload` carries editable text.
          LayoutInspectorDataProducer.buildPayload(
              root = root,
              slotTables = state.slotTableCapture?.snapshot().orEmpty(),
              density = density,
            )
            ?.let { layout ->
              ComposeFigmaSvgDataProducer.writeSvg(
                rootDir = dataDir,
                previewId = previewId,
                layout = layout,
                semantics = payload,
                density = density,
                // Hand the just-written frame PNG so opaque components (Image/Icon/Canvas/charts)
                // export as `<image>` layers backed by a real background-free crop of the frame.
                frameImage = state.outputFile,
                // Embed the real (Google-downloadable) face so `<text>` renders faithfully instead
                // of a substituted `sans-serif`. Opt-in; also on when fidelity is being measured so
                // the score reflects the embedded font. Reuses the renderer's own font cache dir.
                fontResolver = figmaFontResolver(),
              )
              // Fidelity harness (opt-in via -Dcomposeai.figma.fidelity=true): rasterise the SVG we
              // just wrote and score it against this render, dropping a `render | figma-svg | diff`
              // composite so drift in the vector export is measurable where the renderer runs.
              if (FigmaSvgFidelity.enabled()) {
                val previewDir = dataDir.resolve(previewId)
                FigmaSvgFidelity.write(
                  previewDir = previewDir,
                  svgFile = previewDir.resolve(ComposeFigmaSvgDataProducer.FILE_SVG),
                  renderPng = state.outputFile,
                )
              }
            }
        }
      }
    } catch (t: Throwable) {
      System.err.println(
        "RenderEngine: wireframe write failed for ${state.spec.outputBaseName}: " +
          "${t.javaClass.simpleName}: ${t.message}"
      )
    }

    // Accessibility (desktop, overlay-only) — extract Compose semantics from the held scene and
    // write the a11y artefacts (empty findings + node hierarchy + Paparazzi-style overlay PNG).
    // ATF is Android-only, so there are no findings here. Gated on `renderMode == "a11y"` so the
    // default render path stays free; the `a11y` data-product registry advertises
    // `requiresRerender`, so a `data/fetch` for an a11y kind queues a `mode=a11y` re-render which
    // lands here. Wrapped in try/catch so an extraction / draw failure never strands the PNG.
    if (state.spec.renderMode == "a11y") {
      try {
        trace.section("a11y:overlay") {
          val root = state.scene.semanticsOwners.firstOrNull()?.unmergedRootSemanticsNode
          val nodes =
            if (root != null) DesktopAccessibilityNodeExtractor.extractNodes(root) else emptyList()
          // Key the sidecar dir by the wire `previewId` the data-product registry reads back on
          // `data/fetch` (`fileFor(previewId, …)`). It resolves to the same dir as
          // `outputBaseName` for router-driven renders (`outputBaseName = outputBaseName ?: id`),
          // but using `previewId` directly keeps write-key and read-key aligned regardless of how
          // the spec was resolved. Falls back to `outputBaseName` for direct className payloads
          // that carry no `previewId` token.
          DesktopAccessibilityDataProducer.writeArtifacts(
            rootDir = dataDir,
            previewId = state.spec.previewId ?: state.spec.outputBaseName,
            nodes = nodes,
            pngFile = state.outputFile,
          )
        }
      } catch (t: Throwable) {
        System.err.println(
          "RenderEngine: a11y overlay write failed for ${state.spec.outputBaseName}: " +
            "${t.javaClass.simpleName}: ${t.message}"
        )
      }
    }

    val tookMs = (System.nanoTime() - startNs) / 1_000_000L
    val metrics = SandboxMeasurement.collect(sandboxStats, tookMs = tookMs)
    trace.write(dataDir)
    return RenderResult(
      id = requestId,
      classLoaderHashCode = System.identityHashCode(state.classLoader),
      classLoaderName = state.classLoader.javaClass.name,
      pngPath = state.outputFile.absolutePath,
      metrics = metrics,
      previewContext = previewContext,
    )
  }

  private fun renderFrame(state: SceneState, useWallClockFrameTime: Boolean) =
    if (useWallClockFrameTime) {
      state.scene.render(nanoTime = currentFrameNanoTime())
    } else {
      state.scene.render()
    }

  /**
   * Crop a wrap-content render to the composable's intrinsic size on wrapped axes (content is
   * placed at the top-left), so a no-size preview's frame is its natural size rather than the
   * sandbox with the content in the corner. Returns [raw] unchanged when the preview isn't wrapped,
   * the measured size wasn't recorded, or it already fills the axis (`fillMax*` composables).
   */
  private fun cropToMeasured(
    raw: org.jetbrains.skia.Image,
    state: SceneState,
  ): org.jetbrains.skia.Image {
    if (!state.spec.wrapWidth && !state.spec.wrapHeight) return raw
    val mw = state.measuredContent[0]
    val mh = state.measuredContent[1]
    val cropW = if (state.spec.wrapWidth && mw in 1 until raw.width) mw else raw.width
    val cropH = if (state.spec.wrapHeight && mh in 1 until raw.height) mh else raw.height
    if (cropW >= raw.width && cropH >= raw.height) return raw
    val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(cropW, cropH)
    return try {
      surface.canvas.drawImage(raw, 0f, 0f)
      surface.makeImageSnapshot()
    } finally {
      surface.close()
    }
  }

  /**
   * v2 phase 3 — close the held scene (frees the Skia [org.jetbrains.skia.Surface]) and restore the
   * context classloader to what it was before [setUp]. Idempotent: a second call after the scene
   * has been closed is a no-op (Skia tolerates double-close on its own; we still restore the
   * classloader unconditionally).
   *
   * **No-mid-render-cancellation invariant** (DESIGN § 9). When called from
   * [DesktopInteractiveSession.close] under daemon shutdown, callers are responsible for ensuring
   * no in-flight render is using [state] — the daemon's drain loop handles that on shutdown.
   */
  fun tearDown(state: SceneState) {
    try {
      state.scene.close()
    } finally {
      Thread.currentThread().contextClassLoader = state.previousContext
    }
  }

  /**
   * Held state for one [setUp] / [renderOnce] / [tearDown] cycle. Carries the resolved scene plus
   * the bookkeeping the engine needs to run renderOnce repeatedly and tear down cleanly. Public
   * because [DesktopInteractiveSession] holds one across `interactive/input` notifications;
   * one-shot [render] callers don't need to look at it.
   */
  class SceneState
  internal constructor(
    val spec: RenderSpec,
    val classLoader: ClassLoader,
    val scene: ImageComposeScene,
    val density: Density,
    val outputFile: File,
    internal val previousContext: ClassLoader?,
    internal val slotTableCapture: PreviewSlotTableCapture?,
    internal val themeFallbackCapture: MaterialThemeFallbackCapture?,
    /**
     * Snapshot-backed Lottie timeline position read by `LocalLottieProgress` inside the held
     * composition. Mutating it on the scene thread (via
     * [DesktopInteractiveSession.dispatchLottieProgress]) recomposes the scene to the new frame
     * without a fresh [setUp] — the live-scrub path. `null` for non-Lottie previews / renders that
     * never carried a progress.
     */
    internal val lottieProgressState: MutableState<Float?> = mutableStateOf(null),
    /**
     * Wrap-content measured size `[width, height]` in px, written by the wrap `Modifier.layout`
     * pass during render (0 = unset). renderOnce crops the encoded PNG to this on wrapped axes so a
     * no-size preview's frame is the composable's natural size, not the sandbox.
     */
    internal val measuredContent: IntArray = IntArray(2),
  ) {
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    internal fun previewContext(): PreviewContext {
      val slotTables = slotTableCapture?.snapshot().orEmpty()
      val rawContext =
        PreviewContext.Builder(
            previewId = spec.previewId,
            backend = PreviewBackends.DESKTOP,
            renderMode = spec.renderMode,
            outputBaseName = spec.outputBaseName,
          )
          .deviceFromRenderPixels(
            spec.device,
            spec.widthPx,
            spec.heightPx,
            spec.density,
            resolvedDevice = spec.device?.let(DeviceDimensions::resolve)?.previewDeviceSpec(),
          )
          .parameterInformationCollected()
          .addSlotTables(slotTables)
          .build()
      val materialThemePayload =
        themePayloadFromPreviewContext(
          context = rawContext,
          fallbackTypography = themeFallbackCapture?.typography,
          fallbackShapes = themeFallbackCapture?.shapes,
        ) ?: themeFallbackCapture?.payload
      val builder =
        PreviewContext.Builder(
            previewId = spec.previewId,
            backend = PreviewBackends.DESKTOP,
            renderMode = spec.renderMode,
            outputBaseName = spec.outputBaseName,
          )
          .deviceFromRenderPixels(
            spec.device,
            spec.widthPx,
            spec.heightPx,
            spec.density,
            resolvedDevice = spec.device?.let(DeviceDimensions::resolve)?.previewDeviceSpec(),
          )
          .parameterInformationCollected()
          .addSlotTables(slotTables)
      // Attribute theme consumers (#1847) here, while the held scene's semantics tree is still
      // valid — the one-shot render tears it down before the registry reads the payload back. Keyed
      // by SemanticsNode id, matching `compose/semantics`, against the same resolved tokens.
      val themePayload = materialThemePayload?.let { payload ->
        val consumers =
          ThemeConsumerCapture.consumersFor(
            root = scene.semanticsOwners.firstOrNull()?.unmergedRootSemanticsNode,
            resolved = payload.resolvedTokens,
          )
        if (consumers.isEmpty()) payload else payload.copy(consumers = consumers)
      }
      themePayload?.let { builder.putInspectionValue(MATERIAL3_THEME_PAYLOAD_CONTEXT_KEY, it) }
      return builder.build()
    }
  }

  /**
   * Issue #1604 — dispatch for `scroll-long` / `scroll-gif` render modes on CMP-desktop. Resolves
   * the `@ScrollingPreview` intent (axis / maxScrollPx / frameIntervalMs) from the daemon's
   * `previews.json` index via [PreviewIndex.scrollCaptureFor], then delegates to
   * `:renderer-desktop`'s [ee.schimke.composeai.renderer.renderScrollPreview] (a `runComposeUiTest`
   * capture that drives `SemanticsActions.ScrollBy`, stitches the LONG slices, or encodes the GIF).
   * The artefact lands at `<dataDir>/render-scroll-{long,gif}/<previewId>.{png,gif}` — the exact
   * path [ScrollDataProductRegistry.fileFor] resolves to — so the dispatcher's second fetch reads
   * it back. Returns a [RenderResult] whose `pngPath` points at the produced artefact.
   *
   * Throws [IllegalStateException] when the spec carries no `previewId`, when the preview has no
   * matching `dataProducts[].scroll` entry in `previews.json`, or when the renderer reports "no
   * scrollable on the requested axis" (returns `false`). The dispatcher surfaces each as
   * `ERR_DATA_PRODUCT_FETCH_FAILED` so the host can fall back to its Gradle path. Mirrors the
   * Android side's `runScrollScenario` one-to-one (same lookup, same on-disk layout, same error
   * contract) so consumer code stays platform-agnostic.
   */
  private fun runScrollScenario(
    spec: RenderSpec,
    requestId: Long,
    classLoader: ClassLoader,
  ): RenderResult {
    val previewId =
      spec.previewId
        ?: error(
          "RenderEngine: scroll mode '${spec.renderMode}' requires a previewId on the RenderSpec " +
            "so the scroll metadata can be resolved from the previews.json index"
        )
    val previewIndex = loadPreviewIndexLazily()
    val scroll =
      previewIndex.scrollCaptureFor(previewId, spec.renderMode!!)
        ?: error(
          "RenderEngine: scroll mode '${spec.renderMode}' requested for previewId '$previewId' " +
            "but the preview has no matching dataProducts[].scroll entry in previews.json — " +
            "the host should fall back to the Gradle composePreviewRenderAll path"
        )
    val (subdir, ext) =
      when (spec.renderMode) {
        SCROLL_LONG_RENDER_MODE -> ScrollDataProductRegistry.SCROLL_LONG_SUBDIR to "png"
        SCROLL_GIF_RENDER_MODE -> ScrollDataProductRegistry.SCROLL_GIF_SUBDIR to "gif"
        else -> error("RenderEngine: unreachable scroll mode '${spec.renderMode}'")
      }
    val outputFile = dataDir.resolve(subdir).resolve("$previewId.$ext")
    outputFile.parentFile?.mkdirs()

    val scrollMode =
      when (spec.renderMode) {
        SCROLL_LONG_RENDER_MODE -> ee.schimke.composeai.renderer.DesktopScrollMode.LONG
        else -> ee.schimke.composeai.renderer.DesktopScrollMode.GIF
      }
    val axis =
      when (scroll.axis.uppercase()) {
        "HORIZONTAL" -> ee.schimke.composeai.scroll.ScrollAxis.HORIZONTAL
        else -> ee.schimke.composeai.scroll.ScrollAxis.VERTICAL
      }

    val startNs = System.nanoTime()
    val handled =
      ee.schimke.composeai.renderer.renderScrollPreview(
        className = spec.className,
        functionName = spec.functionName,
        widthPx = spec.widthPx,
        heightPx = spec.heightPx,
        density = spec.density,
        showBackground = spec.showBackground,
        backgroundColor = spec.backgroundColor,
        outputFile = outputFile,
        wrapperClassName = spec.wrapperClassName,
        // v1 daemon renders the parameterless `@Preview` overload only — `@PreviewParameter`
        // fan-out stays behind the standalone renderer (see this file's class doc).
        previewArgs = emptyList(),
        localeTag = spec.localeTag,
        scrollMode = scrollMode,
        axis = axis,
        maxScrollPx = scroll.maxScrollPx,
        frameIntervalMs = scroll.frameIntervalMs,
        fontScale = spec.fontScale ?: 1.0f,
        classLoader = classLoader,
      )
    if (!handled) {
      error(
        "RenderEngine: scroll mode '${spec.renderMode}' returned false (no scrollable on " +
          "scroll.axis=${scroll.axis} for previewId '$previewId')"
      )
    }
    val tookMs = (System.nanoTime() - startNs) / 1_000_000L
    return RenderResult(
      id = requestId,
      classLoaderHashCode = System.identityHashCode(classLoader),
      classLoaderName = classLoader.javaClass.name,
      pngPath = outputFile.absolutePath,
      metrics = mapOf("tookMs" to tookMs),
    )
  }

  /**
   * `figma-svg-long` dispatch — the **full-page** layered SVG of a scrolling preview
   * (`compose/figma-svg-long`). A `LazyColumn`/`LazyRow` is virtualised, so the normal viewport
   * render composes only the on-screen rows and the figma-svg carries just those. This renders the
   * preview at a viewport grown until the scroll container reports nothing left to scroll (so every
   * item composes) and sized to the content, then reuses the ordinary figma-svg export over that
   * taller tree — producing one editable SVG of the whole screen (pinned top bar, all rows, pinned
   * bottom bar). SVG-only; the PNG scroll story stays LONG / GIF. See docs/design/SCROLLING_SVG.md.
   *
   * Sizing is **grow-by-remaining**: `VerticalScrollAxisRange.maxValue − value` is exactly the
   * content below the fold, which is exactly the extra content-area height needed to show it — and
   * because the top/bottom chrome are fixed height, adding it to the frame height adds it to the
   * content area, so a pinned bottom bar tucks directly under the last row. Bounded by an iteration
   * cap and a max extra-height so an infinite list can't grow the frame without limit.
   *
   * The final render writes into an **isolated** output base
   * (`<previewId>` + [SCROLL_SVG_TMP_SUFFIX]) so the tall render's `compose/semantics` / wireframe
   * / PNG never overwrite the preview's normal-size products; only the layered SVG is copied out to
   * the long product path (`<dataDir>/<previewId>/compose-figma-long.svg`) that
   * [ComposeFigmaSvgLongDataProductRegistry] reads back. A non-scrolling preview simply yields its
   * viewport SVG (nothing to grow).
   */
  private fun runScrollSvgScenario(
    spec: RenderSpec,
    requestId: Long,
    classLoader: ClassLoader,
  ): RenderResult {
    val previewId =
      spec.previewId
        ?: error(
          "RenderEngine: render mode '${spec.renderMode}' requires a previewId on the RenderSpec"
        )
    val startNs = System.nanoTime()

    val baseHeight = spec.heightPx
    val maxHeight = baseHeight + SCROLL_SVG_MAX_EXTRA_PX
    var probeHeight = baseHeight.coerceAtMost(maxHeight)
    var sizedHeight = baseHeight
    var prevContentBottom = -1
    var iterations = 0
    while (iterations < SCROLL_SVG_MAX_GROW_ITERATIONS) {
      iterations++
      val probe = spec.copy(renderMode = null, heightPx = probeHeight, wrapHeight = false)
      val state = setUp(probe, classLoader, inspectionMode = spec.inspectionMode ?: true)
      val measure =
        try {
          // Two frames so a first-composition LazyList lays out before we measure, mirroring the
          // two
          // settle frames the one-shot path renders.
          renderFrame(state, false)
          renderFrame(state, false)
          measureVerticalScroll(state.scene)
        } finally {
          tearDown(state)
        }
      // Not a scrolling preview: nothing to grow, the viewport SVG is the full page.
      if (measure == null) {
        sizedHeight = baseHeight
        break
      }
      // The chrome pinned below the list (e.g. a Scaffold bottom bar) — the gap between the scroll
      // container's bottom and the frame bottom. Sizing the frame to `content + this` tucks that
      // bar
      // directly under the last row.
      val bottomChrome = (probeHeight - measure.scrollNodeBottom).coerceAtLeast(0)
      sizedHeight =
        (measure.contentBottom + bottomChrome + SCROLL_SVG_CONTENT_MARGIN_PX).coerceIn(
          baseHeight,
          maxHeight,
        )
      // Fully composed once growing the viewport reveals no further content. (LazyList's semantic
      // scroll range is a coarse estimate, so we grow by measured *geometry* — the deepest composed
      // descendant of the scroll node — not by the reported remaining.)
      if (measure.contentBottom <= prevContentBottom) break
      prevContentBottom = measure.contentBottom
      if (probeHeight >= maxHeight) break
      // Grow with a whole base viewport of headroom so the next batch of items composes.
      probeHeight = (measure.contentBottom + bottomChrome + baseHeight).coerceAtMost(maxHeight)
    }
    val height = sizedHeight

    // Final render at the settled height into an isolated base so it can't clobber the preview's
    // normal-size products. `previewId = null` makes renderOnce key the figma-svg dir off the
    // (isolated) outputBaseName.
    val tmpBase = "$previewId$SCROLL_SVG_TMP_SUFFIX"
    val finalSpec =
      spec.copy(
        renderMode = null,
        heightPx = height,
        wrapHeight = false,
        previewId = null,
        outputBaseName = tmpBase,
      )
    val finalState = setUp(finalSpec, classLoader, inspectionMode = spec.inspectionMode ?: true)
    try {
      renderOnce(finalState, requestId)
    } finally {
      tearDown(finalState)
    }

    val tmpDir = dataDir.resolve(tmpBase)
    val producedSvg = tmpDir.resolve(ComposeFigmaSvgDataProducer.FILE_SVG)
    if (!producedSvg.exists()) {
      error(
        "RenderEngine: render mode '${spec.renderMode}' produced no layered SVG for previewId " +
          "'$previewId' (no layout tree captured?)"
      )
    }
    // The long export lives in its own subdir (SVG + its own figma-raster/ crops). A hybrid export
    // references per-node `figma-raster/<node>.png` crops, and Compose reassigns node ids per
    // render, so writing the tall render's crops next to the viewport export's would collide; the
    // dedicated subdir keeps each export's crops self-consistent, and the served SVG inlines them
    // relative to its own dir.
    val longDir =
      dataDir.resolve(previewId).resolve(ComposeFigmaSvgProduct.LONG_SUBDIR).also { it.mkdirs() }
    val destSvg = longDir.resolve(ComposeFigmaSvgProduct.FILE_SVG_LONG)
    val svgBytes = fileSystem.read(producedSvg.path.toPath()) { readByteArray() }
    fileSystem.write(destSvg.path.toPath()) { write(svgBytes) }
    // Carry the hybrid raster crops the SVG's `<image>` layers reference (Image/Icon/Canvas/… on a
    // scrolling screen) so those layers resolve instead of dangling.
    val tmpRasterDir = tmpDir.resolve(ComposeFigmaSvgDataProducer.RASTER_DIR)
    if (tmpRasterDir.isDirectory) {
      val destRasterDir =
        longDir.resolve(ComposeFigmaSvgDataProducer.RASTER_DIR).also { it.mkdirs() }
      tmpRasterDir.listFiles()?.forEach { crop ->
        val bytes = fileSystem.read(crop.path.toPath()) { readByteArray() }
        fileSystem.write(destRasterDir.resolve(crop.name).path.toPath()) { write(bytes) }
      }
    }
    // Best-effort cleanup of the throwaway render dir + its PNG so the tall probe artefacts don't
    // linger next to the real products.
    runCatching { tmpDir.deleteRecursively() }
    runCatching { File(outputDir, "$tmpBase.png").delete() }

    val tookMs = (System.nanoTime() - startNs) / 1_000_000L
    return RenderResult(
      id = requestId,
      classLoaderHashCode = System.identityHashCode(classLoader),
      classLoaderName = classLoader.javaClass.name,
      pngPath = destSvg.absolutePath,
      metrics = mapOf("tookMs" to tookMs),
    )
  }

  /** Geometry of the main vertical scroll container in a rendered scene (root-pixel space). */
  private data class ScrollMeasure(
    /** The scroll container's own bottom edge — where any pinned bottom chrome begins. */
    val scrollNodeBottom: Int,
    /** The deepest composed descendant's bottom — how far the list content actually reaches. */
    val contentBottom: Int,
  )

  /**
   * Measures the tallest vertically-scrollable node in [scene] and how far its composed content
   * reaches, or null when nothing is vertically scrollable. Used by [runScrollSvgScenario] to size
   * the full-page render by *geometry* rather than the LazyList scroll-range estimate (which is
   * coarse — its `maxValue` isn't a reliable pixel extent). Bounds are root-pixel space, same as
   * the figma-svg export reads.
   */
  @OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
  )
  private fun measureVerticalScroll(scene: ImageComposeScene): ScrollMeasure? {
    val root = scene.semanticsOwners.firstOrNull()?.unmergedRootSemanticsNode ?: return null
    // Pick the tallest node carrying a VerticalScrollAxisRange — the screen's main scroll container
    // (a nested inner scroller would be shorter).
    var scroll: SemanticsNode? = null
    var tallest = -1f
    fun findScroll(node: SemanticsNode) {
      if (node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null) {
        val h = node.boundsInRoot.height
        if (h > tallest) {
          tallest = h
          scroll = node
        }
      }
      node.children.forEach(::findScroll)
    }
    findScroll(root)
    val scrollNode = scroll ?: return null
    // Deepest composed descendant bottom = the real content extent (grows as more items compose).
    var maxBottom = scrollNode.boundsInRoot.top
    fun deepest(node: SemanticsNode) {
      val b = node.boundsInRoot.bottom
      if (b.isFinite() && b > maxBottom) maxBottom = b
      node.children.forEach(::deepest)
    }
    scrollNode.children.forEach(::deepest)
    return ScrollMeasure(
      scrollNodeBottom = scrollNode.boundsInRoot.bottom.toInt(),
      contentBottom = maxBottom.toInt(),
    )
  }

  /**
   * Live-daemon `kind=LOTTIE` animated capture. Sweeps the discovered Lottie asset's intrinsic
   * timeline into a looping GIF at `<outputDir>/<outputBaseName>.gif` by delegating to
   * `:renderer-desktop`'s [ee.schimke.composeai.renderer.renderLottieGif]. The default window is
   * the asset's own `durationFrames / frameRate`, so "animate for the preview's default duration"
   * needs no extra payload — a 2s clip plays for 2s.
   *
   * The asset is resolved by [LottiePreview]/`renderLottieGif` off the **context classloader**
   * (which `loadLottieAsset` consults first), so we install [classLoader] for the duration of the
   * capture — the disposable child loader carries the consumer's processed resources
   * (CLASSLOADER.md § Risks 2). Mirrors [runScrollScenario]'s shape: returns a [RenderResult] whose
   * `pngPath` points at the produced GIF (the field is the generic artefact path, same as the
   * scroll-GIF path).
   *
   * Throws [IllegalStateException] when the spec carries no `assetPath`, or when the GIF writer
   * declined (never on a standard JRE) — the dispatcher surfaces it like any other render failure.
   */
  private fun runLottieGifScenario(
    spec: RenderSpec,
    requestId: Long,
    classLoader: ClassLoader,
  ): RenderResult {
    val asset =
      spec.assetPath?.takeIf { it.isNotBlank() }
        ?: error(
          "RenderEngine: kind=LOTTIE renderMode='$LOTTIE_GIF_RENDER_MODE' requires an assetPath on " +
            "the RenderSpec so the Lottie document can be inflated"
        )
    val outputFile = File(outputDir, "${spec.outputBaseName}.gif")
    outputFile.parentFile?.mkdirs()

    val previousContext = Thread.currentThread().contextClassLoader
    Thread.currentThread().contextClassLoader = classLoader
    val startNs = System.nanoTime()
    try {
      ee.schimke.composeai.renderer.renderLottieGif(
        assetPath = asset,
        widthPx = spec.widthPx,
        heightPx = spec.heightPx,
        density = spec.density,
        showBackground = spec.showBackground,
        backgroundColor = spec.backgroundColor,
        outputFile = outputFile,
      )
        ?: error(
          "RenderEngine: Lottie GIF encode produced no output for asset '$asset' (GIF writer " +
            "plugin unavailable?)"
        )
    } finally {
      Thread.currentThread().contextClassLoader = previousContext
    }
    val tookMs = (System.nanoTime() - startNs) / 1_000_000L
    return RenderResult(
      id = requestId,
      classLoaderHashCode = System.identityHashCode(classLoader),
      classLoaderName = classLoader.javaClass.name,
      pngPath = outputFile.absolutePath,
      metrics = mapOf("tookMs" to tookMs),
    )
  }

  /**
   * Lazily reads the daemon's `previews.json` via [PreviewIndex.loadFromFile] using the
   * `composeai.daemon.previewsJsonPath` system property the gradle plugin populates. Falls back to
   * [PreviewIndex.empty] when the property is unset (harness / fake-mode tests) —
   * [runScrollScenario] then surfaces a structured "no scroll metadata" error. Mirrors the Android
   * engine's helper of the same name so the two backends resolve scroll intent identically.
   */
  private fun loadPreviewIndexLazily(): PreviewIndex {
    val path = System.getProperty(PreviewIndex.PREVIEWS_JSON_PATH_PROP)
    return if (path.isNullOrBlank()) {
      PreviewIndex.empty()
    } else {
      PreviewIndex.loadFromFile(java.nio.file.Paths.get(path))
    }
  }

  interface PreviewContextCapture {
    fun shouldCapture(previewId: String?, renderMode: String?): Boolean
  }

  companion object {
    /**
     * System property carrying the absolute path of the renders directory. Same name the Android
     * side uses; the gradle plugin's daemon launch descriptor sets it once at JVM start.
     */
    const val OUTPUT_DIR_PROP: String = "composeai.render.outputDir"

    /**
     * Issue #1604 — scroll scenarios the daemon's `data/fetch` re-render path can request. The
     * registry in `:data-scroll-connector` advertises `render/scroll/long` / `render/scroll/gif` as
     * `requiresRerender = true`, so a missing scroll artefact returns
     * `Outcome.RequiresRerender("scroll-long" | "scroll-gif")` and the dispatcher submits a render
     * with `spec.renderMode` set to one of these constants. [render] routes scroll-mode requests
     * into [runScrollScenario]. Values match `:daemon:android`'s `RenderEngine` constants byte for
     * byte so a single payload drives both backends.
     */
    const val SCROLL_LONG_RENDER_MODE: String = "scroll-long"

    const val SCROLL_GIF_RENDER_MODE: String = "scroll-gif"

    /**
     * Render mode requesting the animated Lottie capture (looping GIF spanning the asset's
     * intrinsic timeline). Paired with `kind=LOTTIE` + an `assetPath`; [render] routes it into
     * [runLottieGifScenario]. The still-frame Lottie path uses the default (null) render mode.
     */
    const val LOTTIE_GIF_RENDER_MODE: String = "lottie-gif"

    /**
     * Render mode requesting the **full-page** figma-svg export of a scrolling preview
     * (`compose/figma-svg-long`). `ComposeFigmaSvgLongDataProductRegistry` advertises the kind as
     * `requiresRerender = true`, so a missing artefact returns
     * `Outcome.RequiresRerender("figma-svg-long")` and the dispatcher submits a render with this
     * mode; [render] routes it into [runScrollSvgScenario]. Value matches `:daemon:android`'s
     * constant so a single payload drives either backend.
     */
    const val FIGMA_SVG_LONG_RENDER_MODE: String = "figma-svg-long"

    /** Max px the `figma-svg-long` growth loop will add on top of the base viewport. */
    private const val SCROLL_SVG_MAX_EXTRA_PX: Int = 40_000

    /**
     * Max growth iterations before giving up. Each iteration adds a base viewport of headroom so a
     * fresh batch of items composes, so a handful covers a long list; the cap only guards against
     * content whose height keeps shifting as it reflows.
     */
    private const val SCROLL_SVG_MAX_GROW_ITERATIONS: Int = 10

    /**
     * Slack (px) added below the deepest composed descendant when sizing the frame, so the last
     * item's own padding/descent below its (text) semantics bounds isn't clipped.
     */
    private const val SCROLL_SVG_CONTENT_MARGIN_PX: Int = 40

    /** Output-base suffix isolating the tall render so it can't clobber the preview's products. */
    private const val SCROLL_SVG_TMP_SUFFIX: String = "__figma_svg_long"

    fun supportsLocaleTagOverride(): Boolean = localProvidableLocaleListOrNull() != null

    @Suppress("UNCHECKED_CAST")
    private fun localProvidableLocaleListOrNull(): ProvidableCompositionLocal<LocaleList>? {
      val holder =
        runCatching { Class.forName("androidx.compose.ui.platform.CompositionLocalsKt") }
          .getOrNull() ?: return null
      return runCatching {
          holder.getMethod("getLocalProvidableLocaleList").invoke(null)
            as ProvidableCompositionLocal<LocaleList>
        }
        .getOrNull()
    }

    private fun localeProviders(localeTag: String?): Array<ProvidedValue<*>> {
      val tag = localeTag?.takeIf { it.isNotBlank() } ?: return emptyArray()
      val local = localProvidableLocaleListOrNull() ?: return emptyArray()
      // Pseudolocales (`en-XA`, `ar-XB`) aren't real BCP-47 locales — `LocaleList("en-XA")` either
      // throws or silently degrades depending on the JVM's ICU build. Substitute the base locale
      // (`en` / `ar`) so locale-sensitive Compose text rendering resolves cleanly. The visual
      // pseudolocalisation knob (LayoutDirection.Rtl for ar-XB) is provided by
      // `PseudolocaleOverrideExtensionDesktop`'s around-composable; text-content
      // pseudolocalisation is Android-only — see `site/reference/pseudolocale.md`.
      val effectiveTag =
        ee.schimke.composeai.data.pseudolocale.Pseudolocale.fromTag(tag)?.baseTag ?: tag
      // A real RTL locale (`ar`, `he`, `fa`, …) also flips the layout, matching a real device —
      // `ar-XB` (whose RTL is provided by `PseudolocaleOverrideExtensionDesktop`) isn't the only
      // RTL case. `effectiveTag` is the base locale, so a pseudolocale never double-provides here.
      return if (ee.schimke.composeai.data.pseudolocale.LocaleDirection.isRtl(effectiveTag)) {
        arrayOf(
          local provides LocaleList(effectiveTag),
          androidx.compose.ui.platform.LocalLayoutDirection provides
            androidx.compose.ui.unit.LayoutDirection.Rtl,
        )
      } else {
        arrayOf(local provides LocaleList(effectiveTag))
      }
    }
  }
}

/**
 * What [RenderEngine.render] needs to produce a single PNG. Decoupled from the protocol's
 * `RenderRequest` so the engine has no dependency on the JSON-RPC envelope shapes.
 *
 * For v1 the daemon's [DesktopHost] parses this out of [RenderRequest.Render.payload] using a
 * trivial `key=value;key=value` format (see [parseFromPayload]); a typed `previewId` field on
 * `RenderRequest` is a documented follow-up that requires widening the renderer-agnostic surface in
 * `:daemon:core`.
 */
data class RenderSpec(
  val previewId: String? = null,
  val renderMode: String? = null,
  /** Fully-qualified name of the class containing the @Preview function. */
  val className: String,
  /** Method name of the @Preview function (parameterless overload). */
  val functionName: String,
  /**
   * Preview flavour mirror of `PreviewKind` (string-typed). `null` / `"COMPOSE"` take the normal
   * class-reflection path; `"LOTTIE"` skips reflection entirely and inflates [assetPath] via
   * Compottie, so a file-discovered Lottie asset renders through the live daemon (and VS Code) with
   * no consumer composable.
   */
  val kind: String? = null,
  /** For `kind="LOTTIE"`: the classpath-relative Lottie asset path. */
  val assetPath: String? = null,
  val widthPx: Int = 320,
  val heightPx: Int = 320,
  /**
   * AS-parity wrap-content flags. When set, `widthPx`/`heightPx` are a *sandbox* bound (not a fixed
   * frame): the composition root is measured with a relaxed (min = 0) constraint on the wrapped
   * axis and sized to the composable's intrinsic size, and the background paints on that intrinsic
   * box — so the captured layout/semantics tree (and the `compose/figma-svg` + wireframe derived
   * from it) reflect the preview's *natural* size, matching the standalone renderer's wrap crop
   * rather than a fixed 320² box that clipped/reflowed wide content. Off ⇒ the composition fills
   * the frame (prior behaviour). Set by [PreviewManifestRouter] for previews that declare no
   * explicit size/device.
   */
  val wrapWidth: Boolean = false,
  val wrapHeight: Boolean = false,
  val density: Float = 2.0f,
  val showBackground: Boolean = true,
  val backgroundColor: Long = 0L,
  /**
   * Raw `@Preview(device = …)` string when known. The desktop render path is currently
   * shape-agnostic (no circular crop — that's an Android/Robolectric-only mechanism), but the field
   * is carried so the wire format stays identical to `:daemon:android`'s `RenderSpec` and a single
   * payload can drive both backends.
   */
  val device: String? = null,
  /**
   * `@Preview(showSystemUi = ...)` (issue #1930). When `true` on a phone-shape capture the render
   * body wraps the composition in `:renderer-desktop`'s `SystemBarsFrame` — a synthetic status bar
   * + gesture-nav pill that simulates Android phone chrome on this non-Android backend, matching
   *   what the Android renderer draws so a single design reference matches either candidate.
   *   Skipped for round/Wear [device]s. Dark chrome follows [uiMode].
   */
  val showSystemUi: Boolean = false,
  /** Stem used for the output PNG filename (e.g. "preview-A" → "<outputDir>/preview-A.png"). */
  val outputBaseName: String = "${className.substringAfterLast('.')}-$functionName",
  /**
   * BCP-47 locale tag override. Scoped through Compose UI's providable locale list when present.
   */
  val localeTag: String? = null,
  /**
   * Font scale multiplier override. Threaded through `Density(density, fontScale)` — applied to
   * `ImageComposeScene`'s constructor and re-provided as `LocalDensity` so any composition path
   * that reads `LocalDensity` directly (rather than the scene's density) sees the same value.
   */
  val fontScale: Float? = null,
  /**
   * Light/dark mode override. Provided as `LocalSystemTheme` — Compose Desktop's
   * `isSystemInDarkTheme()` reads that local rather than `Configuration.uiMode`.
   */
  val uiMode: SpecUiMode? = null,
  /**
   * Portrait/landscape override. Desktop has no display-rotation concept on `ImageComposeScene`,
   * but issue #1208 reduces `LANDSCAPE` to a `widthPx ↔ heightPx` swap applied by [DesktopHost]
   * before the spec reaches the engine. Explicit `widthPx`/`heightPx` overrides on the same call
   * win over the hint — `RenderEngine` reads the resolved dimensions straight from this spec
   * without re-interpreting the orientation field, so any swap must already be baked in by the
   * caller.
   */
  val orientation: SpecOrientation? = null,
  /**
   * Per-render `LocalInspectionMode` override for one-shot renders. Null preserves preview
   * semantics (`true`); held interactive/recording sessions pass their own runtime-like `false`.
   */
  val inspectionMode: Boolean? = null,
  /**
   * Per-render slot mode. When `true` the renderer provides `LocalSlotMode = true`, so a
   * `PreviewSlot` marker renders a labelled placeholder instead of its content. Null/false renders
   * content normally.
   */
  val slotMode: Boolean? = null,
  /**
   * Per-render cleared-background toggle. When `true` the harness background is forced transparent
   * (overriding [showBackground]/[backgroundColor]) and `LocalPreviewBackgroundCleared = true` is
   * provided around the preview, so a composable that paints its own opaque fill can drop it for a
   * crisp transparent outline. Default `false` preserves the discovery-time background.
   */
  val clearBackground: Boolean = false,
  /**
   * Per-call overrides bag, threaded through every registered [PreviewOverrideExtension]. The
   * renderer doesn't read individual fields directly — registered planners decide what to apply.
   * Direct-applied overrides like size, density, and locale stay on this spec's typed fields above
   * because the renderer applies them itself; theme/wallpaper-style overrides ride along here so
   * adding a new override-driven feature is purely a connector concern.
   */
  val overrides: PreviewOverrides? = null,
  /**
   * FQN of the `PreviewWrapperProvider` from `@PreviewWrapper(SomeProvider::class)` when the source
   * preview is annotated. Sourced from the gradle plugin's discovery JSON (`extractWrapperFqn`
   * reads it off the class-file annotation tables — the upstream annotation has
   * `AnnotationRetention.BINARY` and is invisible to `Method.annotations` at runtime, see
   * issue #1440). The render body drives `InvokeWithOptionalWrapper` off this field when set; null
   * falls back to the (best-effort) runtime-reflection lookup for direct-payload callers that
   * bypass the manifest.
   */
  val wrapperClassName: String? = null,
) {

  enum class SpecUiMode {
    LIGHT,
    DARK,
  }

  enum class SpecOrientation {
    PORTRAIT,
    LANDSCAPE,
  }

  companion object {

    /**
     * Parses [RenderRequest.Render.payload] — a `;`-delimited `key=value` string — into a
     * [RenderSpec]. Recognised keys: `className`, `functionName`, `widthPx`, `heightPx`, `density`,
     * `showBackground`, `backgroundColor`, `device`, `outputBaseName`, `localeTag`, `fontScale`,
     * `uiMode` (`light`/`dark`), `orientation` (`portrait`/`landscape`), `inspectionMode`
     * (`true`/`false`), and data-product routing keys `previewId` / `mode`. `className` and
     * `functionName` are required; everything else falls back to the defaults on this data class.
     *
     * Keeping this stringly-typed for v1 is deliberate (per the task brief). When `RenderRequest`
     * grows a typed `previewId: String?` field, [DesktopHost] will look the spec up in
     * `previews.json` rather than parsing it out of the payload — at which point this helper goes
     * away.
     */
    fun parseFromPayload(payload: String): RenderSpec {
      val map = mutableMapOf<String, String>()
      for (entry in payload.split(';')) {
        val trimmed = entry.trim()
        if (trimmed.isEmpty()) continue
        val eq = trimmed.indexOf('=')
        if (eq <= 0) continue
        map[trimmed.substring(0, eq).trim()] = trimmed.substring(eq + 1).trim()
      }
      val className =
        map["className"] ?: error("RenderSpec.parseFromPayload: missing 'className' in '$payload'")
      val functionName =
        map["functionName"]
          ?: error("RenderSpec.parseFromPayload: missing 'functionName' in '$payload'")
      val defaults = RenderSpec(className = className, functionName = functionName)
      return RenderSpec(
        previewId = map["previewId"]?.takeIf { it.isNotBlank() },
        renderMode = map["mode"]?.takeIf { it.isNotBlank() },
        className = className,
        functionName = functionName,
        widthPx = map["widthPx"]?.toIntOrNull() ?: defaults.widthPx,
        heightPx = map["heightPx"]?.toIntOrNull() ?: defaults.heightPx,
        wrapWidth = map["wrapWidth"]?.toBoolean() ?: defaults.wrapWidth,
        wrapHeight = map["wrapHeight"]?.toBoolean() ?: defaults.wrapHeight,
        density = map["density"]?.toFloatOrNull() ?: defaults.density,
        showBackground = map["showBackground"]?.toBoolean() ?: defaults.showBackground,
        backgroundColor = map["backgroundColor"]?.toLongOrNull() ?: defaults.backgroundColor,
        device = map["device"]?.takeIf { it.isNotBlank() } ?: defaults.device,
        showSystemUi = map["showSystemUi"]?.toBoolean() ?: defaults.showSystemUi,
        outputBaseName = map["outputBaseName"] ?: defaults.outputBaseName,
        localeTag = map["localeTag"]?.takeIf { it.isNotBlank() },
        fontScale = map["fontScale"]?.toFloatOrNull(),
        uiMode =
          when (map["uiMode"]?.lowercase()) {
            "light" -> SpecUiMode.LIGHT
            "dark" -> SpecUiMode.DARK
            else -> null
          },
        orientation =
          when (map["orientation"]?.lowercase()) {
            "portrait" -> SpecOrientation.PORTRAIT
            "landscape" -> SpecOrientation.LANDSCAPE
            else -> null
          },
        inspectionMode = map["inspectionMode"]?.toBooleanStrictOrNull(),
        slotMode = map["slotMode"]?.toBooleanStrictOrNull(),
        clearBackground = map["clearBackground"]?.toBoolean() ?: defaults.clearBackground,
        overrides = map["overrides"]?.decodePreviewOverrides(),
        wrapperClassName = map["wrapperClassName"]?.takeIf { it.isNotBlank() },
      )
    }

    private val json = Json {
      ignoreUnknownKeys = true
      encodeDefaults = false
    }

    private fun String.decodePreviewOverrides(): PreviewOverrides? =
      runCatching {
          val bytes = Base64.getUrlDecoder().decode(this)
          json.decodeFromString(PreviewOverrides.serializer(), bytes.toString(Charsets.UTF_8))
        }
        .getOrNull()

    /**
     * Decode the base64-encoded `PreviewOverrides` bag carried in the `overrides=<b64>` payload
     * token — the extension bag `JsonRpcServer.encodeRenderPayload` emits (`namedOverrides`,
     * `themeProvider`, `wallpaper`, `permissions`, `gestures`, `lottie`). Exposed so the
     * previewId-based render path ([DesktopHost.specFromPreviewIdPayload]) can carry the bag
     * through too, not just the className-based [parseFromPayload]; without it a `?knob.<key>=…`
     * edit on the bundle-backed live daemon (`serve` / preview.coo.ee) is silently dropped.
     */
    internal fun decodeOverridesToken(token: String): PreviewOverrides? =
      token.decodePreviewOverrides()
  }
}

/**
 * Tiny @Composable trampoline that invokes [composableMethod] reflectively against the current
 * composer. Mirrors `:renderer-desktop`'s private `InvokeComposable`; kept private+top-level so the
 * compose-compiler plugin recognises it as a composable function.
 */
@androidx.compose.runtime.Composable
private fun InvokeComposable(composableMethod: ComposableMethod) {
  composableMethod.invoke(currentComposer, null)
}

/**
 * Wraps [InvokeComposable] in the preview's `@PreviewWrapper(SomeProvider::class)` `Wrap { … }` if
 * one is present, sourcing the wrapper FQN from [wrapperFqnFromSpec] (the discovery-time class-file
 * read) with a best-effort runtime-reflection fallback. Without this the daemon would call the
 * preview body directly and bypass the wrapper — e.g.
 * `@PreviewWrapper(RemotePreviewWrapper::class)` previews would crash with `IllegalStateException:
 * Invalid applier` the moment they emit a `RemoteBox` / `RemoteColumn` / `RemoteRow`, because those
 * composables require the RemoteCompose applier the wrapper installs. Mirrors the Android daemon
 * and `:renderer-android`'s `PreviewRenderStrategy`.
 *
 * **Why the FQN comes from the spec rather than `Method.annotations`.** The upstream
 * `androidx.compose.ui.tooling.preview.PreviewWrapper` annotation has `AnnotationRetention.BINARY`
 * (issue #1440): it's emitted into the class file but not retained at runtime. The gradle plugin's
 * `extractWrapperFqn` reads the FQN from the class-file annotation tables (where it IS still
 * visible) and writes it into `previews.json`; the daemon threads it into
 * [RenderSpec.wrapperClassName] via [PreviewManifestRouter]. The reflection fallback is retained
 * for direct-payload callers and remains best-effort.
 *
 * Wrapper class resolution flows through [loadPreviewWrapperClass], so connector-provided SPI
 * substitutions apply transparently.
 */
@Composable
private fun InvokeWithOptionalWrapper(
  composableMethod: ComposableMethod,
  wrapperFqnFromSpec: String?,
  themeProviderFqn: String? = null,
) {
  val wrapper =
    remember(composableMethod, wrapperFqnFromSpec, themeProviderFqn) {
      // A `themeProvider` override wraps the preview in an app-declared theme provider IN PLACE OF
      // its own `@PreviewWrapper` — but only when it actually loads. On a stale/misspelled FQN,
      // `loadWrapperByFqnOrNull` logs and returns null; we then fall back to the preview's declared
      // wrapper rather than stripping it. A blank/absent themeProvider skips straight to it.
      themeProviderFqn?.takeIf { it.isNotBlank() }?.let { loadWrapperByFqnOrNull(it) }
        ?: resolveWrapperOrNull(composableMethod, wrapperFqnFromSpec)
    }
  if (wrapper == null) {
    InvokeComposable(composableMethod)
  } else {
    val (wrapMethod, wrapperInstance) = wrapper
    val body: @Composable () -> Unit = { InvokeComposable(composableMethod) }
    wrapMethod.invoke(currentComposer, wrapperInstance, body)
  }
}

/**
 * Resolves the `@PreviewWrapper`'s `PreviewWrapperProvider` to a `Wrap(content)` method plus an
 * instance.
 *
 * Strategy (in order):
 * 1. Use [wrapperFqnFromSpec] when supplied — production path, sourced from `previews.json`.
 * 2. Otherwise, look up `@androidx.compose.ui.tooling.preview.PreviewWrapper` reflectively off the
 *    composable's underlying JVM method. This is best-effort: the upstream annotation has
 *    `AnnotationRetention.BINARY`, so `Method.annotations` will not return it for real-world
 *    previews. Kept for direct-payload callers and back-compat.
 *
 * Returns null when no wrapper is resolvable.
 *
 * Reflective lookup (rather than a compile-time import of `PreviewWrapper`) keeps the daemon
 * runnable on older Compose runtimes that predate the annotation (1.11.0-beta+).
 */
internal fun resolveWrapperOrNull(
  composableMethod: ComposableMethod,
  wrapperFqnFromSpec: String? = null,
): Pair<ComposableMethod, Any>? {
  val wrapperFqn =
    wrapperFqnFromSpec?.takeIf { it.isNotBlank() }
      ?: resolveWrapperFqnViaReflection(composableMethod)
      ?: return null
  return loadWrapperByFqnOrNull(wrapperFqn)
}

private fun resolveWrapperFqnViaReflection(composableMethod: ComposableMethod): String? {
  val jvmMethod = composableMethod.asMethod()
  val ann =
    jvmMethod.annotations.firstOrNull {
      it.annotationClass.java.name == "androidx.compose.ui.tooling.preview.PreviewWrapper"
    } ?: return null
  val wrapperValue =
    runCatching { ann.annotationClass.java.getDeclaredMethod("wrapper").invoke(ann) }.getOrNull()
      ?: return null
  return when (wrapperValue) {
    is Class<*> -> wrapperValue.name
    is kotlin.reflect.KClass<*> -> wrapperValue.java.name
    else -> null
  }
}

private fun loadWrapperByFqnOrNull(wrapperFqn: String): Pair<ComposableMethod, Any>? =
  runCatching {
      val resolved = loadPreviewWrapperClass(wrapperFqn)
      val instance = resolved.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
      val wrapMethod = resolved.getDeclaredComposableMethod("Wrap", Function2::class.java)
      wrapMethod to (instance as Any)
    }
    .onFailure { t ->
      System.err.println(
        "compose-ai-daemon: [render] wrapper resolution failed for $wrapperFqn " +
          "(${t.javaClass.simpleName}: ${t.message}); rendering without wrapper"
      )
    }
    .getOrNull()

@Composable
private fun CaptureMaterialTheme(
  onCaptured: (ColorScheme, Typography, Shapes, ThemePayload) -> Unit
) {
  val colorScheme = MaterialTheme.colorScheme
  val typography = MaterialTheme.typography
  val shapes = MaterialTheme.shapes
  SideEffect {
    onCaptured(
      colorScheme,
      typography,
      shapes,
      themePayloadFromMaterialTheme(colorScheme, typography, shapes),
    )
  }
}

internal class PreviewSlotTableCapture {
  val store: MutableSet<CompositionData> = Collections.newSetFromMap(WeakHashMap())

  fun snapshot(): List<CompositionData> = store.filterNotNull()
}

internal class MaterialThemeFallbackCapture {
  var typography: Typography? = null
    private set

  var shapes: Shapes? = null
    private set

  var payload: ThemePayload? = null
    private set

  fun capture(typography: Typography, shapes: Shapes) {
    this.typography = typography
    this.shapes = shapes
  }

  fun capture(payload: ThemePayload) {
    this.payload = payload
  }
}

@OptIn(InternalComposeApi::class)
@androidx.compose.runtime.Composable
private fun InspectablePreviewContent(
  capture: PreviewSlotTableCapture,
  content: @androidx.compose.runtime.Composable () -> Unit,
) {
  currentComposer.collectParameterInformation()
  capture.store.add(currentComposer.compositionData)
  CompositionLocalProvider(LocalInspectionTables provides capture.store, content = content)
}

private fun DeviceDimensions.DeviceSpec.previewDeviceSpec(): PreviewDeviceSpec =
  PreviewDeviceSpec(widthDp = widthDp, heightDp = heightDp, density = density, isRound = isRound)

/**
 * The Google-Fonts WOFF2 resolver for the `compose/figma-svg` export, or null when font embedding
 * is off. On when `composeai.figma.embedFonts=true`, or implicitly when fidelity is being measured
 * (`composeai.figma.fidelity=true`) so the score reflects the embedded face rather than the
 * browser's substitute. Reuses the renderer's font cache dir / offline switch so a face is
 * downloaded at most once per environment.
 */
private fun figmaFontResolver(): FigmaFontResolver? {
  fun on(prop: String) = System.getProperty(prop)?.lowercase() == "true"
  if (!on("composeai.figma.embedFonts") && !on("composeai.figma.fidelity")) return null
  return GoogleFontsWoff2Resolver(
    cacheDir = System.getProperty("composeai.fonts.cacheDir")?.let { File(it) },
    offline = on("composeai.fonts.offline"),
  )
}
