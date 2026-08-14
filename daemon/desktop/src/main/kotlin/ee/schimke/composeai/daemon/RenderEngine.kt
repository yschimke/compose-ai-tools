package ee.schimke.composeai.daemon

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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.daemon.devices.DeviceDimensions
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.layoutinspector.ComposeFigmaSvgProduct
import ee.schimke.composeai.data.layoutinspector.FigmaSvgBackgroundMode
import ee.schimke.composeai.data.render.LinkBufferComposer
import ee.schimke.composeai.data.render.PreviewBackends
import ee.schimke.composeai.data.render.PreviewBackground
import ee.schimke.composeai.data.render.PreviewContext
import ee.schimke.composeai.data.render.PreviewDeviceSpec
import ee.schimke.composeai.data.render.extensions.ExtensionContextData
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext
import ee.schimke.composeai.data.render.extensions.PlannedDataExtension
import ee.schimke.composeai.data.render.extensions.PostCaptureProcessor
import ee.schimke.composeai.data.render.extensions.RecordingDataProductStore
import ee.schimke.composeai.data.render.extensions.compose.ComposeDataExtensionPipeline
import ee.schimke.composeai.data.render.extensions.compose.CompositionTracing
import ee.schimke.composeai.data.render.extensions.compose.RecordingExtensionCompositionSink
import ee.schimke.composeai.data.render.extensions.loadPreviewWrapperClass
import ee.schimke.composeai.data.render.extensions.provides
import ee.schimke.composeai.data.theme.ThemePayload
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.io.composeAiCacheDir
import ee.schimke.composeai.preview.lottie.LottiePreview
import java.io.File
import java.util.Base64
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.JvmResourceReader
import org.jetbrains.compose.resources.LocalResourceReader
import org.jetbrains.compose.resources.ResourceReader
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
 * **What's duplicated, what isn't.** This is the small-composable, single-frame subset — the
 * daemon's v1 surface renders one frame per preview id from existing `previews.json` discovery.
 * `@PreviewParameter` is *resolved* here (via
 * [ee.schimke.composeai.renderer.PreviewParameterSupport]) but not *fanned out*: a parameterized
 * preview renders its provider's first value under the bare id, matching the one-frame-per-id
 * contract and mirroring `:daemon:android`. Emitting one file per value stays with the standalone
 * `DesktopRendererMain`. The scroll / animation / GIF paths from `DesktopRendererMain` likewise
 * stay behind the standalone renderer for now; B-desktop.1.7+ revisits if the harness needs them.
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
   * `<userCache>/composeai/history/daemon-renders/` so unit tests don't need to set the property.
   * Deliberately NOT under `user.dir` — an unset property must not scatter PNGs through whatever
   * directory the daemon happened to be launched from.
   */
  private val outputDir: File =
    File(
      System.getProperty(OUTPUT_DIR_PROP)
        ?: composeAiCacheDir("history").resolve("daemon-renders").absolutePath
    ),
  private val dataDir: File = (outputDir.parentFile ?: outputDir).resolve("data"),
  private val previewContextCapture: PreviewContextCapture? = null,
  private val previewOverrideExtensions: PreviewOverrideExtensions =
    PreviewOverrideExtensions.Empty,
  private val frameNanoTime: () -> Long = System::nanoTime,
  private val fileSystem: FileSystem = SystemFileSystem,
  /**
   * Scene lifecycle hook — fires with the freshly allocated [ImageComposeScene] **before**
   * `setContent` composes into it, and again with `null` at tearDown.
   *
   * This exists so `compose/recomposition` can answer for an ordinary render. That producer
   * instruments a scene's `Recomposer`, and until now the only scene it could reach was the one a
   * *live interactive session* held (`DesktopHost.InteractiveSessionListener`), so recomposition
   * counts were unavailable for the plain render path — which is the path almost every preview
   * takes. The install has to happen before `setContent` or the initial composition, the very thing
   * worth counting, has already run by the time the observer attaches.
   *
   * Deliberately a plain function type rather than `DesktopHost.InteractiveSessionListener`: the
   * engine has no business knowing about interactive sessions, and the two callers converge on the
   * same producer method anyway. Null (the default) keeps every test's behaviour.
   */
  private val sceneLifecycleListener: ((String, ImageComposeScene?) -> Unit)? = null,
  /**
   * Always-on post-capture data-artifact extensions, mirroring the Android engine's
   * `builtDataArtifactExtensions` seam. Each [PostCaptureProcessor] runs after the PNG capture,
   * reading the [RenderArtifactContextKeys] the engine populates. Defaults to the portable
   * always-on set shared with the Android daemon; more sidecars migrate off the inline block onto
   * this seam in follow-ups. The wireframe extension is handed the desktop Skia PNG baker and
   * `densityAware = true` so its output matches the density-resolved payload the inline path wrote.
   */
  private val dataArtifactExtensions: List<PlannedDataExtension> =
    listOf(
      ComposeSemanticsExtension(),
      ComposeSemanticsWireframeExtension(
        pngGenerator = { payload, destPng -> DesktopSemanticsWireframe.generate(payload, destPng) },
        densityAware = true,
      ),
      LayoutInspectorExtension(),
      ComposeFigmaSvgExtension(fontResolver = ::figmaFontResolver),
    ),
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
    // The rewritten Compose SlotTable opt-in, applied against the classloader this render composes
    // on — before its first composition, which is what the runtime latches. The daemon renders many
    // previews per JVM, so the flag is a whole-session property here rather than a per-request one;
    // re-applying it per render is idempotent and costs a `getProperty`. Unset (default) is silent.
    LinkBufferComposer.applyAndDescribe(classLoader)?.let(System.err::println)
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
    // `renderLottieApng`, mirroring how `runScrollScenario` delegates to `renderScrollPreview`.
    if (spec.kind == "LOTTIE" && spec.renderMode == LOTTIE_GIF_RENDER_MODE) {
      return runLottieGifScenario(spec = spec, requestId = requestId, classLoader = classLoader)
    }
    val trace = PerfettoTraceDataProducer.recorder(spec.outputBaseName, backend = "desktop")
    val state =
      trace.section("compose:setUp") {
        setUp(spec, classLoader, inspectionMode = spec.inspectionMode ?: true, trace = trace)
      }
    val result =
      try {
        // `@ScrollingPreview(END)` — settle the scrollable at its content end before the capture,
        // so the PNG *and* every tree derived from the same scene (semantics, layout, figma-svg)
        // describe the frame the Gradle render ships. Ahead of `renderOnce`, which is also the
        // interactive session's per-input entry point and must never re-drive under the user.
        val driveStartNs = System.nanoTime()
        trace.section("render:scrollToEnd") { driveStaticScrollToEnd(state) }
        val driveNs = System.nanoTime() - driveStartNs
        trace.section("render:once") {
          renderOnce(
            state,
            requestId,
            sandboxStats = sandboxStats,
            trace = trace,
            elapsedBeforeNs = driveNs,
          )
        }
      } finally {
        trace.section("compose:tearDown") { tearDown(state) }
        trace.write(dataDir)
      }
    // Re-stamp the trace here rather than taking `renderOnce`'s: `finally` has run by now, so this
    // snapshot is the only one that includes `compose:setUp`, `render:once` and `compose:tearDown`.
    // `renderOnce` still stamps its own, which is what the interactive session (which calls it
    // directly, once per input) reports.
    return result.copy(trace = trace.renderTrace())
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
  @OptIn(androidx.compose.ui.InternalComposeUiApi::class, ExperimentalResourceApi::class)
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
    // Resolve the composable entrypoint plus any `@PreviewParameter` argument list. A parameterized
    // preview compiles to `foo(<T>, Composer, int)`, so the bare `getDeclaredComposableMethod`
    // lookup used to throw `NoSuchMethodException` here — no PNG, no semantics. The daemon now
    // renders the provider's *first* value under the bare id (the per-value fan-out stays with the
    // standalone renderer), mirroring `:daemon:android`'s single-frame contract.
    // [PreviewParameterSupport.resolve] opens the method for reflective invocation (Kotlin `private
    // fun` previews are idiomatic and would otherwise throw `IllegalAccessException`).
    val resolvedInvocation: ee.schimke.composeai.renderer.PreviewParameterSupport.Resolved? =
      if (isLottie) {
        null
      } else {
        val clazz = Class.forName(spec.className, true, classLoader)
        ee.schimke.composeai.renderer.PreviewParameterSupport.resolve(
          clazz = clazz,
          functionName = spec.functionName,
          providerClassName = spec.previewParameterProviderClassName,
          limit = spec.previewParameterLimit,
          classLoader = classLoader,
          row = spec.previewParameterRow,
        )
      }
    val composableMethod: ComposableMethod? = resolvedInvocation?.method
    val previewArgs: List<Any?> = resolvedInvocation?.args ?: emptyList()

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
    // Content-size bounds (the Max / Min / Within size modes) apply on a wrapped axis, where
    // widthPx/heightPx are a sandbox bound rather than a fixed frame. A min bound larger than the
    // default sandbox needs the scene enlarged to fit, otherwise the composable is clipped to the
    // scene before the intrinsic-size crop runs. Only widen (never shrink) the scene here — the
    // crop still trims the PNG back to the measured intrinsic size.
    val sizeOverrides = spec.overrides
    // Scene sizing + the AS-parity wrap-measure box are shared with the one-shot
    // `:renderer-desktop`
    // fork via [ee.schimke.composeai.renderer.composePreviewSceneSize] /
    // [ee.schimke.composeai.renderer.ComposePreviewContentBox], so a `compose-preview serve` render
    // and a batch bundle re-render size the same preview identically.
    val sizeBounds =
      ee.schimke.composeai.renderer.PreviewSizeBounds(
        minWidthPx = sizeOverrides?.minWidthPx,
        minHeightPx = sizeOverrides?.minHeightPx,
        maxWidthPx = sizeOverrides?.maxWidthPx,
        maxHeightPx = sizeOverrides?.maxHeightPx,
      )
    val sceneSize =
      ee.schimke.composeai.renderer.composePreviewSceneSize(
        widthPx = spec.widthPx,
        heightPx = spec.heightPx,
        wrapWidth = spec.wrapWidth,
        wrapHeight = spec.wrapHeight,
        sizeBounds = sizeBounds,
      )
    val sceneWidthPx = sceneSize.width
    val sceneHeightPx = sceneSize.height
    val scene =
      try {
        ImageComposeScene(width = sceneWidthPx, height = sceneHeightPx, density = density)
      } catch (t: Throwable) {
        // Ensure we don't leave the context classloader installed if scene allocation fails before
        // the SceneState is even handed back to the caller (caller never gets a chance to call
        // tearDown in that case).
        Thread.currentThread().contextClassLoader = previousContext
        throw t
      }
    // Second half of applying `localeTag` (the first is [localeProviders]): point the JVM default
    // Locale at the override for the *composition* only. CMP `stringResource(...)` reads
    // `androidx.compose.ui.text.intl.Locale.current` (the JVM default on desktop, not the
    // `LocalProvidableLocaleList` composition local), and resolves at composition time — so the
    // switch must be live while `setContent` composes but must NOT persist on the returned
    // `SceneState`. Keeping it on the held state would let a locale-overridden
    // interactive/recording
    // session leak its locale onto every other render in the same daemon until it closed;
    // [renderOnce] re-applies it around each frame instead. See [enterPreviewLocale], which also
    // serialises the switch against every other composition in the process (issue #3721).
    // Announce the scene before anything composes into it — see [sceneLifecycleListener]. Guarded
    // because a producer-side failure must never fail the render it is only observing.
    val lifecyclePreviewId = spec.previewId
    if (sceneLifecycleListener != null && lifecyclePreviewId != null) {
      try {
        sceneLifecycleListener.invoke(lifecyclePreviewId, scene)
      } catch (t: Throwable) {
        System.err.println(
          "RenderEngine: scene lifecycle listener failed for $lifecyclePreviewId: " +
            "${t.javaClass.simpleName}: ${t.message}"
        )
      }
    }
    val localeScope = enterPreviewLocale(spec.localeTag)
    try {
      // Composable-level spans nest *inside* `compose:setContent`, which is the phase that actually
      // composes — so the trace reads "setContent took 40ms, and here is which composable spent
      // it".
      // Opt-in: a real screen emits thousands of these against a dozen engine phases.
      compositionTracing(trace) {
        trace.section("compose:setContent") {
          scene.setContent {
            // PROTOCOL.md § 5 (`renderNow.overrides.uiMode`) — Compose Desktop's
            // `isSystemInDarkTheme()` reads `LocalSystemTheme.current` (foundation-desktop's
            // `DarkTheme.skiko.kt`). Override it here so `uiMode = "dark"` actually flips
            // dark-aware
            // composables instead of falling through to the JVM's `org.jetbrains.skiko.SystemTheme`
            // probe.
            val systemTheme =
              when (spec.uiMode) {
                RenderSpec.SpecUiMode.DARK -> SystemTheme.Dark
                RenderSpec.SpecUiMode.LIGHT -> SystemTheme.Light
                null -> SystemTheme.Unknown
              }
            val previewResources = remember(classLoader) { previewResourceReader(classLoader) }
            CompositionLocalProvider(
              LocalInspectionMode provides inspectionMode,
              // Compose Resources' desktop default reader is permanently bound to the classloader
              // that loaded components-resources (the daemon parent). Bundle classes and their
              // composeResources payload live only on this render's disposable child loader, so an
              // unqualified stringResource() lookup can otherwise miss a resource that is visibly
              // present beside the preview classes. Point the public reader local at the same child
              // loader used to invoke the preview; unlike the thread context-loader install above,
              // Compose Resources actually consults this value.
              LocalResourceReader provides previewResources,
              // Slot mode: a `PreviewSlot` marker renders a labelled placeholder instead of its
              // content, so a structured-screen builder gets a visible slot map. Defaults false.
              ee.schimke.composeai.preview.slots.LocalSlotMode provides (spec.slotMode ?: false),
              // Cleared background ("crisp outline"): a composable drawing its own opaque fill
              // drops
              // it to match the transparent harness background below. Defaults false.
              ee.schimke.composeai.preview.slots.LocalPreviewBackgroundCleared provides
                spec.clearBackground,
              androidx.compose.ui.LocalSystemTheme provides systemTheme,
              LocalDensity provides density,
              // Interactive Lottie scrubbing: a non-null progress lands the captured frame at that
              // timeline position, winning over the composable's authored progress (file-discovered
              // `LottiePreview` below, or any `@Preview` calling it). Read from snapshot state so a
              // held session's `dispatchLottieProgress` recomposes live; sticky across fresh
              // renders
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
                val bgColor = previewBackgroundColor(spec)
                // The AS-parity wrap-measure box (and its fixed-axis `fillMaxSize` counterpart) is
                // shared with the one-shot `:renderer-desktop` fork via [ComposePreviewContentBox],
                // so
                // both size the preview and capture its intrinsic bounds identically.
                // `measuredContent`
                // is written only on a wrapped axis, then read by [renderOnce] to crop the PNG to
                // the
                // composable's natural size instead of the whole sandbox.
                ee.schimke.composeai.renderer.ComposePreviewContentBox(
                  wrapWidth = spec.wrapWidth,
                  wrapHeight = spec.wrapHeight,
                  backgroundColor = bgColor,
                  sizeBounds = sizeBounds,
                  onMeasured = { w, h ->
                    measuredContent[0] = w
                    measuredContent[1] = h
                  },
                ) {
                  ComposeDataExtensionPipeline.Apply(
                    extensions = previewOverrideExtensions.plan(spec.overrides),
                    previewId = spec.previewId,
                    renderMode = spec.renderMode,
                    sink = RecordingExtensionCompositionSink(),
                  ) {
                    // Trampoline through a @Composable so the reflective invocation lands inside
                    // the
                    // running composition. Mirrors `:renderer-desktop`'s InvokeComposable. Honours
                    // `@PreviewWrapper(SomeProvider::class)` by routing through the wrapper's
                    // `Wrap`.
                    if (isLottie) {
                      // Drive the file-discovered Lottie through the draw-time `progress` lambda
                      // reading the snapshot state, so a held-session scrub (mutating
                      // `lottieProgressState`) repaints on the next `render()` by redraw alone — no
                      // recomposition required. This is the same redraw-only path
                      // `renderLottieApng`
                      // uses to sweep a single held scene into GIF frames. Shadow
                      // `LocalLottieProgress`
                      // to null here so the overload's draw-time `progress()` wins; the outer
                      // provide
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
                        // `PreviewWrapperProvider` FQN) replaces the preview's own
                        // `@PreviewWrapper`
                        // —
                        // "render this preview under theme X" — but only when it resolves; a
                        // stale/misspelled FQN falls back to the declared wrapper.
                        themeProviderFqn = spec.overrides?.themeProvider,
                        // The provider's first value for a `@PreviewParameter` preview; empty for a
                        // plain parameterless one.
                        previewArgs = previewArgs,
                      )
                    }
                  }
                }
              }
              // `@Preview(showSystemUi = true)` (issue #1930) — wrap the composition in the
              // synthetic
              // [ee.schimke.composeai.renderer.SystemBarsFrame] so the daemon's desktop capture
              // draws
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
                    // Same conversion as every other uiMode-taking call, so there is one place to
                    // get it right. Behaviourally identical here — SystemBarsFrame inspects the
                    // night-YES bit alone, so 0x10 and 0 are the same to it — but leaving a second
                    // hand-rolled mapping around is what made the scroll dispatch copy the wrong
                    // one.
                    uiMode = RenderSpec.uiModeBits(spec.uiMode)
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
    } finally {
      // Composition is done (or threw) — drop the process-global locale switch so it never outlives
      // the composition window, whether or not the scene is held past this point.
      localeScope.close()
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
   * One frame whose *pixels are thrown away* — the settling render [ScenePointerDispatch.press]
   * runs so Compose's gesture detectors observe a press before the next event can arrive.
   *
   * Under the same JVM-default-`Locale` override [renderOnce] wraps its frames in, and for the same
   * reason: a press can compose new content, and `rememberResourceEnvironment` caches what it
   * resolves — so a settling frame run at the host default would bake default-language
   * `stringResource(...)` text into a `localeTag` preview that the localized capture afterwards
   * cannot undo.
   *
   * The returned snapshot is closed rather than left to a cleaner: it owns native Skia memory, and
   * a held session presses many times between captures.
   */
  @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
  internal fun renderSettlingFrame(state: SceneState, nanoTime: Long) {
    withPreviewLocale(state.spec.localeTag) { state.scene.render(nanoTime = nanoTime).close() }
  }

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
    /**
     * Wall-clock nanoseconds already spent on this render *before* this call — currently the
     * `@ScrollingPreview(END)` drive, which runs ahead of `renderOnce` (see [render]) but is part
     * of the render body by any honest accounting: it can cost tens of scene renders. Folded into
     * the reported `tookMs` so the daemon's latency and cost-model numbers cover the real work. `0`
     * for every other caller, including the interactive session.
     */
    elapsedBeforeNs: Long = 0L,
  ): RenderResult {
    val startNs = System.nanoTime() - elapsedBeforeNs

    // Flush snapshot state written out-of-composition since the last render so the held scene
    // observes it before painting. The held-session scrub path mutates `lottieProgressState` from
    // the scene thread *between* renderOnce calls
    // (DesktopInteractiveSession.dispatchLottieProgress);
    // the file-Lottie content reads it in its draw-time `progress` lambda, but that read only
    // invalidates once apply-notifications fire. Same pairing `:renderers:desktop`'s
    // `renderLottieApng`
    // uses (`sendApplyNotifications()` after each write, before `render()`). Event-driven writes
    // (clicks) are flushed by the scene's own pointer processing; this covers the out-of-band
    // write.
    // Harmless for the one-shot path — nothing is pending right after setUp.
    androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()

    // Re-apply the `localeTag` JVM-default-Locale override for the frame render (see
    // [setUp] — it is deliberately not held on [SceneState] across idle time). A held
    // interactive/recording session recomposes here on each input, and
    // `rememberResourceEnvironment`
    // re-reads `androidx.compose.ui.text.intl.Locale.current`, so the override must be live for the
    // frame to keep resolving CMP `stringResource(...)` in the target locale; restored immediately
    // after the capture so the switch never spans two renders.
    val localeScope = enterPreviewLocale(state.spec.localeTag)
    val rawImage =
      try {
        // Render two frames so any LaunchedEffect / animations have a tick to settle. Same
        // reasoning
        // as `:renderer-desktop`'s renderPreview.
        trace.section("compose:frame") { renderFrame(state, useWallClockFrameTime) }
        trace.section("compose:captureFrame") { renderFrame(state, useWallClockFrameTime) }
      } finally {
        localeScope.close()
      }
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
    // `overrides.talkBack` composites the TalkBack focus overlay onto the captured frame. Applied
    // here — the single encode every non-recording desktop capture funnels through — so the
    // override means the same thing on a one-shot `/render` snapshot and on the *live* lane, whose
    // frames come from a held interactive session ([DesktopInteractiveSession.render] → this
    // function). It used to be composited only in `DesktopRecordingSession.frameBytes`, so the
    // serve viewer's "Accessibility (TalkBack)" toggle reached the daemon and then painted nothing:
    // `stream/start` is layered on an interactive session, not a recording. Recordings keep their
    // own per-frame path (they walk the focus stops over time) and never call renderOnce, so the
    // overlay is never applied twice.
    val overlaidBytes =
      if (state.spec.overrides?.talkBack == true) talkBackOverlaid(state, pngData.bytes)
      else pngData.bytes
    // Round clip goes LAST, over the overlay. The overlay draws in the frame's full rectangle —
    // its caption spans nearly the whole bottom edge — so clipping first and compositing after
    // would repaint opaque pixels outside the ellipse and a round Wear frame would lose its mask.
    // Clipping after costs the corners of the overlay instead, which is the right trade: the
    // device shape is what the frame is claiming to be.
    val pngBytes =
      if (state.spec.isRoundComposePreview()) applyRoundClip(overlaidBytes) else overlaidBytes

    state.outputFile.parentFile?.mkdirs()
    trace.section("render:writePng") {
      fileSystem.write(state.outputFile.path.toPath()) { write(pngBytes) }
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

    // compose/semantics, compose/semantics-wireframe, compose/spatial-semantics, layout/inspector,
    // and compose/figma-svg are all written by the shared post-capture extensions in the loop below
    // (the same seam the Android engine uses). The desktop-only figma-svg fidelity harness runs
    // after that loop, once the SVG has been written.

    // Post-capture data-artifact extensions — the portable PostCaptureProcessor seam shared with
    // the
    // Android engine (its `builtDataArtifactExtensions` loop). compose/semantics is produced here
    // through the shared ComposeSemanticsExtension rather than an inline `writeArtifacts` call; the
    // remaining sidecars above migrate onto this loop in follow-ups. Each processor is wrapped in
    // try/catch so one failing never strands the PNG or the other sidecars.
    run {
      val semanticsRoot = state.scene.semanticsOwners.firstOrNull()?.unmergedRootSemanticsNode
      if (semanticsRoot != null && dataArtifactExtensions.isNotEmpty()) {
        val previewId = state.spec.previewId ?: state.spec.outputBaseName
        val contextData =
          ExtensionContextData.of(
            *buildList {
                add(RenderArtifactContextKeys.RootDir provides dataDir)
                add(RenderArtifactContextKeys.OutputBaseName provides state.spec.outputBaseName)
                // Thread the protocol previewId when present so extensions that key their dir off
                // it
                // (the wireframe/spatial producer) resolve the same path the inline call did.
                state.spec.previewId?.let { add(RenderArtifactContextKeys.PreviewId provides it) }
                add(RenderArtifactContextKeys.SemanticsRoot provides semanticsRoot)
                add(RenderArtifactContextKeys.Density provides state.spec.density)
                add(
                  RenderArtifactContextKeys.SlotTables provides
                    state.slotTableCapture?.snapshot().orEmpty()
                )
                add(RenderArtifactContextKeys.FontScale provides (state.spec.fontScale ?: 1.0f))
                add(RenderArtifactContextKeys.OutputPng provides state.outputFile)
                add(RenderArtifactContextKeys.RoundClip provides state.spec.isRoundComposePreview())
                // The background this render painted behind the composable, so the figma-svg
                // export lays the same colour down as its bottom layer (issue #2884). Resolved the
                // same way the render itself resolves `bgColor` above; a transparent result
                // publishes nothing, keeping component exports background-free.
                previewBackgroundHex(state.spec)?.let {
                  add(RenderArtifactContextKeys.PreviewBackground provides it)
                }
                // …and the shape it was asked for in, if it was. The figma-svg export injects
                // nothing unless a mode was requested (see the key's KDoc).
                state.spec.svgBackground?.let {
                  add(RenderArtifactContextKeys.SvgBackgroundMode provides it)
                }
              }
              .toTypedArray()
          )
        val productStore = RecordingDataProductStore()
        for (ext in dataArtifactExtensions) {
          if (ext !is PostCaptureProcessor) continue
          try {
            trace.section("dataArtifact:${ext.id}") {
              ext.process(
                ExtensionPostCaptureContext(
                  extensionId = ext.id,
                  previewId = state.spec.previewId,
                  renderMode = state.spec.renderMode,
                  products = productStore.scopedFor(ext),
                  data = contextData,
                )
              )
            }
          } catch (t: Throwable) {
            System.err.println(
              "RenderEngine: ${ext.id} data write failed for ${state.spec.outputBaseName}: " +
                "${t.javaClass.simpleName}: ${t.message}"
            )
          }
        }
        // Desktop-only figma-svg fidelity harness (opt-in via -Dcomposeai.figma.fidelity=true): the
        // shared ComposeFigmaSvgExtension in the loop above wrote compose/figma-svg; rasterise it
        // and score against this render, dropping a `render | figma-svg | diff` composite. Android
        // has no equivalent.
        if (FigmaSvgFidelity.enabled()) {
          try {
            val previewDir = dataDir.resolve(previewId)
            FigmaSvgFidelity.write(
              previewDir = previewDir,
              svgFile = previewDir.resolve(ComposeFigmaSvgDataProducer.FILE_SVG),
              renderPng = state.outputFile,
            )
          } catch (t: Throwable) {
            System.err.println(
              "RenderEngine: figma-svg fidelity failed for ${state.spec.outputBaseName}: " +
                "${t.javaClass.simpleName}: ${t.message}"
            )
          }
        }
      }
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
      outputBaseName = state.spec.outputBaseName,
      // The interactive session calls this directly, once per input, and never goes through
      // `render`'s setUp/tearDown wrapper — so this is the trace that path reports. The one-shot
      // `render` overwrites it with a wider snapshot that also covers setUp and tearDown.
      trace = trace.renderTrace(),
    )
  }

  private fun renderFrame(state: SceneState, useWallClockFrameTime: Boolean) =
    when {
      useWallClockFrameTime -> state.scene.render(nanoTime = currentFrameNanoTime())
      // A scroll drive ran and left the frame clock mid-timeline — keep going from there rather
      // than resetting to zero. See [SceneState.virtualFrameNanos].
      state.virtualFrameNanos > 0L -> state.scene.render(nanoTime = state.nextVirtualFrameNanos())
      else -> state.scene.render()
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

  private fun RenderSpec.isRoundComposePreview(): Boolean {
    val kindIsCompose = kind == null || kind.equals("COMPOSE", ignoreCase = true)
    if (!kindIsCompose) return false
    val lower = device?.lowercase() ?: return false
    return lower.contains("_round") ||
      lower.contains("isround=true") ||
      lower.contains("shape=round")
  }

  /**
   * Composite the TalkBack focus overlay onto [pngBytes] — the still-frame twin of
   * `DesktopRecordingSession.talkBackFrameBytes`. A still has no timeline to walk, so it pins the
   * **first** focus stop (what TalkBack lands on when the screen gains focus); the traversal
   * numbers drawn on every stop carry the rest of the order. Returns [pngBytes] unchanged when the
   * scene exposes no semantics owner, has no focus stops, or the overlay fails — an overlay is a
   * visualization and must never strand the render it decorates.
   */
  @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
  private fun talkBackOverlaid(state: SceneState, pngBytes: ByteArray): ByteArray =
    try {
      val root = state.scene.semanticsOwners.firstOrNull()?.unmergedRootSemanticsNode
      if (root == null) pngBytes
      else {
        val nodes = DesktopAccessibilityNodeExtractor.extractNodes(root)
        DesktopTalkBackFocusOverlay.overlayPngBytes(pngBytes, nodes, focusedStop = 0) ?: pngBytes
      }
    } catch (t: Throwable) {
      System.err.println(
        "RenderEngine: talkBack overlay failed for ${state.spec.outputBaseName}: " +
          "${t.javaClass.simpleName}: ${t.message}"
      )
      pngBytes
    }

  private fun applyRoundClip(sourceBytes: ByteArray): ByteArray {
    val source =
      java.io.ByteArrayInputStream(sourceBytes).use { javax.imageio.ImageIO.read(it) }
        ?: return sourceBytes
    val output =
      java.awt.image.BufferedImage(
        source.width,
        source.height,
        java.awt.image.BufferedImage.TYPE_INT_ARGB,
      )
    val g = output.createGraphics()
    try {
      g.setRenderingHint(
        java.awt.RenderingHints.KEY_ANTIALIASING,
        java.awt.RenderingHints.VALUE_ANTIALIAS_ON,
      )
      g.clip =
        java.awt.geom.Ellipse2D.Float(0f, 0f, source.width.toFloat(), source.height.toFloat())
      g.drawImage(source, 0, 0, null)
    } finally {
      g.dispose()
    }
    val out = java.io.ByteArrayOutputStream()
    javax.imageio.ImageIO.write(output, "PNG", out)
    return out.toByteArray()
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
  /**
   * Run [block] with composable-level composition spans recording into [trace], when the opt-in is
   * on. Off, this is a straight call — no tracer installed, so the compiler-generated trace call
   * sites short-circuit and cost a boolean.
   */
  private fun <T> compositionTracing(trace: PerfettoTraceDataProducer.Recorder, block: () -> T): T =
    if (CompositionTracing.enabled()) {
      CompositionTracing.record(
        beginSection = { name, category -> trace.beginSection(name, category) },
        endSection = { trace.endSection() },
        block = block,
      )
    } else block()

  fun tearDown(state: SceneState) {
    try {
      val previewId = state.spec.previewId
      if (sceneLifecycleListener != null && previewId != null) {
        try {
          sceneLifecycleListener.invoke(previewId, null)
        } catch (_: Throwable) {
          // Already reported at install time; a teardown failure has nowhere useful to go and must
          // not stop the scene from closing.
        }
      }
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
    /**
     * Frame timestamp the next one-shot capture frame renders at, in nanoseconds, or `0` for the
     * default frozen-at-zero clock.
     *
     * [ImageComposeScene.render] takes the frame time `withFrameNanos` and every Compose animation
     * sees, and the one-shot path deliberately pins it to zero so a preview renders
     * deterministically — nothing advances, so nothing depends on how long the render took. The
     * `@ScrollingPreview(END)` drive is the one thing here that *needs* time to pass (an animated
     * `scrollBy` has to actually run), so it ticks this cursor forward and leaves it where it
     * finished. The capture frames then continue from there rather than snapping back to zero,
     * which would hand every in-flight animation a large negative delta on the very frame being
     * captured.
     *
     * Stays `0` for every preview without an END drive, so their frame clock — and their pixels —
     * are exactly what they were.
     */
    internal var virtualFrameNanos: Long = 0L

    /** Advances [virtualFrameNanos] by one 60 Hz frame and returns the new timestamp. */
    internal fun nextVirtualFrameNanos(): Long {
      virtualFrameNanos += FRAME_INTERVAL_NANOS
      return virtualFrameNanos
    }

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
        // The night flip and the resolved background apply to every scroll mode, LONG and GIF
        // included — a stitched strip or a scroll GIF of a dark preview is as wrong in light
        // colours as a still is. This is the daemon's own dispatch, so leaving the argument
        // defaulted would have fixed the standalone renderer and left every daemon-served scroll
        // data product rendering light on white.
        //
        // Via [RenderSpec.uiModeBits] rather than an inline `if (DARK) 0x20 else 0`: an explicit
        // `uiMode=light` has to send 0x10, because 0 means "undefined" and hands the decision back
        // to the host's theme probe.
        uiMode = RenderSpec.uiModeBits(spec.uiMode),
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
   * `@ScrollingPreview(END)` — drive the preview's scrollable to its content end before the frame
   * is captured, so the daemon's PNG (and the semantics / layout / figma-svg read off the same
   * scene) show the frame the Gradle render ships, not the resting top.
   *
   * The desktop daemon captures through [ImageComposeScene], which has neither a test main-clock
   * nor `onNode` interactions — the two things `:renderer-desktop`'s `renderScrollPreview` leans on
   * for the same job. It does expose both halves needed to do it directly, though:
   * `semanticsOwners` reaches the same `SemanticsActions.ScrollBy` the test harness invokes, and
   * `render(nanoTime)` is the frame clock an animated scroll advances on. So this drives the scroll
   * the same way, ticking the scene forward by hand instead of a `MainTestClock`.
   *
   * Progress is measured from `ScrollAxisRange.value` for the reason the CMP renderer's END path
   * measures it: `ScrollBy` animates, and a lazy container's `maxValue` is an estimate that firms
   * up as items compose, so neither the requested delta nor the remaining distance is a reliable
   * account of what actually moved. The loop stops on the first step that doesn't move.
   *
   * That axis range is also why `maxScrollPx` is a **coarse** bound here. A `LazyColumn` reports
   * `value` in its own estimated content space (index × average item size), not layout pixels, so
   * the cap is checked in whatever units the scroller reports and is only enforced between steps —
   * one step can overshoot it. It does what the annotation needs it for, which is bounding a drive
   * over a very long list, but it is not a pixel-exact stop position and shouldn't be read as one.
   *
   * `reduceMotion` is deliberately not honoured here, unlike the Android daemon: it gates Wear's
   * `TransformingLazyColumn` edge transforms, and Wear Compose is Android-only, so there is nothing
   * on this backend for it to flatten.
   *
   * No-ops (leaving the frame clock at zero, so the capture is bit-for-bit what it was) when the
   * preview has no END intent or carries nothing scrollable.
   */
  @OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
  )
  internal fun driveStaticScrollToEnd(state: SceneState) {
    val previewId = state.spec.previewId ?: return
    val intent = loadPreviewIndexLazily().staticScrollFor(previewId) ?: return
    // Under the same JVM-default-`Locale` override `renderOnce` wraps its frames in. The drive
    // performs the *first* layout and composes every lazy item it scrolls past, and
    // `rememberResourceEnvironment` caches what it resolves — so without this a localized END
    // capture would bake default-language `stringResource(...)` text into the items the drive
    // brought on screen, and re-applying the locale for the final frames alone would not undo it.
    withPreviewLocale(state.spec.localeTag) { driveStaticScrollToEndLocalized(state, intent) }
  }

  @OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
  )
  private fun driveStaticScrollToEndLocalized(state: SceneState, intent: ScrollCaptureDto) {
    val axisKey =
      if (intent.axis.equals("HORIZONTAL", ignoreCase = true))
        SemanticsProperties.HorizontalScrollAxisRange
      else SemanticsProperties.VerticalScrollAxisRange

    // Semantics only exist once the scene has laid out, and `setUp` deliberately doesn't render.
    // At timestamp zero, and *without* touching the frame cursor: a preview that turns out to have
    // nothing scrollable must be captured at the same deterministic frame zero as an ordinary
    // render, which is what the "capturing the initial frame" message below promises. The cursor
    // only starts moving once a scrollable is actually found.
    state.scene.render()

    // Re-resolved on each read rather than held: the drive recomposes the scene, and a
    // `SemanticsNode` is a snapshot of one composition pass.
    //
    // First in traversal order, *not* the largest — `DesktopScrollRenderer` drives
    // `nodes.firstOrNull()`, and `PreviewIndex.staticScrollFor` documents the contract as "the
    // first scrollable on the annotated axis". A screen with two same-axis scrollers must settle
    // in the same place whether it came from `compose-preview serve` or the Gradle batch render;
    // agreeing with the other backend matters more than this one picking the scroller it guesses
    // is the interesting one.
    fun mainScroller(): SemanticsNode? {
      val root = state.scene.semanticsOwners.firstOrNull()?.unmergedRootSemanticsNode ?: return null
      var found: SemanticsNode? = null
      fun walk(node: SemanticsNode) {
        if (found != null) return
        if (node.config.getOrNull(axisKey) != null) {
          found = node
          return
        }
        node.children.forEach(::walk)
      }
      walk(root)
      return found
    }

    fun axisRange(): ScrollAxisRange? = mainScroller()?.config?.getOrNull(axisKey)

    val initial = axisRange()
    if (initial == null) {
      System.err.println(
        "@ScrollingPreview(END) on ${state.spec.outputBaseName}: nothing scrollable on axis " +
          "${intent.axis} — capturing the initial frame."
      )
      return
    }

    val cap = intent.maxScrollPx?.takeIf { it > 0 }?.toFloat() ?: Float.POSITIVE_INFINITY
    val startPx = initial.value()
    var scrolledPx = 0f
    for (step in 0 until END_DRIVE_MAX_STEPS) {
      val range = axisRange() ?: break
      val remaining = (range.maxValue() - range.value()).coerceAtLeast(0f)
      if (remaining <= SCROLL_SETTLED_EPSILON_PX) break
      val headroom = (cap - scrolledPx).coerceAtLeast(0f)
      if (headroom <= SCROLL_SETTLED_EPSILON_PX) break

      val before = range.value()
      val delta = minOf(remaining, headroom)
      val action = mainScroller()?.config?.getOrNull(SemanticsActions.ScrollBy)?.action ?: break
      if (axisKey == SemanticsProperties.HorizontalScrollAxisRange) action(delta, 0f)
      else action(0f, delta)

      // `ScrollBy` animates; tick the scene until it lands, or the next iteration would measure a
      // scroll still in flight and the drive would creep instead of converge.
      repeat(END_STEP_SETTLE_FRAMES) {
        state.scene.render(nanoTime = state.nextVirtualFrameNanos())
      }

      val advanced = (axisRange()?.value() ?: before) - before
      if (advanced <= SCROLL_SETTLED_EPSILON_PX) break
      scrolledPx = (axisRange()?.value() ?: before) - startPx
    }

    // Let anything the scroll landing triggers finish before the capture frames.
    repeat(END_POST_SCROLL_SETTLE_FRAMES) {
      state.scene.render(nanoTime = state.nextVirtualFrameNanos())
    }
    // Reported in the scroller's own axis-range units, not pixels — see the note on `maxScrollPx`
    // above.
    System.err.println(
      "@ScrollingPreview(END) on ${state.spec.outputBaseName}: drove the scrollable " +
        "${scrolledPx.toInt()} unit(s) to the content end."
    )
  }

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
   * timeline into a looping APNG at `<outputDir>/<outputBaseName>_animated.png` by delegating to
   * `:renderer-desktop`'s [ee.schimke.composeai.renderer.renderLottieApng]. The default window is
   * the asset's own `durationFrames / frameRate`, so "animate for the preview's default duration"
   * needs no extra payload — a 2s clip plays for 2s. APNG rather than GIF so the anti-aliased edge
   * over the asset's transparent background is carried by real alpha (see `renderLottieApng`).
   *
   * The asset is resolved by [LottiePreview]/`renderLottieApng` off the **context classloader**
   * (which `loadLottieAsset` consults first), so we install [classLoader] for the duration of the
   * capture — the disposable child loader carries the consumer's processed resources
   * (CLASSLOADER.md § Risks 2). Mirrors [runScrollScenario]'s shape: returns a [RenderResult] whose
   * `pngPath` points at the produced APNG (the field is the generic artefact path, same as the
   * scroll-GIF path).
   *
   * Throws [IllegalStateException] when the spec carries no `assetPath`, or when the APNG writer
   * declined — the dispatcher surfaces it like any other render failure.
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
    val outputFile = File(outputDir, "${spec.outputBaseName}_animated.png")
    outputFile.parentFile?.mkdirs()

    val previousContext = Thread.currentThread().contextClassLoader
    Thread.currentThread().contextClassLoader = classLoader
    val startNs = System.nanoTime()
    try {
      ee.schimke.composeai.renderer.renderLottieApng(
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

    /** One 60 Hz frame, in nanoseconds — the tick [SceneState.virtualFrameNanos] advances by. */
    private const val FRAME_INTERVAL_NANOS: Long = 16_666_667L

    /** Sub-pixel scroll movement that counts as "didn't move". */
    private const val SCROLL_SETTLED_EPSILON_PX: Float = 0.5f

    /**
     * Frames [driveStaticScrollToEnd] lets an animated `ScrollBy` run for before re-reading the
     * axis range. ~0.5 s, comfortably past Compose's default scroll animation.
     */
    private const val END_STEP_SETTLE_FRAMES: Int = 32

    /**
     * Iteration bound for the END drive. A lazy container only firms up its estimated extent as
     * items compose, so reaching a long list's true end takes several rounds of "jump to the end I
     * can currently see". The loop exits on the first step that makes no progress, so this is a
     * runaway guard rather than the usual exit.
     */
    private const val END_DRIVE_MAX_STEPS: Int = 60

    /** Frames of settle after the drive, so scroll-triggered chrome isn't caught mid-reveal. */
    private const val END_POST_SCROLL_SETTLE_FRAMES: Int = 60

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

    /**
     * The base BCP-47 tag actually applied for a `localeTag` override, or `null` when the spec
     * carries no (blank) override. Pseudolocales (`en-XA`, `ar-XB`) fold to their base (`en` /
     * `ar`) — they aren't real BCP-47 locales, so `LocaleList("en-XA")` / `Locale.forLanguageTag`
     * would throw or silently degrade depending on the JVM's ICU build. Shared by [localeProviders]
     * (the composition `LocalProvidableLocaleList`) and the JVM-default-`Locale` override in
     * [setUp] so both steer off the same effective tag. The visual pseudolocalisation knob
     * (LayoutDirection.Rtl for `ar-XB`) is applied separately by
     * `PseudolocaleOverrideExtensionDesktop`'s around-composable; text-content pseudolocalisation
     * is Android-only — see `site/reference/pseudolocale.md`.
     */
    internal fun effectiveLocaleTag(localeTag: String?): String? {
      val tag = localeTag?.takeIf { it.isNotBlank() } ?: return null
      return ee.schimke.composeai.data.pseudolocale.Pseudolocale.fromTag(tag)?.baseTag ?: tag
    }

    /**
     * Point the JVM default [java.util.Locale] at the [effectiveTag] override for a render's
     * duration and return the previous default so [setUp]/[tearDown] can restore it — or `null`
     * when there is no override (nothing to restore).
     *
     * This is the second half of applying `localeTag`, complementing [localeProviders]. CMP string
     * resources (`org.jetbrains.compose.components:components-resources`) resolve their locale via
     * `rememberResourceEnvironment()` → `androidx.compose.ui.text.intl.Locale.current`, which on
     * Skiko/desktop reads the JVM default `Locale` — **not** the `LocalProvidableLocaleList`
     * composition local [localeProviders] sets (that local only steers ui-text locale-aware layout
     * + the RTL direction). Without this, a `@Preview(locale = "de")` override reached the layout
     *   direction but `stringResource(...)` still rendered the base (English) copy.
     *
     * **Not safe to call bare.** The switch is process-global and held sessions each compose on
     * their own thread, so every use goes through [withPreviewLocale] / [enterPreviewLocale], which
     * serialise it.
     */
    internal fun overrideJvmDefaultLocale(effectiveTag: String?): java.util.Locale? {
      effectiveTag ?: return null
      val previous = java.util.Locale.getDefault()
      java.util.Locale.setDefault(java.util.Locale.forLanguageTag(effectiveTag))
      return previous
    }

    /** Restore a JVM default [java.util.Locale] captured by [overrideJvmDefaultLocale]. */
    internal fun restoreJvmDefaultLocale(previous: java.util.Locale?) {
      if (previous != null) java.util.Locale.setDefault(previous)
    }

    /**
     * Process-wide gate over the JVM default [java.util.Locale] (issue #3721).
     *
     * `fair = true` deliberately. Unlocalized renders are the readers here, and on a busy
     * multi-seat serve they never stop arriving — a non-fair lock would let them starve a localized
     * session's writer indefinitely.
     *
     * Static, not per-[RenderEngine]: the thing being guarded is one JVM-wide static, and a daemon
     * (or a test) can hold several engines.
     */
    private val localeGate = java.util.concurrent.locks.ReentrantReadWriteLock(true)

    /** An entered locale scope, released exactly once by [PreviewLocaleScope.close]. */
    internal class PreviewLocaleScope internal constructor(private val release: () -> Unit) {
      fun close() = release()
    }

    /**
     * Claim the locale gate for a render of [localeTag] and apply the override, returning the scope
     * that releases both. **The caller must `close()` it in a `finally`.**
     *
     * [withPreviewLocale] is the form to reach for; this exists for [setUp], whose scope is a
     * 180-line `try`/`catch` that a lambda would re-indent wholesale.
     *
     * The reader/writer split is inverted from the usual intuition, and that inversion is the whole
     * design:
     * - a render with **no** locale override is a **reader** — it doesn't touch the global, it only
     *   depends on it staying put. Any number run concurrently, and the cost is one uncontended
     *   read-lock acquire per frame, which is what makes this affordable in the common case.
     * - a render **with** an override is a **writer** — it mutates the global, so it excludes
     *   everyone.
     *
     * Without this, two held sessions (each on its own scene executor — one-shot renders are
     * already serialised onto [DesktopHost]'s single render thread, held sessions are not) could
     * sit inside a locale scope at the same time: the unlocalized one resolves
     * `stringResource(...)` under the other's language and `rememberResourceEnvironment` caches it,
     * and two localized scopes could restore out of order and leave the daemon's default moved for
     * good.
     *
     * **Enter this on the thread that composes, never around a cross-thread wait.** Held sessions
     * reach the engine through `submit(...).get()`; a scope entered on the calling side of that
     * wait would deadlock against the executor thread that needs it.
     */
    internal fun enterPreviewLocale(localeTag: String?): PreviewLocaleScope {
      val effectiveTag = effectiveLocaleTag(localeTag)
      if (effectiveTag == null) {
        localeGate.readLock().lock()
        return PreviewLocaleScope { localeGate.readLock().unlock() }
      }
      // A read→write upgrade deadlocks on ReentrantReadWriteLock rather than failing. It can't
      // happen while one SceneState means one locale for its whole render — this turns a future
      // violation of that invariant into a diagnosable exception instead of a hung daemon.
      check(localeGate.readHoldCount == 0) {
        "enterPreviewLocale($effectiveTag) would upgrade a read lock this thread already holds; a " +
          "localized render must not nest inside an unlocalized one"
      }
      localeGate.writeLock().lock()
      val previousDefaultLocale = overrideJvmDefaultLocale(effectiveTag)
      return PreviewLocaleScope {
        restoreJvmDefaultLocale(previousDefaultLocale)
        localeGate.writeLock().unlock()
      }
    }

    /** [enterPreviewLocale] around [block], released on the way out however it leaves. */
    internal fun <T> withPreviewLocale(localeTag: String?, block: () -> T): T {
      val scope = enterPreviewLocale(localeTag)
      try {
        return block()
      } finally {
        scope.close()
      }
    }

    private fun localeProviders(localeTag: String?): Array<ProvidedValue<*>> {
      val local = localProvidableLocaleListOrNull() ?: return emptyArray()
      val effectiveTag = effectiveLocaleTag(localeTag) ?: return emptyArray()
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

/** A Compose Resources reader whose lookup scope matches the preview's disposable classloader. */
@OptIn(ExperimentalResourceApi::class)
internal fun previewResourceReader(classLoader: ClassLoader): ResourceReader =
  JvmResourceReader(classLoader)

/**
 * The flat colour a render paints behind the composable: the per-render "crisp outline" override
 * first, then `@Preview(backgroundColor = …)`, then plain `@Preview(showBackground = true)`'s
 * theme-shaped default. [Color.Transparent] when the preview opted into none — the common component
 * case.
 *
 * Precedence and the light/dark default live in [PreviewBackground] so both backends and both
 * renderers agree; in particular `showBackground = true` on a dark preview is not white.
 */
internal fun previewBackgroundColor(spec: RenderSpec): Color =
  Color(
    PreviewBackground.resolveArgb(
      showBackground = spec.showBackground,
      backgroundColor = spec.backgroundColor,
      night = spec.uiMode == RenderSpec.SpecUiMode.DARK,
      clearBackground = spec.clearBackground,
    )
  )

/**
 * [previewBackgroundColor] as the `#AARRGGBB` string the data-product wire format uses, or null
 * when the render drew on transparency and the figma-svg export should stay background-free
 * (issue #2884).
 */
internal fun previewBackgroundHex(spec: RenderSpec): String? =
  previewBackgroundColor(spec)
    .takeIf { it.alpha > 0f }
    ?.let { "#${String.format(java.util.Locale.US, "%08X", it.toArgb())}" }

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
   * Per-render background mode for the `compose/figma-svg` export
   * (`PreviewOverrides.svgBackground`) — `NONE`, `DEVICE`, `CONTENT_SHAPE`, or `FULL_BLEED`. Only
   * that export reads it; it changes nothing about the rendered PNG.
   *
   * Null means the caller said nothing and the daemon-wide `composeai.svg.background` default
   * applies, which is itself `NONE`: the export is background-free unless asked, because an
   * injected fill is an opaque shape spanning the canvas — hard to remove once baked, easy to add
   * back — so a *declared* `showBackground` is not enough to earn one.
   */
  val svgBackground: FigmaSvgBackgroundMode? = null,
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
  /**
   * FQN of the `PreviewParameterProvider` from `@PreviewParameter` on the preview function's
   * parameter, when discovery recorded one. Sourced from `previews.json` (the upstream annotation
   * has `AnnotationRetention.BINARY` and is invisible to `Method.annotations` at runtime — same
   * provenance as [wrapperClassName]). When set the render body resolves and renders one of the
   * provider's values — [previewParameterRow]'s, or the first under the bare id, matching
   * `:daemon:android`'s single-frame contract; the per-value fan-out stays with the standalone
   * renderer. Null is the plain parameterless preview.
   */
  val previewParameterProviderClassName: String? = null,
  /** Mirrors `@PreviewParameter.limit`. `Int.MAX_VALUE` is the annotation default. */
  val previewParameterLimit: Int = Int.MAX_VALUE,
  /**
   * Which `@PreviewParameter` row to bind — a fan-out suffix (`Dark`) or `PARAM_<idx>`, per
   * [ee.schimke.composeai.renderer.PreviewParameterSupport.resolve]. Set by [PreviewManifestRouter]
   * when the inbound previewId was row-addressed as `<baseId>_<row>` (issue #3749). Null keeps the
   * historical "render value 0 under the bare id" contract.
   */
  val previewParameterRow: String? = null,
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
     * The `@Preview(uiMode = …)` **Configuration bits** for a [SpecUiMode], for the renderer entry
     * points that take the raw int rather than the enum.
     *
     * All three states are distinct and the distinction matters:
     * [ee.schimke.composeai.renderer.systemThemeFromUiMode] maps `0x20` → dark, `0x10` → light and
     * anything else → `Unknown`, and `Unknown` deliberately leaves `isSystemInDarkTheme()` to the
     * JVM's own theme probe. So collapsing [SpecUiMode.LIGHT] to `0` does not mean "light", it
     * means "ask the host" — and a request that explicitly asked for light would render dark on a
     * dark-themed machine.
     *
     * Easy to get wrong by copying the `if (DARK) 0x20 else 0` shape used for `SystemBarsFrame`,
     * which is correct there only because that consumer inspects the night-YES bit alone and so has
     * two states rather than three.
     */
    fun uiModeBits(mode: SpecUiMode?): Int =
      when (mode) {
        SpecUiMode.DARK -> 0x20 // UI_MODE_NIGHT_YES
        SpecUiMode.LIGHT -> 0x10 // UI_MODE_NIGHT_NO
        null -> 0 // UI_MODE_NIGHT_UNDEFINED — defer to the host
      }

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
        svgBackground =
          FigmaSvgBackgroundMode.parse(map["svgBackground"]) ?: defaults.svgBackground,
        overrides = map["overrides"]?.decodePreviewOverrides(),
        wrapperClassName = map["wrapperClassName"]?.takeIf { it.isNotBlank() },
        previewParameterProviderClassName =
          map["previewParameterProvider"]?.takeIf { it.isNotBlank() },
        previewParameterLimit =
          map["previewParameterLimit"]?.toIntOrNull() ?: defaults.previewParameterLimit,
        previewParameterRow = map["previewParameterRow"]?.takeIf { it.isNotBlank() },
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
private fun InvokeComposable(composableMethod: ComposableMethod, previewArgs: List<Any?>) {
  composableMethod.invoke(currentComposer, null, *previewArgs.toTypedArray())
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
  previewArgs: List<Any?> = emptyList(),
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
    InvokeComposable(composableMethod, previewArgs)
  } else {
    val (wrapMethod, wrapperInstance) = wrapper
    val body: @Composable () -> Unit = { InvokeComposable(composableMethod, previewArgs) }
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
 * is explicitly off. Embedding is **on by default** — a null/`sans-serif` `<text>` renders with a
 * substituted typeface, so embedding the real face only ever improves the export and degrades right
 * back to `sans-serif` when the face can't be resolved (offline / network error). Opt out with
 * `composeai.svg.embedFonts=false`; measuring fidelity (`composeai.figma.fidelity=true`) forces it
 * back on regardless, so the score reflects the embedded face rather than the browser's substitute.
 * Reuses the renderer's font cache dir / offline switch so a face is downloaded at most once per
 * environment.
 */
private fun figmaFontResolver(): FigmaFontResolver? {
  fun on(prop: String) = System.getProperty(prop)?.lowercase() == "true"
  val disabled = System.getProperty("composeai.svg.embedFonts")?.lowercase() == "false"
  if (disabled && !on("composeai.figma.fidelity")) return null
  return GoogleFontsWoff2Resolver(
    cacheDir = System.getProperty("composeai.fonts.cacheDir")?.let { File(it) },
    offline = on("composeai.fonts.offline"),
  )
}
