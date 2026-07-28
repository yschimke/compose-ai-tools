package ee.schimke.composeai.daemon

import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.reflect.ComposableMethod
import androidx.compose.runtime.reflect.getDeclaredComposableMethod
import androidx.compose.runtime.remember
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import ee.schimke.composeai.daemon.devices.DeviceDimensions
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.render.PreviewBackends
import ee.schimke.composeai.data.render.PreviewContext
import ee.schimke.composeai.data.render.PreviewDeviceSpec
import ee.schimke.composeai.data.render.extensions.ExtensionContextData
import ee.schimke.composeai.data.render.extensions.ExtensionContextValue
import ee.schimke.composeai.data.render.extensions.ExtensionPostCaptureContext
import ee.schimke.composeai.data.render.extensions.PostCaptureProcessor
import ee.schimke.composeai.data.render.extensions.RecordingDataProductStore
import ee.schimke.composeai.data.render.extensions.compose.ComposeDataExtensionPipeline
import ee.schimke.composeai.data.render.extensions.compose.RecordingExtensionCompositionSink
import ee.schimke.composeai.data.render.extensions.loadIrReplayClass
import ee.schimke.composeai.data.render.extensions.loadPreviewWrapperClass
import ee.schimke.composeai.data.render.extensions.provides
import ee.schimke.composeai.data.theme.NodeThemeFacts
import ee.schimke.composeai.data.theme.ThemeConsumerAttribution
import ee.schimke.composeai.data.theme.ThemePayload
import ee.schimke.composeai.renderer.AccessibilityDataProducts
import ee.schimke.composeai.renderer.FontFallbackException
import ee.schimke.composeai.renderer.FontResolutionDiagnostics
import ee.schimke.composeai.renderer.RenderWarningsSidecar
import ee.schimke.composeai.renderer.WearScrollSvgAssembler
import ee.schimke.composeai.renderer.AccessibilityHierarchyContextKeys
import ee.schimke.composeai.renderer.AccessibilityHierarchyExtension
import ee.schimke.composeai.renderer.uiautomator.UiAutomatorDataProducts
import ee.schimke.composeai.renderer.uiautomator.UiAutomatorHierarchyContextKeys
import ee.schimke.composeai.renderer.uiautomator.UiAutomatorHierarchyExtension
import java.io.File
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import ee.schimke.composeai.data.layoutinspector.ComposeFigmaSvgProduct
import ee.schimke.composeai.io.SystemFileSystem
import okio.ByteString.Companion.decodeBase64
import okio.Path.Companion.toPath

/**
 * Robolectric/Compose render body for the preview daemon — the per-preview inner loop that turns a
 * resolved class+method reference into a PNG on disk.
 *
 * **Duplicated from
 * [`renderer-android`'s `RobolectricRenderTest`](../../../../../../../renderers/android/src/main/kotlin/ee/schimke/composeai/renderer/RobolectricRenderTest.kt).**
 * Per
 * [DESIGN.md § 7](../../../../../../docs/daemon/DESIGN.md#7-sharing-strategy--what-crosses-the-boundary)
 * the v1 render body lives in two places — the standalone JUnit-driven renderer and the daemon — so
 * the daemon doesn't depend on the renderer's `@RunWith(ParameterizedRobolectricTestRunner)` entry
 * point. v2's reconciliation extracts the body into a shared helper. Until then any change to the
 * core render body landed here also has to land in `:renderer-android`'s `renderDefault` (and vice
 * versa); the `:samples:android-daemon-bench:composePreviewRender` task + CI pixel-diff catches
 * drift.
 *
 * **What's duplicated, what isn't.** This is the "small composable, no `@PreviewParameter`, no
 * `@AnimatedPreview`, no `@ScrollingPreview`" subset — the daemon's v1 surface only renders single
 * static previews. The fan-out / animation / GIF stitching paths from `RobolectricRenderTest` stay
 * behind the standalone renderer for now; B1.7+ revisits if the harness needs them.
 *
 * **Threading contract.** Called from inside [RobolectricHost.SandboxRunner.holdSandboxOpen], i.e.
 * the test thread of the dummy `@Test` runner, with the Robolectric sandbox classloader as the
 * context classloader. The Compose UI test rule (`createAndroidComposeRule`) is constructed
 * per-render — the warm runtime the daemon amortises is the Robolectric sandbox + JVM + JIT, not
 * the rule itself. Per-render `ActivityScenario` construction is what the JUnit runner already
 * pays; we just sidestep the `@RunWith` machinery.
 *
 * **No-mid-render-cancellation invariant** (DESIGN § 9). Cleanup runs in `try/finally`: `setContent
 * { }` on a fresh empty body to give Compose a frame to dispose `LaunchedEffect` /
 * `DisposableEffect`, then explicit `mainClock.advanceTimeBy(CAPTURE_ADVANCE_MS)` on the empty
 * tree. The `ActivityScenario` is closed by the rule's outer statement when `evaluate()` returns;
 * we never leave one open across renders. This is the Android equivalent of desktop's
 * `scene.close()` discipline (DESIGN § 10) — without the empty-setContent flush, a `LaunchedEffect`
 * holding a Job in the previous preview survives into the next render's composition and shows up as
 * cross-render visual drift.
 *
 * **B1.4 scope guard.** This file deliberately does NOT touch `ShadowPackageManager` cleanup,
 * `GoogleFontInterceptor` SandboxScope wiring, or the wider helper-by-helper audit — that's B1.7
 * (DESIGN § 11). For B1.4 we only ensure the render body itself does not introduce *new* leak
 * shapes; pre-existing additive state in helpers we call (e.g. the `addActivityIfNotPresent` call
 * for `ComponentActivity`) is left for B1.7 to reverse.
 */
class RenderEngine(
  /**
   * Directory under which PNG files are written. Defaults to the `composeai.render.outputDir`
   * system property (mirrors `:renderer-android`'s contract); falls back to
   * `${user.dir}/.compose-preview-history/daemon-renders/` so unit tests don't need to set the
   * property.
   */
  private val outputDir: File =
    File(
      System.getProperty(OUTPUT_DIR_PROP)
        ?: "${System.getProperty("user.dir")}/.compose-preview-history/daemon-renders"
    ),
  /**
   * D2 — root of the per-preview data-product output tree
   * (`<dataDir>/<previewId>/a11y-{atf,hierarchy}.json`). Defaults to `${outputDir.parent}/data` so
   * the layout sits next to the renders dir, matching the design doc's
   * `build/compose-previews/data/<id>/<kind>.json` convention. Unit tests override to a temp dir.
   *
   * `null` disables the a11y-on-render path entirely — useful for the harness fake-mode runs that
   * don't exercise the producer.
   */
  private val dataDir: File? = (outputDir.parentFile ?: outputDir).resolve("data"),
  /**
   * Registered [PreviewOverrideExtension]s the renderer queries on every render. The renderer
   * doesn't read individual override fields like `wallpaper` or `material3Theme` directly — each
   * registered planner inspects the merged [PreviewOverrides] and contributes its own
   * `AroundComposable` (or other) hook when its override applies.
   */
  // `internal` so `RobolectricHost.SandboxRunner.runHeldInteractiveSession` (same module) can
  // read it when wrapping `InvokeHeldComposable` with the planner output — interactive sessions
  // bypass the public `render(...)` path that the non-held / recording paths use, but still need
  // the same `AroundComposable` chain (canonical case: touch-overlay rings). Stays effectively
  // private outside `:daemon:android`.
  internal val previewOverrideExtensions: PreviewOverrideExtensions =
    PreviewOverrideExtensions.Empty,
  /**
   * Always-on data extensions (fonts, resources, i18n) that own their recorder + post-capture
   * artefact write. Distinct from [previewOverrideExtensions]: every factory here runs on every
   * render rather than gating on a `renderNow.overrides` field. Each factory is invoked per render
   * with the per-render Android [Context] so the extension can install recording
   * `CompositionLocal`s before composition starts; the same instance is then asked to write its
   * typed artefact during the post-capture pass.
   */
  private val dataArtifactExtensions: RenderDataArtifactExtensions =
    RenderDataArtifactExtensions.Empty,
) {

  /**
   * Renders one preview to a PNG on disk and returns a [RenderResult] populated with the absolute
   * `pngPath` and a `metrics` map containing `tookMs` (wall-clock of the render body, excluding
   * queue wait).
   *
   * @param spec what to render — class FQN, method name, sandbox dimensions.
   * @param requestId opaque id forwarded to the [RenderResult] so [RobolectricHost]'s queue can
   *   demux.
   */
  @OptIn(ExperimentalRoborazziApi::class)
  fun render(
    spec: RenderSpec,
    requestId: Long,
    classLoader: ClassLoader =
      RenderEngine::class.java.classLoader ?: ClassLoader.getSystemClassLoader(),
    /**
     * B2.3 — per-sandbox lifecycle counters owned by [RobolectricHost.SandboxRunner]. Captured at
     * sandbox-init time and incremented on every render-completion via
     * [SandboxMeasurement.collect]. Defaults to a fresh per-call instance for unit tests that drive
     * the engine directly without a sandbox; the resulting metrics still populate, just with a
     * sandbox-age that resets per test render.
     */
    sandboxStats: SandboxLifecycleStats = SandboxLifecycleStats(),
    /**
     * D2 / D2.2 — render in a11y mode (`LocalInspectionMode = false`) and dump `a11y-atf.json` +
     * `a11y-hierarchy.json` under [dataDir] so the daemon's data-product registry can surface them
     * as `a11y/atf` + `a11y/hierarchy`. Mirrors the design doc's "produce always, gate emission on
     * subscriptions" approach — the cost is a few ms per render.
     *
     * `null` (the default) means *resolve from context*: a11y mode is on iff
     * [RenderSpec.renderMode] is `"a11y"` (set by the daemon's `data/fetch` re-render path or by
     * the host's per-preview subscription state). Pass `true` / `false` explicitly to force the
     * mode regardless of context (used by tests).
     */
    runAccessibility: Boolean? = null,
  ): RenderResult {
    // Issue #1528 — scroll-scenario dispatch. When the dispatcher's `data/fetch` re-render path
    // queues `mode=scroll-long` / `scroll-gif`, route into the renderer's scroll handlers
    // (`renderer.handleLongCapture` / `renderer.handleGifCapture`) instead of the default
    // single-frame Compose path. The handlers write to the on-disk path the scroll registry
    // (`ScrollDataProductRegistry.fileFor`) reads back, so the second registry fetch sees the
    // file and emits `Ok(path)`. Looks up `ScrollCaptureDto` via the daemon's PreviewIndex
    // (`scrollCaptureFor`) — the previews.json path is loaded lazily via
    // `PreviewIndex.loadFromFile` against the `composeai.daemon.previewsJsonPath` system property
    // so the engine doesn't need a constructor-time previewIndex injection. The DTO carries
    // `axis` / `maxScrollPx` / `frameIntervalMs` straight from the annotation through the
    // gradle plugin's `dataProducts[].scroll` field.
    if (spec.renderMode == SCROLL_LONG_RENDER_MODE || spec.renderMode == SCROLL_GIF_RENDER_MODE) {
      return runScrollScenario(spec = spec, requestId = requestId, classLoader = classLoader)
    }
    // Full-page figma-svg for a scrolling preview (`compose/figma-svg-long`). Renders at an expanded
    // viewport so a virtualised LazyColumn composes every item, then emits the layered SVG over the
    // whole content instead of just the on-screen rows. SVG-only — the PNG scroll story stays LONG /
    // GIF above. Mirrors `:daemon:desktop`. See docs/design/SCROLLING_SVG.md.
    if (spec.renderMode == FIGMA_SVG_LONG_RENDER_MODE) {
      return runScrollSvgScenario(spec = spec, requestId = requestId, classLoader = classLoader)
    }
    val effectiveRunAccessibility = runAccessibility ?: (spec.renderMode == A11Y_RENDER_MODE)
    // Roborazzi defaults to "compare" mode — `captureRoboImage` reads the existing baseline at
    // the target path and *doesn't* write a new PNG. The daemon writes baselines, never compares,
    // so force record mode if the surrounding JVM didn't set it. Idempotent across renders;
    // mirrors the gradle-plugin's `RobolectricSystemProps.roborazzi.test.record = true` (see
    // `AndroidPreviewClasspath.kt`) which the standalone JUnit path relies on.
    if (System.getProperty("roborazzi.test.record") == null) {
      System.setProperty("roborazzi.test.record", "true")
    }

    outputDir.mkdirs()
    val outputFile = File(outputDir, "${spec.outputBaseName}.png")
    val startNs = System.nanoTime()
    val trace = PerfettoTraceDataProducer.recorder(spec.outputBaseName, backend = "android")
    val slotTableCapture = PreviewSlotTableCapture()
    val themeFallbackCapture = MaterialThemeFallbackCapture()
    // Opt-in (serve / bundle-daemon set it) — see [PLACEHOLDER_MISSING_RESOURCES_PROP]. Off for the
    // pack semantics daemon so a missing resource fails loudly instead of baking a placeholder.
    val placeholderMissingResources =
      System.getProperty(PLACEHOLDER_MISSING_RESOURCES_PROP) == "true"

    val isTile = spec.kind.equals(TILE_KIND, ignoreCase = true)
    val isNotification = spec.kind.equals(NOTIFICATION_KIND, ignoreCase = true)
    val isGlanceAppWidget = spec.kind.equals(GLANCE_APPWIDGET_KIND, ignoreCase = true)
    // v5 IR replay: a bundle may carry this preview's intermediate representation, in which case
    // its
    // consumer class was dropped at pack time. We then inflate the IR via the matching runtime in
    // the setContent body below instead of reflecting the (absent) class. This handles protolayout;
    // a Remote Compose IR preview falls through to the normal path (its class is still absent, so
    // it
    // errors as it did before, until its replay path lands).
    val irReplay = BundleIrReplayStore.lookup(spec.previewId)
    val isProtolayoutIr = irReplay?.format == BundleIrReplayStore.FORMAT_PROTOLAYOUT
    // Remote Compose replays through a connector composable the daemon can't compile against (it's
    // built on the alpha player SDK); resolve it reflectively via the IR-replay SPI. Null when the
    // connector isn't on the classpath — then we fall through to the normal class path (which fails
    // as before, since the IR preview's class was dropped at pack time).
    // Resolve via the default (context) classloader, exactly as `loadPreviewWrapperClass` does for
    // the live RC wrapper path — that's the classloader topology under which the connector links
    // against the alpha player at runtime.
    val rcReplayClass: Class<*>? =
      if (irReplay?.format == BundleIrReplayStore.FORMAT_REMOTECOMPOSE)
        runCatching { loadIrReplayClass(BundleIrReplayStore.FORMAT_REMOTECOMPOSE) }.getOrNull()
      else null
    // An IR-backed preview's consumer class was dropped at pack time, so skip the reflective load
    // whenever we have a replay path for it (protolayout direct, or a resolved RC provider).
    val isIrReplay = isProtolayoutIr || rcReplayClass != null
    val clazz =
      if (isIrReplay) null
      else
        trace.section("classloader:loadPreviewClass") {
          Class.forName(spec.className, true, classLoader)
        }
    // Tile / notification previews are non-composable top-level functions returning
    // `TilePreviewData` / `android.app.Notification`; routing them through
    // `getDeclaredComposableMethod` would throw `NoSuchMethodException` (the Compose-method
    // lookup expects `(Composer, Int, …)` trailing params). Glance previews ARE `@Composable`
    // but need to be hosted inside a `GlanceAppWidget.providePreview(...)` →
    // `composeForPreview(...)`
    // → `RemoteViews.apply` pipeline (handed off to `:renderer-android`'s
    // [renderer.GlanceAppWidgetPreviewComposable]); invoking them directly through the regular
    // Compose path would skip the `RemoteViews` materialisation entirely and render an unwrapped
    // body that fails the moment a Glance composable touches the GlanceComposition applier. The
    // dedicated branches skip top-level composable resolution and paint the result through the
    // matching renderer helper in the setContent body below.
    val nonComposableInvocation = isTile || isNotification || isGlanceAppWidget || isIrReplay
    val composableMethod: ComposableMethod? =
      if (nonComposableInvocation) null
      else
        trace.section("compose:resolveComposable") {
          clazz!!.getDeclaredComposableMethod(spec.functionName)
        }
    // Kotlin `private fun` previews compile to JVM-private methods. `getDeclaredComposableMethod`
    // still resolves them (it scans `declaredMethods`), but the reflective `invoke` in
    // [InvokeComposable] would throw IllegalAccessException, so open the method up first — mirrors
    // `:renderer-android`'s ComposePreviewStrategy. Guarded with `runCatching`: a SecurityManager
    // or strong module encapsulation can refuse, in which case we still attempt the invoke
    // (which succeeds for public/internal previews) rather than failing resolution outright.
    composableMethod?.let { runCatching { it.asMethod().isAccessible = true } }

    // Self-diagnostic — surfaces in the VS Code extension's output channel as `[daemon stderr] …`.
    // Pairs with `[classloader] swap requested` / `allocate child loader` lines from
    // [UserClassLoaderHolder]. If `classFile` doesn't advance across saves the daemon is
    // re-rendering against bytecode that wasn't actually recompiled.
    val fingerprint =
      UserClassLoaderHolder.classFileFingerprint(classLoader, spec.className)
        ?: "fingerprint unavailable (class not on a file: URL)"
    System.err.println(
      "compose-ai-daemon: [render] ${spec.className}#${spec.functionName} " +
        "loaderId=${System.identityHashCode(classLoader).toString(16)} classFile=$fingerprint"
    )
    System.err.println(
      "compose-ai-daemon: [render] mode=${spec.renderMode ?: "<default>"} " +
        "effectiveRunAccessibility=$effectiveRunAccessibility " +
        "inspectionMode=${if (effectiveRunAccessibility) false else spec.inspectionMode ?: true} " +
        "outputBaseName=${spec.outputBaseName}"
    )

    // `device = "id:wearos_*_round"` / `isRound=true` previews need a circular crop matching the
    // standalone renderer's `RobolectricRenderTest`. The standalone path also gates on
    // `showSystemUi || kind == TILE` to skip the crop on non-fullscreen previews (the crop is a
    // device frame, not part of the composable), but the daemon's v1 `RenderSpec` doesn't carry
    // either field — assume any explicit round-device request wants the crop. Refine when those
    // fields get plumbed through.
    val isRound = isRoundDevice(spec.device)

    // A round Wear frame requested TALLER than it is wide is the grown `figma-svg-long` scroll
    // render re-entering `render()` (a normal round watch face is square). Flatten
    // `TransformingLazyColumn` edge scaling (`LocalReduceMotion = true`) so its items stack at their
    // natural size — the flat, editable list the capsule-clipped SVG lays out — instead of curving /
    // shrinking toward the tall frame's top and bottom. Same knob the raster LONG path uses;
    // resolved reflectively via the request classloader (the user's `wear-compose` lives on the
    // child loader, not the daemon's own), and a no-op when the consumer isn't a Wear app.
    val flattenWearScroll = isRound && spec.heightPx > spec.widthPx
    val wearReduceMotionLocal =
      if (flattenWearScroll)
        ee.schimke.composeai.renderer.WearReduceMotionLocal.get(classLoader)
      else null

    // Content-size bounds (the Max / Min / Within size modes) apply on a wrapped axis, where
    // widthPx/heightPx are a sandbox bound rather than a fixed frame. A min bound larger than the
    // default sandbox needs the Robolectric display enlarged to fit, otherwise the wrap measure is
    // clamped to the sandbox window before it can reach the requested floor. Only widen (never
    // shrink) the sandbox — the intrinsic-size crop still trims the PNG back to the measured size.
    // Mirrors the desktop daemon's scene-enlarge in `:daemon:desktop`'s RenderEngine.
    val sizeOverrides = spec.overrides
    val sandboxWidthPx =
      if (spec.wrapWidth)
        maxOf(spec.widthPx, sizeOverrides?.minWidthPx ?: 0, sizeOverrides?.maxWidthPx ?: 0)
      else spec.widthPx
    val sandboxHeightPx =
      if (spec.wrapHeight)
        maxOf(spec.heightPx, sizeOverrides?.minHeightPx ?: 0, sizeOverrides?.maxHeightPx ?: 0)
      else spec.heightPx

    // Per-preview Robolectric configuration — qualifiers re-applied so a previous render's size /
    // density doesn't bleed into this one. Same entrypoints `RobolectricRenderTest` uses; both
    // mutate `RuntimeEnvironment` global state, which is OK here because the sandbox is single-
    // threaded under our render loop (DESIGN § 9 invariant: no concurrent renders).
    applyPreviewQualifiers(
      widthDp = pxToDp(sandboxWidthPx, spec.density),
      heightDp = pxToDp(sandboxHeightPx, spec.density),
      density = spec.density,
      isRound = isRound,
      localeTag = spec.localeTag,
      uiMode = spec.uiMode,
      orientation = spec.orientation,
    )
    org.robolectric.RuntimeEnvironment.setFontScale(spec.fontScale ?: 1.0f)

    // Activity registration mirrors `RobolectricRenderTest.renderDefault` — Robolectric 4.13+
    // requires the activity to be resolvable through `ShadowPackageManager` before
    // `createAndroidComposeRule` will launch it. `addActivityIfNotPresent` is idempotent across
    // renders; B1.7 owns the additive-state cleanup story.
    val appContext: android.app.Application =
      androidx.test.core.app.ApplicationProvider.getApplicationContext()
    org.robolectric.Shadows.shadowOf(appContext.packageManager)
      .addActivityIfNotPresent(
        android.content.ComponentName(appContext.packageName, ComponentActivity::class.java.name)
      )

    // v2 `createAndroidComposeRule` (compose-ui-test 1.11.0-alpha03+) is the
    // long-term replacement, but we share the renderer's `compose-bom-compat`
    // (1.9.5) compile floor. Track [RobolectricRenderTest.renderDefault] when
    // the floor moves up.
    @Suppress("DEPRECATION") val rule = createAndroidComposeRule<ComponentActivity>()
    // Per-node theme facts pulled from the semantics tree during the rule statement (the rule tears
    // the composition down once `evaluate()` returns) so theme consumer attribution (#1847) can run
    // against them afterwards, once the resolved tokens are assembled.
    var capturedThemeFacts: List<NodeThemeFacts> = emptyList()
    // AS-parity wrap-content: the intrinsic size the wrapped content measured to, recorded from
    // inside composition so the captured PNG can be cropped to it after `captureRoboImage`. `[0,0]`
    // until the first measure; only consulted on wrapped axes (see `spec.wrapWidth`/`wrapHeight`).
    val measuredContent = intArrayOf(0, 0)
    val description =
      org.junit.runner.Description.createTestDescription(
        RenderEngine::class.java,
        "render_${spec.outputBaseName}",
      )
    val statement =
      object : org.junit.runners.model.Statement() {
        override fun evaluate() {
          try {
            rule.mainClock.autoAdvance = false

            val bgArgb = resolveBackgroundColor(spec).toArgb()
            rule.runOnUiThread { rule.activity.window.decorView.setBackgroundColor(bgArgb) }
            // Always-on data extensions (fonts, resources, i18n recorders + post-capture
            // writers) — built per render so each extension owns its own recorder lifecycle.
            // Threaded through the same Compose pipeline as `previewOverrideExtensions` and
            // re-used during the post-capture pass below to write the artefacts.
            //
            // Built off a placeholder-wrapped context (not the raw activity) so the missing-resource
            // fallback sits *underneath* these extensions. The resources recorder re-provides
            // `LocalContext` from a `RecordingResources` that delegates to this base
            // (`ResourcesUsedDataProducer.recorder`), which would otherwise shadow the outer
            // `LocalContext provides placeholderContext` below and route a missing `stringResource`
            // straight to the raw table → `Resources$NotFoundException`. Wrapping the base here keeps
            // the fallback active through every extension that carries this context onward. The real
            // activity is threaded separately via `RenderDataArtifactContextKeys.HeldActivity`, so
            // nothing that needs the `ComponentActivity` reads it from this (wrapped) context.
            val builtDataArtifactExtensions =
              dataArtifactExtensions.build(
                if (placeholderMissingResources) rule.activity.wrappedForPlaceholderResources()
                else rule.activity
              )

            System.err.println(
              "compose-ai-daemon: [render] phase=setContent.start outputBaseName=${spec.outputBaseName}"
            )
            trace.section("compose:setContent") {
              rule.setContent {
                // D2 — a11y mode flips LocalInspectionMode off so Compose populates real
                // accessibility semantics (mergeMode, contentDescription, role) for ATF + the
                // hierarchy walk to consume after capture. Tradeoff: infinite animations tick
                // through rather than parking under the paused clock — same trade the standalone
                // renderer already pays in its always-on a11y pass.
                val inspectionMode =
                  if (effectiveRunAccessibility) false else spec.inspectionMode ?: true
                // Missing-resource fallback: wrap LocalContext so a `stringResource` /
                // `colorResource` / `context.getString` lookup that isn't in the (possibly absent
                // or stale) packed resource table falls back to an obvious placeholder instead of
                // throwing `Resources$NotFoundException` and aborting the whole render. Transparent
                // for every resolvable resource; only misses are substituted. Outermost so the data
                // extensions (incl. pseudolocale) and preview content all see the guarded context.
                val baseContext = LocalContext.current
                val placeholderContext =
                  if (placeholderMissingResources)
                    remember(baseContext) { baseContext.wrappedForPlaceholderResources() }
                  else null
                val provided =
                  buildList {
                      if (placeholderContext != null) {
                        add(LocalContext provides placeholderContext)
                      }
                      add(LocalInspectionMode provides inspectionMode)
                      ee.schimke.composeai.renderer.LocaleCompositionLocals
                        .providedValue(LocalConfiguration.current, classLoader)
                        ?.let(::add)
                      // Cleared background ("crisp outline"): a composable drawing its own opaque
                      // fill drops it to match the transparent decor-view background. Defaults false.
                      add(
                        ee.schimke.composeai.preview.slots.LocalPreviewBackgroundCleared provides
                          spec.clearBackground
                      )
                      // Flatten Wear `TransformingLazyColumn` scaling for the grown scroll-SVG
                      // render (see `flattenWearScroll` above); no-op provider when not a Wear app.
                      if (wearReduceMotionLocal != null) add(wearReduceMotionLocal provides true)
                    }
                    .toTypedArray()
                CompositionLocalProvider(*provided) {
                  CaptureMaterialTheme { _, typography, shapes, payload ->
                    themeFallbackCapture.capture(typography, shapes)
                    themeFallbackCapture.capture(payload)
                  }
                  val content: @Composable () -> Unit = {
                    ComposeDataExtensionPipeline.Apply(
                      extensions =
                        previewOverrideExtensions.plan(spec.overrides) +
                          builtDataArtifactExtensions,
                      previewId = spec.previewId,
                      renderMode = spec.renderMode,
                      sink = RecordingExtensionCompositionSink(),
                    ) {
                      // AS-parity wrap: on a wrapped axis, measure the composable with a relaxed
                      // (min = 0) constraint against the sandbox max and size the box to the child's
                      // intrinsic size, so the captured tree (and the figma-svg / wireframe /
                      // semantics derived from it) reflects the preview's natural size instead of the
                      // fixed sandbox frame — a Column handed only the sandbox height reflows overflow
                      // children to zero. Fixed axes keep `fillMaxSize` so `fillMax*` / LazyColumn
                      // still have a finite viewport. Mirrors the desktop daemon + standalone renderer.
                      val contentBoxModifier =
                        if (spec.wrapWidth || spec.wrapHeight) {
                          Modifier.layout { measurable, constraints ->
                            // Size-mode bounds (Max / Min / Within) clamp the wrapped-axis measure:
                            // a max bound lowers the sandbox ceiling so the composable can't grow
                            // past it; a min bound raises the floor so it can't collapse below it.
                            // Both are clamped to the (already-enlarged) sandbox so a bound larger
                            // than the window can't produce an impossible constraint. Absent bounds
                            // keep the AS-parity wrap (min = 0, max = sandbox). Mirrors the desktop
                            // daemon RenderEngine.
                            val maxWBound =
                              sizeOverrides?.maxWidthPx?.coerceAtMost(constraints.maxWidth)
                                ?: constraints.maxWidth
                            val maxHBound =
                              sizeOverrides?.maxHeightPx?.coerceAtMost(constraints.maxHeight)
                                ?: constraints.maxHeight
                            val minWBound = (sizeOverrides?.minWidthPx ?: 0).coerceIn(0, maxWBound)
                            val minHBound = (sizeOverrides?.minHeightPx ?: 0).coerceIn(0, maxHBound)
                            val wrapped =
                              androidx.compose.ui.unit.Constraints(
                                minWidth = if (spec.wrapWidth) minWBound else constraints.minWidth,
                                maxWidth = if (spec.wrapWidth) maxWBound else constraints.maxWidth,
                                minHeight =
                                  if (spec.wrapHeight) minHBound else constraints.minHeight,
                                maxHeight =
                                  if (spec.wrapHeight) maxHBound else constraints.maxHeight,
                              )
                            val placeable = measurable.measure(wrapped)
                            measuredContent[0] = placeable.width
                            measuredContent[1] = placeable.height
                            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                          }
                        } else {
                          Modifier.fillMaxSize()
                        }
                      // On a wrapped axis, `propagateMinConstraints = true` pushes the wrapped-axis
                      // *min* bound (the Min / Within size modes) down onto the composable itself,
                      // not just the wrapping box — with the default (false) the outer box grows to
                      // the min bound but relaxes the child's min to 0, so a wrap-content component
                      // stays at its intrinsic size in the corner of an enlarged frame instead of
                      // filling the requested size. Scoped to wrapped renders only: the fixed-frame
                      // branch above is `fillMaxSize`, whose tight min would otherwise be forwarded
                      // into the root composable and stretch wrap-content content that must stay
                      // small in an explicitly-sized frame. Mirrors the desktop daemon + standalone
                      // renderer.
                      Box(
                        modifier = contentBoxModifier,
                        propagateMinConstraints = spec.wrapWidth || spec.wrapHeight,
                      ) {
                        if (isProtolayoutIr) {
                          // v5 IR replay — inflate the captured protolayout `Layout` + `Resources`
                          // protos through `TileRenderer`, with no reference to the tile function
                          // that produced them (its class was dropped at pack time). Same
                          // AndroidView-hosted shape as the live tile branch, so captureRoboImage
                          // walks an identical Compose tree.
                          ee.schimke.composeai.renderer.TileIrReplayComposable(
                            layoutBytes = irReplay!!.bytes,
                            resourcesBytes = irReplay.resourcesBytes ?: ByteArray(0),
                            label = "IR replay ${spec.previewId ?: spec.outputBaseName}",
                          )
                        } else if (rcReplayClass != null) {
                          // Remote Compose IR replay — render the captured RemoteDocument through
                          // the connector's player, reached reflectively (the daemon can't compile
                          // against the alpha SDK). See IrReplayComposableProvider.
                          InvokeIrReplay(rcReplayClass, irReplay!!.bytes)
                        } else if (isTile) {
                          // Non-composable @Preview from `androidx.wear.tiles.tooling.preview` —
                          // mirrors the standalone renderer's `TilePreviewStrategy`. The
                          // inflated tile View lands inside the `Box` via `AndroidView`, so
                          // captureRoboImage walks the same Compose tree as for composable
                          // previews.
                          ee.schimke.composeai.renderer.TilePreviewComposable(
                            className = spec.className,
                            functionName = spec.functionName,
                            widthDp = pxToDp(spec.widthPx, spec.density),
                            heightDp = pxToDp(spec.heightPx, spec.density),
                            device = spec.device,
                            // Same per-render child loader the compose path uses for
                            // `getDeclaredComposableMethod` above; without it
                            // `findTilePreviewMethod` would `Class.forName` against the parent
                            // loader and either miss user classes (under isolation) or render
                            // stale bytecode after an edit.
                            classLoader = classLoader,
                          )
                        } else if (isNotification) {
                          // Non-composable `@NotificationPreview` — mirrors the standalone
                          // renderer's `NotificationPreviewStrategy`. The inflated RemoteViews
                          // lands inside the `Box` via `AndroidView`, same Compose-tree shape as
                          // the tile path so captureRoboImage downstream is unchanged.
                          ee.schimke.composeai.renderer.NotificationPreviewComposable(
                            className = spec.className,
                            functionName = spec.functionName,
                            classLoader = classLoader,
                            // Lets the renderer write a structured-fields JSON sidecar alongside
                            // the PNG for daemon-driven renders (same path used by the standalone
                            // `composePreviewRender` Test task).
                            previewId = spec.previewId,
                            // Exact surface width = the resolved canvas width (400dp for FQN-
                            // discovered notifications), so the shade isn't cropped to its ~320dp
                            // intrinsic square. Mirrors the Glance branch's dp conversion.
                            widthDp = pxToDp(spec.widthPx, spec.density),
                          )
                        } else if (isGlanceAppWidget) {
                          // `@androidx.glance.preview.Preview` — mirrors the standalone
                          // renderer's `GlanceAppWidgetPreviewStrategy`. The user's @Composable
                          // is hosted inside a `SyntheticGlanceAppWidget.providePreview(...)`
                          // and its output is materialised to `RemoteViews` via
                          // `GlanceAppWidget.composeForPreview(...)`, then inflated into the
                          // captured `AndroidView`. Without this branch Glance previews would
                          // fall through to `InvokeWithOptionalWrapper` and execute the
                          // `@GlanceComposable` body directly against the regular Compose
                          // applier, producing either a misrender or a hard crash when the
                          // body touches Glance-only composables.
                          val widthDp = pxToDp(spec.widthPx, spec.density)
                          val heightDp = pxToDp(spec.heightPx, spec.density)
                          ee.schimke.composeai.renderer.GlanceAppWidgetPreviewComposable(
                            className = spec.className,
                            functionName = spec.functionName,
                            widthDp = widthDp,
                            heightDp = heightDp,
                            classLoader = classLoader,
                          )
                        } else {
                          InvokeWithOptionalWrapper(
                            composableMethod = composableMethod!!,
                            wrapperFqnFromSpec = spec.wrapperClassName,
                            // A `themeProvider` override (an app-declared @ThemeCatalog
                            // `PreviewWrapperProvider` FQN) replaces the preview's own
                            // `@PreviewWrapper` — "render this preview under theme X" — but only when
                            // it resolves; a stale/misspelled FQN falls back to the declared wrapper.
                            themeProviderFqn = spec.overrides?.themeProvider,
                          )
                        }
                      }
                    }
                  }
                  InspectablePreviewContent(slotTableCapture, content)
                }
              }
            }

            System.err.println(
              "compose-ai-daemon: [render] phase=setContent.done outputBaseName=${spec.outputBaseName}"
            )

            // CAPTURE_ADVANCE_MS is the same paused-clock advance `RobolectricRenderTest` uses —
            // ≈ 2 Choreographer frames. Enough to settle initial composition + one
            // `LaunchedEffect` pass; deterministic snapshot point for any infinite animation.
            // PROTOCOL.md § 5 (`renderNow.overrides.captureAdvanceMs`) — animation-heavy
            // previews can override.
            trace.section("compose:advanceClock") {
              rule.mainClock.advanceTimeBy(spec.captureAdvanceMs ?: CAPTURE_ADVANCE_MS)
            }

            outputFile.parentFile?.mkdirs()
            // `applyDeviceCrop = true` is what produces the circular alpha mask Roborazzi paints
            // over the captured bitmap; the `round` resource qualifier set above only affects
            // `Configuration.isScreenRound`. Both are needed for parity with the standalone
            // renderer's wear-round path.
            //
            // BUT the grown Wear scroll render (`flattenWearScroll`) is the isolated throwaway base
            // whose PNG feeds ONLY the hybrid `figma-raster` crops the capsule SVG references — the
            // capsule `<clipPath>` does the visual masking. Circle-cropping it here would zero the
            // alpha outside the inscribed circle, so any rasterized Image/Icon/Canvas in the revealed
            // top or bottom of the tall frame would crop to a blank/transparent `<image>` even though
            // the capsule shows that region. Skip the device crop for the tall render so those crops
            // carry real pixels; the SVG's capsule mask clips the frame, not the source PNG.
            val roborazziOptions =
              RoborazziOptions(
                recordOptions =
                  RoborazziOptions.RecordOptions(applyDeviceCrop = isRound && !flattenWearScroll)
              )
            System.err.println(
              "compose-ai-daemon: [render] phase=captureRoboImage.start outputBaseName=${spec.outputBaseName}"
            )
            rule.onRoot().also {
              trace.section("render:captureRoboImage") {
                it.captureRoboImage(file = outputFile, roborazziOptions = roborazziOptions)
              }
            }
            System.err.println(
              "compose-ai-daemon: [render] phase=captureRoboImage.done outputBaseName=${spec.outputBaseName}"
            )

            // AS-parity wrap crop: the sandbox window is generous (400×800 dp) so wrap-content
            // measures naturally, but that leaves the composable in the top-left of a large PNG.
            // Crop back to the measured intrinsic size on wrapped axes (content is placed at 0,0),
            // matching the standalone renderer's `cropPngTopLeft`. No-op for fixed-size previews.
            if (spec.wrapWidth || spec.wrapHeight) {
              cropWrappedPngTopLeft(
                file = outputFile,
                wrapWidth = spec.wrapWidth,
                wrapHeight = spec.wrapHeight,
                measuredWidth = measuredContent[0],
                measuredHeight = measuredContent[1],
              )
            }

            // Pull per-node theme facts while the composition is still alive so theme consumer
            // attribution (#1847) can run after the rule tears the scene down.
            capturedThemeFacts =
              ThemeConsumerCapture.extractFacts(
                runCatching { rule.onRoot(useUnmergedTree = true).fetchSemanticsNode() }.getOrNull()
              )

            // Always-on data-artifact extensions (fonts, resources, semantics, layout-inspector,
            // i18n, etc). Each extension owns its own recorder lifecycle (installed during
            // composition via [AroundComposableHook]) and writes its typed artefact through
            // [PostCaptureProcessor.process]. The render engine just hands them the typed
            // post-capture context (rootDir, previewId, semantics root, layout-inspector
            // preview context, locale) and lets each extension decide what to write.
            if (dataDir != null && builtDataArtifactExtensions.isNotEmpty()) {
              val resolvedSemanticsRoot =
                runCatching { rule.onRoot(useUnmergedTree = true).fetchSemanticsNode() }.getOrNull()
              val layoutInspectorPreviewContext = resolvedSemanticsRoot?.let { semanticsRoot ->
                PreviewContext.Builder(
                    previewId = spec.previewId,
                    backend = PreviewBackends.ANDROID,
                    renderMode = spec.renderMode,
                    outputBaseName = spec.outputBaseName,
                  )
                  .deviceFromRenderPixels(
                    spec.device,
                    spec.widthPx,
                    spec.heightPx,
                    spec.density,
                    resolvedDevice =
                      spec.device?.let(DeviceDimensions::resolve)?.previewDeviceSpec(),
                  )
                  .parameterInformationCollected()
                  .addSlotTables(slotTableCapture.snapshot())
                  .rootForTest(semanticsRoot.root)
                  .build()
              }
              val artifactContextData =
                buildList<ExtensionContextValue<*>> {
                  add(RenderDataArtifactContextKeys.RootDir provides dataDir)
                  add(RenderDataArtifactContextKeys.OutputBaseName provides spec.outputBaseName)
                  spec.previewId?.let { add(RenderDataArtifactContextKeys.PreviewId provides it) }
                  spec.localeTag
                    ?.takeIf { it.isNotBlank() }
                    ?.let { add(RenderDataArtifactContextKeys.RenderedLocale provides it) }
                  resolvedSemanticsRoot?.let {
                    add(RenderDataArtifactContextKeys.SemanticsRoot provides it)
                  }
                  add(RenderDataArtifactContextKeys.Density provides spec.density)
                  add(RenderDataArtifactContextKeys.SlotTables provides slotTableCapture.snapshot())
                  add(RenderDataArtifactContextKeys.FontScale provides (spec.fontScale ?: 1.0f))
                  add(RenderDataArtifactContextKeys.OutputPng provides outputFile)
                  add(RenderDataArtifactContextKeys.HeldActivity provides rule.activity)
                  layoutInspectorPreviewContext?.let {
                    add(RenderDataArtifactContextKeys.LayoutInspectorPreviewContext provides it)
                    // Round-Wear clip for the shared figma-svg export — the same
                    // `previewContext.device.isRound` the inline Android extension read.
                    add(RenderDataArtifactContextKeys.RoundClip provides it.device.isRound)
                  }
                }
              val extensionContextData =
                ExtensionContextData.of(*artifactContextData.toTypedArray())
              val productStore = RecordingDataProductStore()
              for (ext in builtDataArtifactExtensions) {
                if (ext !is PostCaptureProcessor) continue
                System.err.println(
                  "compose-ai-daemon: [render] phase=dataArtifact.${ext.id}.start outputBaseName=${spec.outputBaseName}"
                )
                val extStartNs = System.nanoTime()
                try {
                  trace.section("dataArtifact:${ext.id}") {
                    ext.process(
                      ExtensionPostCaptureContext(
                        extensionId = ext.id,
                        previewId = spec.previewId,
                        renderMode = spec.renderMode,
                        products = productStore.scopedFor(ext),
                        data = extensionContextData,
                      )
                    )
                  }
                  System.err.println(
                    "compose-ai-daemon: [render] phase=dataArtifact.${ext.id}.done " +
                      "outputBaseName=${spec.outputBaseName} " +
                      "tookMs=${(System.nanoTime() - extStartNs) / 1_000_000L}"
                  )
                } catch (t: Throwable) {
                  System.err.println(
                    "RenderEngine: ${ext.id} data write failed for ${spec.outputBaseName}: " +
                      "${t.javaClass.simpleName}: ${t.message}"
                  )
                }
              }
            }

            // D2 — a11y data products. Walk the same `ViewRootForTest` ATF can populate, dump
            // `a11y-atf.json` (findings) and `a11y-hierarchy.json` (nodes) next to the PNG. The
            // dispatcher reads these on `data/fetch` / `data/subscribe` attachment via
            // `AccessibilityDataProductRegistry`. Wrapped in try/catch so an a11y failure does
            // not strand the PNG the user already cares about — the registry sees a missing
            // file as "no attachment for this kind on this render".
            if (effectiveRunAccessibility && dataDir != null) {
              System.err.println(
                "compose-ai-daemon: [render] phase=a11y.start outputBaseName=${spec.outputBaseName}"
              )
              try {
                trace.section("a11y:dataProducts") {
                  val view = (rule.onRoot().fetchSemanticsNode().root as ViewRootForTest).view
                  // Hierarchy + ATF come from a typed extension instead of a direct
                  // AccessibilityChecker.analyze call. The extension owns the platform-specific
                  // ATF walk; downstream consumers (TouchTargets, Overlay) read declared inputs
                  // without re-walking the View. A future :data-a11y-hierarchy-desktop (CMP
                  // semantics walk) would target {Desktop} and emit the same product keys; the
                  // planner's target filter selects exactly one provider per platform.
                  val hierarchyExtension = AccessibilityHierarchyExtension()
                  val store = RecordingDataProductStore()
                  hierarchyExtension.process(
                    ExtensionPostCaptureContext(
                      extensionId = hierarchyExtension.id,
                      previewId = spec.outputBaseName,
                      renderMode = spec.renderMode,
                      products = store.scopedFor(hierarchyExtension),
                      data =
                        ExtensionContextData.of(
                          AccessibilityHierarchyContextKeys.ViewRoot provides view
                        ),
                    )
                  )
                  val hierarchy = store.require(AccessibilityDataProducts.Hierarchy)
                  val findings = store.require(AccessibilityDataProducts.Atf)
                  AccessibilityDataProducer.writeArtifacts(
                    rootDir = dataDir,
                    previewId = spec.outputBaseName,
                    findings = findings.findings,
                    nodes = hierarchy.nodes,
                    density = spec.density,
                    pngFile = outputFile,
                    isRound = isRound,
                  )
                  System.err.println(
                    "compose-ai-daemon: [render] phase=a11y.done outputBaseName=${spec.outputBaseName} " +
                      "findings=${findings.findings.size} nodes=${hierarchy.nodes.size}"
                  )
                }
              } catch (t: Throwable) {
                System.err.println(
                  "compose-ai-daemon: [render] phase=a11y.failed outputBaseName=${spec.outputBaseName} " +
                    "error=${t.javaClass.simpleName}: ${t.message}"
                )
                t.printStackTrace(System.err)
              }
            }

            // #874 — `uia/hierarchy` data product. Walks the same `SemanticsOwner` `uia.*`
            // dispatch resolves selectors against and dumps the actionable subset to
            // `uia-hierarchy.json` so agents can inspect what's clickable / scrollable /
            // has-text *before* dispatching. Default filter (set by the producer) keeps
            // ~20% of nodes a real Material screen produces while preserving every viable
            // dispatch target. Always runs on the Android backend — independent of the a11y
            // opt-in. Wrapped in try/catch so a hierarchy failure does not strand the PNG.
            if (dataDir != null) {
              try {
                trace.section("uia:hierarchy") {
                  val rootNode = rule.onRoot(useUnmergedTree = false).fetchSemanticsNode()
                  val uiaExtension = UiAutomatorHierarchyExtension()
                  val uiaStore = RecordingDataProductStore()
                  uiaExtension.process(
                    ExtensionPostCaptureContext(
                      extensionId = uiaExtension.id,
                      previewId = spec.outputBaseName,
                      renderMode = spec.renderMode,
                      products = uiaStore.scopedFor(uiaExtension),
                      data =
                        ExtensionContextData.of(
                          UiAutomatorHierarchyContextKeys.SemanticsRoot provides rootNode
                        ),
                    )
                  )
                  val payload = uiaStore.require(UiAutomatorDataProducts.Hierarchy)
                  UiAutomatorDataProducer.writeArtifacts(
                    rootDir = dataDir,
                    previewId = spec.outputBaseName,
                    payload = payload,
                  )
                }
              } catch (t: Throwable) {
                System.err.println(
                  "RenderEngine: uia/hierarchy write failed for ${spec.outputBaseName}: " +
                    "${t.javaClass.simpleName}: ${t.message}"
                )
              }
            }

            // Display filters — post-capture colour-matrix variants (grayscale/bedtime, invert,
            // daltonizer simulations). Gated on `composeai.displayfilter.filters` being non-empty
            // so the default render path stays free; the same prop drives the DaemonMain
            // registration gate so "extension registered" and "filters produced" stay in sync.
            // Wrapped in try/catch so a filter failure does not strand the PNG.
            if (dataDir != null) {
              val filters = DisplayFilterConfig.fromSystemProperties()
              if (filters.isNotEmpty()) {
                try {
                  trace.section("displayfilter:variants") {
                    DisplayFilterDataProducer.writeArtifacts(
                      rootDir = dataDir,
                      previewId = spec.outputBaseName,
                      pngFile = outputFile,
                      filters = filters,
                    )
                  }
                } catch (t: Throwable) {
                  System.err.println(
                    "RenderEngine: displayfilter write failed for ${spec.outputBaseName}: " +
                      "${t.javaClass.simpleName}: ${t.message}"
                  )
                }
              }
            }
          } finally {
            // DESIGN § 9 + § 10 cleanup epilogue. The Compose test rule does not allow a second
            // `setContent` on the same `ComponentActivity`, so we can't drive the
            // empty-setContent flush *inside this statement*. Instead, the rule's outer statement
            // (the wrapper applied by `rule.apply(statement, description)`) closes the
            // `ActivityScenario` when `evaluate()` returns, which:
            //  - calls `Activity.onDestroy()`, which disposes the `ComposeView`'s composition,
            //  - which dispatches `LaunchedEffect` cancellation + `DisposableEffect.onDispose`,
            //  - releases the `HardwareRenderer` / `ImageReader` Roborazzi opened for
            //    `captureRoboImage`,
            //  - and recycles the captured `Bitmap` (Roborazzi's own discipline; we don't own
            //    the bitmap here).
            // We tick the paused clock once more so any pending Compose snapshot work is flushed
            // to disposal. Wrapped in try/catch so a thrown render body doesn't strand the next
            // render at a bad clock state.
            try {
              rule.mainClock.advanceTimeBy(spec.captureAdvanceMs ?: CAPTURE_ADVANCE_MS)
            } catch (t: Throwable) {
              System.err.println(
                "RenderEngine: post-capture mainClock advance failed for ${spec.className}.${spec.functionName}: ${t.message}"
              )
            }
          }
        }
      }
    // Bracket this preview's downloadable-font resolution so a face that couldn't be resolved (and
    // fell back to Roboto) is attributed to exactly this render — mirrors the gradle-plugin's
    // `RobolectricRenderTest`. Drained after the render below to gate on it. Without this, the
    // `bundle pack` / serve daemon render path (which design-artifacts uses) silently shipped a
    // Roboto-fallback sticker while `RobolectricRenderTest`'s gate only guarded the plugin path.
    FontResolutionDiagnostics.beginPreview()
    // B2.0 — install the child classloader as the context classloader for the duration of the
    // render dispatch. Compose's reflection paths (notably PreviewParameter providers — see
    // CLASSLOADER.md § Risks 2) consult the context classloader; without this install they would
    // miss user classes that aren't on the parent's (sandbox) classpath. Restored in `finally` so
    // the surrounding sandbox bootstrap path is unaffected.
    val previousContext = Thread.currentThread().contextClassLoader
    Thread.currentThread().contextClassLoader = classLoader
    try {
      System.err.println(
        "compose-ai-daemon: [render] phase=evaluateRule.start outputBaseName=${spec.outputBaseName}"
      )
      trace.section("render:evaluateRule") { rule.apply(statement, description).evaluate() }
      System.err.println(
        "compose-ai-daemon: [render] phase=evaluateRule.done outputBaseName=${spec.outputBaseName}"
      )
    } catch (t: Throwable) {
      System.err.println(
        "compose-ai-daemon: [render] phase=evaluateRule.failed outputBaseName=${spec.outputBaseName} " +
          "error=${t.javaClass.simpleName}: ${t.message}"
      )
      t.printStackTrace(System.err)
      throw t
    } finally {
      Thread.currentThread().contextClassLoader = previousContext
    }

    // Font-fallback gate (reached only on a successful render — a thrown body propagated above).
    // A downloadable face that couldn't be resolved fell back to Roboto, so this preview would ship
    // the wrong typeface. Fail the render by default (the daemon's per-preview catch drops the PNG
    // and records the reason, so a design-artifacts publish can't silently bake Roboto);
    // `-Dcomposeai.fonts.failOnFallback=false` downgrades it to a `<png>.warnings.json` sidecar kept
    // beside the PNG. Mirrors `RobolectricRenderTest`'s gate so both Android render paths behave the
    // same.
    val fontFallbacks = FontResolutionDiagnostics.drainPreview()
    if (fontFallbacks.isNotEmpty() && FontResolutionDiagnostics.failOnFallback) {
      throw FontFallbackException(fontFallbacks)
    }
    RenderWarningsSidecar.writeOrDelete(outputFile, fontFallbacks)

    val tookMs = (System.nanoTime() - startNs) / 1_000_000L
    val metrics = SandboxMeasurement.collect(sandboxStats, tookMs = tookMs)
    dataDir?.let(trace::write)
    val slotTables = slotTableCapture.snapshot()
    val rawPreviewContext =
      PreviewContext.Builder(
          previewId = spec.previewId,
          backend = PreviewBackends.ANDROID,
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
    val baseThemePayload =
      themePayloadFromPreviewContext(
        context = rawPreviewContext,
        fallbackTypography = themeFallbackCapture.typography,
        fallbackShapes = themeFallbackCapture.shapes,
      ) ?: themeFallbackCapture.payload
    // Attribute consumers (#1847) against the facts captured while the scene was alive, keyed by
    // SemanticsNode id (matching `compose/semantics`) against the reported tokens.
    val materialThemePayload = baseThemePayload?.let { payload ->
      val consumers = ThemeConsumerAttribution.attribute(capturedThemeFacts, payload.resolvedTokens)
      if (consumers.isEmpty()) payload else payload.copy(consumers = consumers)
    }
    val previewContextBuilder =
      PreviewContext.Builder(
          previewId = spec.previewId,
          backend = PreviewBackends.ANDROID,
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
    materialThemePayload?.let {
      previewContextBuilder.putInspectionValue(MATERIAL3_THEME_PAYLOAD_CONTEXT_KEY, it)
    }
    val previewContext = previewContextBuilder.build()
    System.err.println(
      "compose-ai-daemon: [render] phase=complete outputBaseName=${spec.outputBaseName} " +
        "tookMs=$tookMs pngPath=${outputFile.absolutePath}"
    )
    return RenderResult(
      id = requestId,
      classLoaderHashCode = System.identityHashCode(classLoader),
      classLoaderName = classLoader.javaClass.name,
      pngPath = outputFile.absolutePath,
      metrics = metrics,
      previewContext = previewContext,
    )
  }

  /**
   * Issue #1528 — dispatch for `scroll-long` / `scroll-gif` render modes. Builds the held
   * `AndroidComposeTestRule`, paints the preview's `@Composable` body via
   * [setHeldComposableContentForScroll], then calls the renderer's public scroll handlers to write
   * the final stitched PNG / animated GIF under `<dataRoot>/render-scroll-{long,gif}/`.
   *
   * Returns a [RenderResult] with `pngPath` pointing at the produced scroll artefact so the
   * dispatcher's second-fetch picks up the file. Throws [IllegalStateException] when the preview
   * has no `dataProducts[].scroll` metadata (the dispatcher surfaces this as
   * `ERR_DATA_PRODUCT_FETCH_FAILED` with the message body), or when the renderer's scroll handler
   * returns `false` (no scrollable on the requested axis — the dispatcher surfaces the same error
   * code so the host can fall back to its Gradle path or paint a "no scrollable" hint).
   *
   * Looks up the scroll intent by lazily loading the daemon's `previews.json`
   * ([PreviewIndex.PREVIEWS_JSON_PATH_PROP]) per render. The cost is one ~kB file read per
   * `data/fetch` re-render, which is dwarfed by the render itself.
   */
  @OptIn(ExperimentalRoborazziApi::class)
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
    val scenarioDataDir =
      dataDir
        ?: error(
          "RenderEngine: scroll mode '${spec.renderMode}' needs a non-null dataDir to write " +
            "<dataDir>/render-scroll-{long,gif}/<previewId>.{png,gif}"
        )
    val (subdir, ext) =
      when (spec.renderMode) {
        SCROLL_LONG_RENDER_MODE -> "render-scroll-long" to "png"
        SCROLL_GIF_RENDER_MODE -> "render-scroll-gif" to "gif"
        else -> error("RenderEngine: unreachable scroll mode '${spec.renderMode}'")
      }
    val outputFile = scenarioDataDir.resolve(subdir).resolve("$previewId.$ext")
    outputFile.parentFile?.mkdirs()

    val isRound = isRoundDevice(spec.device)
    applyPreviewQualifiers(
      widthDp = pxToDp(spec.widthPx, spec.density),
      heightDp = pxToDp(spec.heightPx, spec.density),
      density = spec.density,
      isRound = isRound,
      localeTag = spec.localeTag,
      uiMode = spec.uiMode,
      orientation = spec.orientation,
    )
    org.robolectric.RuntimeEnvironment.setFontScale(spec.fontScale ?: 1.0f)

    val appContext: android.app.Application =
      androidx.test.core.app.ApplicationProvider.getApplicationContext()
    org.robolectric.Shadows.shadowOf(appContext.packageManager)
      .addActivityIfNotPresent(
        android.content.ComponentName(appContext.packageName, ComponentActivity::class.java.name)
      )

    @Suppress("DEPRECATION") val rule = createAndroidComposeRule<ComponentActivity>()
    val description =
      org.junit.runner.Description.createTestDescription(
        RenderEngine::class.java,
        "scroll_${spec.outputBaseName}",
      )
    val startNs = System.nanoTime()
    var handled = false
    val statement =
      object : org.junit.runners.model.Statement() {
        override fun evaluate() {
          rule.mainClock.autoAdvance = false
          val clazz = Class.forName(spec.className, true, classLoader)
          val composableMethod = clazz.getDeclaredComposableMethod(spec.functionName)
          val bgArgb = resolveBackgroundColor(spec).toArgb()
          val heightDp = pxToDp(spec.heightPx, spec.density)
          // Match the batch renderer's per-mode reduce-motion contract: a stitched LONG still
          // always flattens Wear `TransformingLazyColumn` scaling (mid-scale items baked into
          // slices are exactly what the stitcher can't collapse), while GIF frames — which
          // genuinely animate — always keep motion. The annotation's `reduceMotion` flag governs
          // TOP/END stills only, neither of which routes through this scenario. Each daemon scroll
          // mode composes fresh, so the value is static per request; resolved reflectively via the
          // request classloader and a no-op when the consumer isn't a Wear app.
          val scrollReduceMotionLocal =
            if (spec.renderMode == SCROLL_LONG_RENDER_MODE)
              ee.schimke.composeai.renderer.WearReduceMotionLocal.get(classLoader)
            else null
          rule.setContent {
            val provided =
              buildList {
                  add(LocalInspectionMode provides false)
                  ee.schimke.composeai.renderer.LocaleCompositionLocals
                    .providedValue(LocalConfiguration.current, classLoader)
                    ?.let(::add)
                  add(
                    ee.schimke.composeai.preview.slots.LocalPreviewBackgroundCleared provides
                      spec.clearBackground
                  )
                  if (scrollReduceMotionLocal != null) add(scrollReduceMotionLocal provides true)
                }
                .toTypedArray()
            CompositionLocalProvider(*provided) {
              Box(modifier = Modifier.fillMaxSize().background(Color(bgArgb))) {
                // A scrolling preview has the same composition contract as its default capture.
                // In particular, app-owned environment such as SharedTransition scopes is often
                // installed by a PreviewWrapperProvider. Bypassing the wrapper here made the
                // default PNG succeed while LONG/GIF failed during composition.
                InvokeWithOptionalWrapper(
                  composableMethod = composableMethod,
                  wrapperFqnFromSpec = spec.wrapperClassName,
                  themeProviderFqn = spec.overrides?.themeProvider,
                )
              }
            }
          }
          rule.mainClock.advanceTimeBy(spec.captureAdvanceMs ?: CAPTURE_ADVANCE_MS)
          handled =
            when (spec.renderMode) {
              SCROLL_LONG_RENDER_MODE ->
                ee.schimke.composeai.renderer.handleLongCapture(
                  rule = rule,
                  scroll = scroll.toRendererScrollCapture(),
                  previewId = previewId,
                  heightDp = heightDp,
                  isRound = isRound,
                  outputFile = outputFile,
                )
              SCROLL_GIF_RENDER_MODE ->
                ee.schimke.composeai.renderer.handleGifCapture(
                  rule = rule,
                  scroll = scroll.toRendererScrollCapture(),
                  previewId = previewId,
                  heightDp = heightDp,
                  isRound = isRound,
                  outputFile = outputFile,
                )
              else -> false
            }
        }
      }
    rule.apply(statement, description).evaluate()

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
   * `figma-svg-long` dispatch (Android) — the **full-page** layered SVG of a scrolling preview
   * (`compose/figma-svg-long`), mirroring `:daemon:desktop`'s `runScrollSvgScenario`. A virtualised
   * `LazyColumn` composes only its on-screen rows, so this grows the viewport until the measured
   * content geometry stops increasing (every item composed), then re-renders once at the settled
   * height so the ordinary figma-svg post-capture extension emits the whole screen — pinned top bar,
   * every row, pinned bottom bar — as one editable tree. SVG-only; the PNG scroll story stays LONG /
   * GIF. See docs/design/SCROLLING_SVG.md.
   *
   * Android has no `setUp/renderOnce/tearDown` split (one monolithic `render()` under a single
   * `createAndroidComposeRule`, which forbids a second `setContent`), so the growth loop builds a
   * **fresh rule per probe** ([measureScrollAtHeight]) and the final export re-enters [render] with a
   * null render mode + an **isolated** output base — so the tall render's `compose/semantics` /
   * wireframe / PNG never overwrite the preview's normal-size products. Only the layered SVG (plus
   * its hybrid `figma-raster/` crops) is copied out to `<dataDir>/<previewId>/figma-long/`, the path
   * [ComposeFigmaSvgLongDataProductRegistry] reads back. A non-scrolling preview yields its viewport
   * SVG (nothing to grow). Sizing is by measured **geometry** (deepest composed descendant of the
   * scroll node), not the coarse LazyList scroll-range estimate.
   */
  /**
   * `figma-svg-long` dispatch for a **round Wear** scrolling preview — the slice-stitch capsule. In
   * one held `createAndroidComposeRule`, composes the preview with `LocalReduceMotion = true` (so the
   * `TransformingLazyColumn` items stack unscaled), then hands the live rule to
   * [WearScrollSvgAssembler], which drives the real scroll one viewport-step at a time, captures the
   * layout + semantics trees per slice, and stitches them into one tall capsule via
   * [ee.schimke.composeai.data.layoutinspector.WearScrollSliceStitcher] — chaining by shared-item
   * movement, placing each row at its true content position, pinning `TimeText`, and compositing the
   * settled `EdgeButton` crescent as one raster. The assembled trees are baked to
   * `<dataDir>/<previewId>/figma-long/compose-figma-long.svg` (plus its `figma-raster/` crops), the
   * same path [ComposeFigmaSvgLongDataProductRegistry] reads back and the served `?scroll=long` SVG.
   *
   * Returns `null` when the preview has no vertical scrollable (assembler saw nothing to stitch), so
   * [runScrollSvgScenario] falls back to its grow-tall path (which masks the single viewport to the
   * inscribed circle). SVG-only; the PNG scroll story stays LONG / GIF.
   */
  @OptIn(ExperimentalRoborazziApi::class)
  private fun runWearScrollSliceSvg(
    spec: RenderSpec,
    requestId: Long,
    classLoader: ClassLoader,
  ): RenderResult? {
    val previewId =
      spec.previewId
        ?: error(
          "RenderEngine: render mode '${spec.renderMode}' requires a previewId on the RenderSpec"
        )
    val scenarioDataDir =
      dataDir
        ?: error(
          "RenderEngine: render mode '${spec.renderMode}' needs a non-null dataDir to write " +
            "<dataDir>/<previewId>/figma-long/compose-figma-long.svg"
        )
    val startNs = System.nanoTime()
    val deviceDp = pxToDp(spec.widthPx, spec.density)

    applyPreviewQualifiers(
      widthDp = deviceDp,
      heightDp = pxToDp(spec.heightPx, spec.density),
      density = spec.density,
      isRound = true,
      localeTag = spec.localeTag,
      uiMode = spec.uiMode,
      orientation = spec.orientation,
    )
    org.robolectric.RuntimeEnvironment.setFontScale(spec.fontScale ?: 1.0f)

    val appContext: android.app.Application =
      androidx.test.core.app.ApplicationProvider.getApplicationContext()
    org.robolectric.Shadows.shadowOf(appContext.packageManager)
      .addActivityIfNotPresent(
        android.content.ComponentName(appContext.packageName, ComponentActivity::class.java.name)
      )

    @Suppress("DEPRECATION") val rule = createAndroidComposeRule<ComponentActivity>()
    val description =
      org.junit.runner.Description.createTestDescription(
        RenderEngine::class.java,
        "wearscrollslice_${spec.outputBaseName}",
      )
    val slotCapture = PreviewSlotTableCapture()
    // Scratch dir for the per-slice draws, settled frame, composited crescent, and the throwaway
    // SVG export — copied out to the long subdir below, then deleted.
    val workDir = File(outputDir, "$previewId$SCROLL_SVG_TMP_SUFFIX-slices")

    var assembled: WearScrollSvgAssembler.Assembled? = null
    val statement =
      object : org.junit.runners.model.Statement() {
        override fun evaluate() {
          rule.mainClock.autoAdvance = false
          val clazz = Class.forName(spec.className, true, classLoader)
          val composableMethod =
            clazz.getDeclaredComposableMethod(spec.functionName).also {
              runCatching { it.asMethod().isAccessible = true }
            }
          val bgArgb = resolveBackgroundColor(spec).toArgb()
          // Flatten Wear edge-scaling so the stitched items stack at natural size (same knob the
          // raster LONG path and the grow-tall probe use); no-op provider when not a Wear app.
          val reduceMotionLocal =
            ee.schimke.composeai.renderer.WearReduceMotionLocal.get(classLoader)
          rule.setContent {
            val provided =
              buildList {
                  add(LocalInspectionMode provides (spec.inspectionMode ?: true))
                  ee.schimke.composeai.renderer.LocaleCompositionLocals
                    .providedValue(LocalConfiguration.current, classLoader)
                    ?.let(::add)
                  add(
                    ee.schimke.composeai.preview.slots.LocalPreviewBackgroundCleared provides
                      spec.clearBackground
                  )
                  if (reduceMotionLocal != null) add(reduceMotionLocal provides true)
                }
                .toTypedArray()
            CompositionLocalProvider(*provided) {
              InspectablePreviewContent(slotCapture) {
                Box(modifier = Modifier.fillMaxSize().background(Color(bgArgb))) {
                  InvokeWithOptionalWrapper(
                    composableMethod = composableMethod,
                    wrapperFqnFromSpec = spec.wrapperClassName,
                    themeProviderFqn = spec.overrides?.themeProvider,
                  )
                }
              }
            }
          }
          rule.mainClock.advanceTimeBy(spec.captureAdvanceMs ?: CAPTURE_ADVANCE_MS)
          rule.waitForIdle()

          assembled =
            WearScrollSvgAssembler.assemble(
              rule = rule,
              // The captured frames + tree bounds are in pixels, so the stitcher width and crescent
              // crop must be the frame's *pixel* width — `deviceDp` above is for qualifiers only.
              deviceWidthPx = spec.widthPx,
              workDir = workDir,
              // No device crop: the tall capsule's `<clipPath>` masks the frame, so the source
              // frames (which the crescent raster crops from) must keep their full pixels.
              captureFrame = { file ->
                file.parentFile?.mkdirs()
                rule
                  .onRoot()
                  .captureRoboImage(
                    file = file,
                    roborazziOptions =
                      RoborazziOptions(
                        recordOptions = RoborazziOptions.RecordOptions(applyDeviceCrop = false)
                      ),
                  )
              },
              captureTree = {
                val semRoot = rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
                val semantics =
                  ComposeSemanticsDataProducer.buildPayload(semRoot, density = spec.density)
                val layout =
                  LayoutInspectorDataProducer.buildPayload(
                    root = semRoot,
                    slotTables = slotCapture.snapshot(),
                    density = spec.density,
                  )
                    ?: error(
                      "RenderEngine: layout tree unreachable for scroll-slice capture of $previewId"
                    )
                layout.root to semantics.root
              },
              rootId = previewId,
            )
        }
      }

    val previousContext = Thread.currentThread().contextClassLoader
    Thread.currentThread().contextClassLoader = classLoader
    try {
      rule.apply(statement, description).evaluate()
    } finally {
      Thread.currentThread().contextClassLoader = previousContext
    }

    val out =
      assembled
        ?: run {
          // Not vertically scrollable — let the caller fall back to the grow-tall / circle path.
          runCatching { workDir.deleteRecursively() }
          return null
        }

    // Bake the stitched trees to SVG in a scratch export dir, then copy the artefact (+ its hybrid
    // raster crops) into the long subdir the registry serves. Black device face, capsule clip.
    val exportDir = File(workDir, "export")
    ComposeFigmaSvgDataProducer.writeSvg(
      rootDir = exportDir,
      previewId = previewId,
      layout = out.layout,
      semantics = out.semantics,
      density = spec.density,
      // Match the render's font scale (setFontScale) so the long-scroll vector's text isn't
      // undersized against the boxes it was measured into.
      fontScale = spec.fontScale ?: 1.0f,
      frameImage = out.framePng,
      roundClip = true,
      deviceBackground = WEAR_DEVICE_FACE,
    )
    val producedSvg = exportDir.resolve(previewId).resolve(ComposeFigmaSvgProduct.FILE_SVG)
    if (!producedSvg.exists()) {
      runCatching { workDir.deleteRecursively() }
      error(
        "RenderEngine: render mode '${spec.renderMode}' produced no capsule SVG for previewId " +
          "'$previewId'"
      )
    }
    val longDir =
      scenarioDataDir.resolve(previewId).resolve(ComposeFigmaSvgProduct.LONG_SUBDIR).also {
        it.mkdirs()
      }
    val destSvg = longDir.resolve(ComposeFigmaSvgProduct.FILE_SVG_LONG)
    producedSvg.copyTo(destSvg, overwrite = true)
    val producedRasterDir = exportDir.resolve(previewId).resolve(ComposeFigmaSvgProduct.RASTER_DIR)
    if (producedRasterDir.isDirectory) {
      val destRasterDir =
        longDir.resolve(ComposeFigmaSvgProduct.RASTER_DIR).also { it.mkdirs() }
      producedRasterDir.listFiles()?.forEach { crop ->
        crop.copyTo(destRasterDir.resolve(crop.name), overwrite = true)
      }
    }
    runCatching { workDir.deleteRecursively() }

    val tookMs = (System.nanoTime() - startNs) / 1_000_000L
    System.err.println(
      "compose-ai-daemon: [render] phase=wearScrollSlice.done previewId=$previewId " +
        "height=${out.height} items=${out.itemCount} tookMs=$tookMs"
    )
    return RenderResult(
      id = requestId,
      classLoaderHashCode = System.identityHashCode(classLoader),
      classLoaderName = classLoader.javaClass.name,
      pngPath = destSvg.absolutePath,
      metrics = mapOf("tookMs" to tookMs),
    )
  }

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
    val scenarioDataDir =
      dataDir
        ?: error(
          "RenderEngine: render mode '${spec.renderMode}' needs a non-null dataDir to write " +
            "<dataDir>/<previewId>/figma-long/compose-figma-long.svg"
        )
    val startNs = System.nanoTime()

    // A round Wear preview is scrolled + slice-stitched into a vertical capsule (the tree-level twin
    // of the raster `render-scroll-long` PNG) rather than grown tall: growing a round `ScreenScaffold`
    // balloons its screen-fraction padding, so the faithful path drives the real scroll and places
    // each captured row at its true position. Falls back to the grow-tall path below when the round
    // preview turns out not to be vertically scrollable (nothing to stitch).
    if (isRoundDevice(spec.device)) {
      runWearScrollSliceSvg(spec = spec, requestId = requestId, classLoader = classLoader)?.let {
        return it
      }
    }

    val baseHeight = spec.heightPx
    val maxHeight = baseHeight + SCROLL_SVG_MAX_EXTRA_PX
    var probeHeight = baseHeight.coerceAtMost(maxHeight)
    var sizedHeight = baseHeight
    var prevContentBottom = -1
    var iterations = 0
    while (iterations < SCROLL_SVG_MAX_GROW_ITERATIONS) {
      iterations++
      val measure = measureScrollAtHeight(spec, probeHeight, classLoader)
      // Not a scrolling preview: nothing to grow, the viewport SVG is the full page.
      if (measure == null) {
        sizedHeight = baseHeight
        break
      }
      // The chrome pinned below the list (e.g. a Scaffold bottom bar) — the gap between the scroll
      // container's bottom and the frame bottom. Sizing the frame to `content + this` tucks that bar
      // directly under the last row.
      val bottomChrome = (probeHeight - measure.scrollNodeBottom).coerceAtLeast(0)
      sizedHeight =
        (measure.contentBottom + bottomChrome + SCROLL_SVG_CONTENT_MARGIN_PX).coerceIn(
          baseHeight,
          maxHeight,
        )
      // Fully composed once growing the viewport reveals no further content.
      if (measure.contentBottom <= prevContentBottom) break
      prevContentBottom = measure.contentBottom
      if (probeHeight >= maxHeight) break
      // Grow with a whole base viewport of headroom so the next batch of items composes.
      probeHeight = (measure.contentBottom + bottomChrome + baseHeight).coerceAtMost(maxHeight)
    }

    // Final render at the settled height into an isolated base, re-entering render() so the whole
    // post-capture pipeline (incl. ComposeFigmaSvgExtension) runs. `previewId = null` makes the
    // export key the figma-svg dir off the (isolated) outputBaseName.
    val tmpBase = "$previewId$SCROLL_SVG_TMP_SUFFIX"
    render(
      spec =
        spec.copy(
          renderMode = null,
          heightPx = sizedHeight,
          // The grown scroll render uses an explicit computed height — never wrap it (mirrors the
          // desktop daemon's growth-probe copy), so the tall frame isn't cropped back to intrinsic.
          wrapHeight = false,
          previewId = null,
          outputBaseName = tmpBase,
        ),
      requestId = requestId,
      classLoader = classLoader,
    )

    val tmpDir = scenarioDataDir.resolve(tmpBase)
    val producedSvg = tmpDir.resolve(ComposeFigmaSvgProduct.FILE_SVG)
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
      scenarioDataDir.resolve(previewId).resolve(ComposeFigmaSvgProduct.LONG_SUBDIR).also {
        it.mkdirs()
      }
    val destSvg = longDir.resolve(ComposeFigmaSvgProduct.FILE_SVG_LONG)
    val svgBytes = SystemFileSystem.read(producedSvg.path.toPath()) { readByteArray() }
    SystemFileSystem.write(destSvg.path.toPath()) { write(svgBytes) }
    // Carry the hybrid raster crops the SVG's `<image>` layers reference (Image/Icon/Canvas/… on a
    // scrolling screen) so those layers resolve instead of dangling.
    val tmpRasterDir = tmpDir.resolve(ComposeFigmaSvgProduct.RASTER_DIR)
    if (tmpRasterDir.isDirectory) {
      val destRasterDir =
        longDir.resolve(ComposeFigmaSvgProduct.RASTER_DIR).also { it.mkdirs() }
      tmpRasterDir.listFiles()?.forEach { crop ->
        val bytes = SystemFileSystem.read(crop.path.toPath()) { readByteArray() }
        SystemFileSystem.write(destRasterDir.resolve(crop.name).path.toPath()) { write(bytes) }
      }
    }
    // Best-effort cleanup of the throwaway render dir + its PNG.
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

  /**
   * Composes [spec] at [probeHeightPx] in a fresh `createAndroidComposeRule` and measures the
   * vertical scroll geometry, or null when nothing is vertically scrollable. A lightweight probe
   * (no capture, no post-capture extensions) used by [runScrollSvgScenario]'s growth loop; a fresh
   * rule per call because the Compose test rule forbids a second `setContent` on one activity.
   */
  @OptIn(ExperimentalRoborazziApi::class)
  private fun measureScrollAtHeight(
    spec: RenderSpec,
    probeHeightPx: Int,
    classLoader: ClassLoader,
  ): ee.schimke.composeai.renderer.ScrollContentMeasure.Measure? {
    val isRound = isRoundDevice(spec.device)
    applyPreviewQualifiers(
      widthDp = pxToDp(spec.widthPx, spec.density),
      heightDp = pxToDp(probeHeightPx, spec.density),
      density = spec.density,
      isRound = isRound,
      localeTag = spec.localeTag,
      uiMode = spec.uiMode,
      orientation = spec.orientation,
    )
    org.robolectric.RuntimeEnvironment.setFontScale(spec.fontScale ?: 1.0f)

    val appContext: android.app.Application =
      androidx.test.core.app.ApplicationProvider.getApplicationContext()
    org.robolectric.Shadows.shadowOf(appContext.packageManager)
      .addActivityIfNotPresent(
        android.content.ComponentName(appContext.packageName, ComponentActivity::class.java.name)
      )

    @Suppress("DEPRECATION") val rule = createAndroidComposeRule<ComponentActivity>()
    val description =
      org.junit.runner.Description.createTestDescription(
        RenderEngine::class.java,
        "figmasvglong_probe_${spec.outputBaseName}",
      )
    var measure: ee.schimke.composeai.renderer.ScrollContentMeasure.Measure? = null
    val statement =
      object : org.junit.runners.model.Statement() {
        override fun evaluate() {
          rule.mainClock.autoAdvance = false
          val clazz = Class.forName(spec.className, true, classLoader)
          val composableMethod =
            clazz.getDeclaredComposableMethod(spec.functionName).also {
              // Kotlin `private fun` scrolling previews compile to JVM-private methods; open them
              // so the reflective invoke doesn't throw IllegalAccessException — same as `render`.
              runCatching { it.asMethod().isAccessible = true }
            }
          val bgArgb = resolveBackgroundColor(spec).toArgb()
          // This probe only runs for `figma-svg-long`, so a round device here is always the Wear
          // scroll scenario: measure with `LocalReduceMotion = true` so the content height reflects
          // the FLATTENED (unscaled) list the final render produces — measuring the scaled layout
          // would size the frame to the wrong extent. No-op provider when not a Wear app.
          val wearReduceMotionLocal =
            if (isRound) ee.schimke.composeai.renderer.WearReduceMotionLocal.get(classLoader)
            else null
          rule.setContent {
            val provided =
              buildList {
                  // Match the final render's inspection mode so a preview that branches on
                  // `LocalInspectionMode.current` composes the same content the export will
                  // (measuring a different branch would size the frame to the wrong extent). The
                  // final SVG render re-enters `render` in the non-a11y path, i.e.
                  // `spec.inspectionMode ?: true`.
                  add(LocalInspectionMode provides (spec.inspectionMode ?: true))
                  ee.schimke.composeai.renderer.LocaleCompositionLocals
                    .providedValue(LocalConfiguration.current, classLoader)
                    ?.let(::add)
                  add(
                    ee.schimke.composeai.preview.slots.LocalPreviewBackgroundCleared provides
                      spec.clearBackground
                  )
                  if (wearReduceMotionLocal != null) add(wearReduceMotionLocal provides true)
                }
                .toTypedArray()
            CompositionLocalProvider(*provided) {
              Box(modifier = Modifier.fillMaxSize().background(Color(bgArgb))) {
                // Mirror the normal render's invocation so a `@PreviewWrapper` / theme-provider that
                // supplies the scrollable content is applied during measurement too, not just at the
                // final render.
                InvokeWithOptionalWrapper(
                  composableMethod,
                  wrapperFqnFromSpec = spec.wrapperClassName,
                  themeProviderFqn = spec.overrides?.themeProvider,
                )
              }
            }
          }
          rule.mainClock.advanceTimeBy(spec.captureAdvanceMs ?: CAPTURE_ADVANCE_MS)
          val root =
            runCatching { rule.onRoot(useUnmergedTree = true).fetchSemanticsNode() }.getOrNull()
          if (root != null)
            measure = ee.schimke.composeai.renderer.ScrollContentMeasure.measureVerticalScroll(root)
        }
      }
    // Install the request classloader as the context loader for the probe, exactly as the normal
    // render dispatch does: `InvokeWithOptionalWrapper` resolves `@PreviewWrapper` / theme-provider
    // classes via the thread context loader, and those live only on the app child classloader — so
    // without this install the probe would measure unwrapped content (or fail) while the final
    // render uses the wrapper.
    val previousContext = Thread.currentThread().contextClassLoader
    Thread.currentThread().contextClassLoader = classLoader
    try {
      rule.apply(statement, description).evaluate()
    } finally {
      Thread.currentThread().contextClassLoader = previousContext
    }
    return measure
  }

  /**
   * Lazily reads the daemon's `previews.json` via [PreviewIndex.loadFromFile] using the
   * `composeai.daemon.previewsJsonPath` system property the gradle plugin populates. Falls back to
   * [PreviewIndex.empty] when the property is unset (harness / fake-mode tests) — callers that need
   * a real index check for an empty result and emit a structured error.
   */
  private fun loadPreviewIndexLazily(): PreviewIndex {
    val path = System.getProperty(PreviewIndex.PREVIEWS_JSON_PATH_PROP)
    return if (path.isNullOrBlank()) {
      PreviewIndex.empty()
    } else {
      PreviewIndex.loadFromFile(java.nio.file.Paths.get(path))
    }
  }

  private fun ScrollCaptureDto.toRendererScrollCapture():
    ee.schimke.composeai.renderer.ScrollCapture =
    ee.schimke.composeai.renderer.ScrollCapture(
      mode =
        when (mode.uppercase()) {
          "LONG" -> ee.schimke.composeai.renderer.ScrollMode.LONG
          "GIF" -> ee.schimke.composeai.renderer.ScrollMode.GIF
          "END" -> ee.schimke.composeai.renderer.ScrollMode.END
          "TOP" -> ee.schimke.composeai.renderer.ScrollMode.TOP
          else -> error("ScrollCaptureDto: unknown mode '$mode'")
        },
      axis =
        when (axis.uppercase()) {
          "HORIZONTAL" -> ee.schimke.composeai.renderer.ScrollAxis.HORIZONTAL
          else -> ee.schimke.composeai.renderer.ScrollAxis.VERTICAL
        },
      maxScrollPx = maxScrollPx,
      reduceMotion = reduceMotion,
      frameIntervalMs = frameIntervalMs,
    )

  private fun resolveBackgroundColor(spec: RenderSpec): Color =
    when {
      spec.clearBackground -> Color.Transparent
      spec.backgroundColor != 0L -> Color(spec.backgroundColor.toInt())
      spec.showBackground -> Color.White
      else -> Color.Transparent
    }

  private fun pxToDp(px: Int, density: Float): Int {
    if (density <= 0f) return px
    return (px / density).toInt().coerceAtLeast(1)
  }

  /**
   * Crops [file] in-place to the measured intrinsic size on wrapped axes (content is placed at the
   * top-left of the sandbox window). The non-wrapped axis keeps its captured pixel extent; a wrapped
   * axis whose measured size wasn't recorded (`<= 0`) or already fills the window is left unchanged
   * (`fillMax*` composables). Uses `javax.imageio` on the JVM side — `captureRoboImage` has already
   * written a standard PNG — so no Robolectric `Bitmap` shadow is needed. Mirrors the standalone
   * renderer's `cropPngTopLeft` and the desktop daemon's `cropToMeasured`.
   */
  private fun cropWrappedPngTopLeft(
    file: File,
    wrapWidth: Boolean,
    wrapHeight: Boolean,
    measuredWidth: Int,
    measuredHeight: Int,
  ) {
    if (!file.exists()) return
    val original = runCatching { javax.imageio.ImageIO.read(file) }.getOrNull() ?: return
    val cropW =
      if (wrapWidth && measuredWidth in 1 until original.width) measuredWidth else original.width
    val cropH =
      if (wrapHeight && measuredHeight in 1 until original.height) measuredHeight else original.height
    if (cropW >= original.width && cropH >= original.height) return
    val cropped = original.getSubimage(0, 0, cropW, cropH)
    runCatching { javax.imageio.ImageIO.write(cropped, "PNG", file) }
  }

  /**
   * Builds and applies the Robolectric resource qualifier string for one render. Same
   * `RuntimeEnvironment` entrypoint as `RobolectricRenderTest.applyPreviewQualifiers`; the
   * difference is that the daemon takes locale / uiMode / orientation as overrides on the
   * [RenderSpec] (see PROTOCOL.md § 5 `renderNow.overrides`) rather than reading them off
   * `@Preview` annotation fields. Qualifier grammar is order-sensitive — locale, width, height,
   * round, orientation, uiMode (notnight/night), density.
   */
  private fun applyPreviewQualifiers(
    widthDp: Int,
    heightDp: Int,
    density: Float,
    isRound: Boolean,
    localeTag: String?,
    uiMode: RenderSpec.SpecUiMode?,
    orientation: RenderSpec.SpecOrientation?,
  ) {
    // Pseudolocales (`en-XA`, `ar-XB`) aren't first-class Android locales — they have no
    // `values-en-rXA/` resources to load. Substitute the base locale (`en`) before emitting the
    // qualifier so the framework still finds default-locale strings, and append `ldrtl` for
    // `ar-XB` so the Configuration reports an RTL layout direction. The pseudolocalisation of the
    // strings themselves happens in the around-composable `PseudolocaleOverrideExtension`
    // registered in `RobolectricHost`.
    val pseudo = ee.schimke.composeai.data.pseudolocale.Pseudolocale.fromTag(localeTag)
    val effectiveLocaleTag = if (pseudo != null) pseudo.baseTag else localeTag
    // A real RTL locale (`ar`, `he`, `fa`, …) also needs `ldrtl` so the layout mirrors like a real
    // device — `ar-XB` isn't the only RTL case.
    val rtl =
      pseudo?.isRtl == true ||
        (pseudo == null &&
          ee.schimke.composeai.data.pseudolocale.LocaleDirection.isRtl(effectiveLocaleTag))
    val qualifiers = buildList {
      if (!effectiveLocaleTag.isNullOrBlank()) add(localeTagToQualifier(effectiveLocaleTag))
      if (rtl) add("ldrtl")
      if (widthDp > 0) add("w${widthDp}dp")
      if (heightDp > 0) add("h${heightDp}dp")
      if (isRound) add("round")
      val derivedOrientation =
        when (orientation) {
          RenderSpec.SpecOrientation.PORTRAIT -> "port"
          RenderSpec.SpecOrientation.LANDSCAPE -> "land"
          null ->
            if (widthDp > 0 && heightDp > 0) {
              if (widthDp > heightDp) "land" else "port"
            } else null
        }
      if (derivedOrientation != null) add(derivedOrientation)
      when (uiMode) {
        RenderSpec.SpecUiMode.LIGHT -> add("notnight")
        RenderSpec.SpecUiMode.DARK -> add("night")
        null -> {}
      }
      if (density > 0f) add("${(density * 160).toInt()}dpi")
    }
    if (qualifiers.isNotEmpty()) {
      org.robolectric.RuntimeEnvironment.setQualifiers("+${qualifiers.joinToString("-")}")
    }
  }

  /**
   * Translates a BCP-47 locale tag (`en-US`, `fr`, `ja-JP`) to Robolectric's BCP-47 qualifier
   * spelling (`b+en+US`, `b+fr`, `b+ja+JP`). Robolectric matches Android's resource framework: the
   * `b+` prefix is mandatory for tags with non-empty regions or scripts; we use it unconditionally
   * for simplicity — single-tag forms like `b+en` are accepted.
   */
  private fun localeTagToQualifier(tag: String): String {
    val parts = tag.split('-', '_').filter { it.isNotBlank() }
    if (parts.isEmpty()) return ""
    return "b+${parts.joinToString("+")}"
  }

  companion object {
    /**
     * System property carrying the absolute path of the renders directory. Same name the desktop
     * side uses; the gradle plugin's daemon launch descriptor sets it once at JVM start.
     */
    const val OUTPUT_DIR_PROP: String = "composeai.render.outputDir"

    /**
     * Opt-in for the missing-resource placeholder fallback (see [PlaceholderFallbackResources]).
     * When `true`, a `stringResource` / `colorResource` lookup absent from the resource table
     * renders an obvious placeholder instead of throwing `Resources$NotFoundException`.
     *
     * **Off by default, on purpose.** The detached live/serve paths ([serve.ServeBundleDaemon],
     * [BundleDaemonCommand]) set it so a stale/incomplete packed bundle degrades gracefully in the
     * interactive viewer rather than showing a broken image. The `bundle pack --with-semantics`
     * semantics daemon (spawned from the gradle `daemon-launch.json`) deliberately leaves it off: it
     * renders from source where the app resource table is *supposed* to resolve, so a miss should
     * fail loudly (the pack then keeps the standalone `composePreviewRender` PNG) instead of baking a
     * placeholder into the published catalog sticker.
     */
    const val PLACEHOLDER_MISSING_RESOURCES_PROP: String =
      "composeai.render.placeholderMissingResources"

    /**
     * D2.2 — `RenderSpec.renderMode` value the daemon stamps when a `data/fetch` for an a11y kind
     * needs a fresh render, and when the host's per-preview subscription state demands a11y for the
     * next dispatch. When this is set, the engine's [runAccessibility] auto-resolution flips
     * `LocalInspectionMode = false` and writes ATF + hierarchy artefacts to `dataDir`.
     */
    const val A11Y_RENDER_MODE: String = "a11y"

    /**
     * Issue #1528 — scroll scenarios the daemon's `data/fetch` re-render path can request. The
     * registry in `:data-scroll-connector` advertises `render/scroll/long` / `render/scroll/gif` as
     * `requiresRerender = true`, so a missing scroll artefact returns
     * `Outcome.RequiresRerender("scroll-long" | "scroll-gif")` and the dispatcher submits a render
     * with `spec.renderMode` set to one of these constants. [render] routes scroll-mode requests
     * into [runScrollScenario], which delegates to the renderer's public
     * [ee.schimke.composeai.renderer.handleLongCapture] / [handleGifCapture] entry points.
     */
    const val SCROLL_LONG_RENDER_MODE: String = "scroll-long"

    const val SCROLL_GIF_RENDER_MODE: String = "scroll-gif"

    /**
     * Render mode requesting the **full-page** figma-svg export of a scrolling preview
     * (`compose/figma-svg-long`). `ComposeFigmaSvgLongDataProductRegistry` advertises the kind as
     * `requiresRerender = true`, so a missing artefact returns
     * `Outcome.RequiresRerender("figma-svg-long")` and the dispatcher submits a render with this
     * mode; [render] routes it into [runScrollSvgScenario]. Value matches `:daemon:desktop`'s
     * constant so a single payload drives either backend.
     */
    const val FIGMA_SVG_LONG_RENDER_MODE: String = "figma-svg-long"

    /** Max px the `figma-svg-long` growth loop will add on top of the base viewport. */
    private const val SCROLL_SVG_MAX_EXTRA_PX: Int = 40_000

    /** Max grow iterations before giving up (content whose height keeps shifting as it reflows). */
    private const val SCROLL_SVG_MAX_GROW_ITERATIONS: Int = 10

    /** A few px of slack added to the sized height so the last row's own bottom edge isn't clipped. */
    private const val SCROLL_SVG_CONTENT_MARGIN_PX: Int = 40

    /** Output-base suffix isolating the tall render so it can't clobber the preview's products. */
    private const val SCROLL_SVG_TMP_SUFFIX: String = "__figma_svg_long"

    /** Black watch face painted behind the round Wear slice-stitch capsule (opt-in device bg). */
    private const val WEAR_DEVICE_FACE: String = "#FF000000"

    /**
     * `RenderSpec.kind` value flagging a tile preview (non-composable function returning
     * `androidx.wear.tiles.tooling.preview.TilePreviewData`). Mirrors
     * `ee.schimke.composeai.plugin.PreviewKind.TILE` so a manifest-emitted value round-trips
     * unchanged through the daemon's render path.
     */
    const val TILE_KIND: String = "TILE"

    /**
     * `RenderSpec.kind` value flagging a notification preview (non-composable function returning
     * `android.app.Notification`). Mirrors `ee.schimke.composeai.plugin.PreviewKind.NOTIFICATION`
     * so a manifest-emitted value round-trips unchanged through the daemon's render path.
     */
    const val NOTIFICATION_KIND: String = "NOTIFICATION"

    /**
     * `RenderSpec.kind` value flagging an `@androidx.glance.preview.Preview` Glance app-widget
     * preview. Routes through `:renderer-android`'s `GlanceAppWidgetPreviewComposable`, which hosts
     * the user's `@GlanceComposable` inside a `GlanceAppWidget.providePreview(...)` and
     * materialises the tree to `RemoteViews` via `composeForPreview(...)`. Mirrors
     * `ee.schimke.composeai.discovery.PreviewKind.GLANCE_APPWIDGET`.
     */
    const val GLANCE_APPWIDGET_KIND: String = "GLANCE_APPWIDGET"

    /**
     * Virtual time to advance before capture in the paused-`mainClock` path, in milliseconds.
     * Mirrors `RobolectricRenderTest.CAPTURE_ADVANCE_MS` exactly so daemon-rendered PNGs match the
     * standalone JUnit path's settle point.
     */
    private const val CAPTURE_ADVANCE_MS = 32L
  }
}

private fun DeviceDimensions.DeviceSpec.previewDeviceSpec(): PreviewDeviceSpec =
  PreviewDeviceSpec(widthDp = widthDp, heightDp = heightDp, density = density, isRound = isRound)

/**
 * Detects whether a Compose `@Preview(device = ...)` string refers to a round (circular) display —
 * matches Wear OS round devices the same way `:renderer-android`'s `RoundClip.kt` does. Inlined
 * rather than depended on for the same reason `RenderEngine` itself is duplicated (see file kdoc):
 * the daemon doesn't take a compile-time dep on the renderer's internals. Reconcile when the v2
 * shared render-body extraction lands.
 */
internal fun isRoundDevice(device: String?): Boolean {
  if (device.isNullOrBlank()) return false
  val lower = device.lowercase()
  return lower.contains("_round") || lower.contains("isround=true") || lower.contains("shape=round")
}

/**
 * Tiny @Composable trampoline that invokes [composableMethod] reflectively against the current
 * composer. Mirrors `:daemon:desktop`'s and `:renderer-desktop`'s private `InvokeComposable` — kept
 * private+top-level so the compose-compiler plugin recognises it as a composable function.
 */
@Composable
private fun InvokeComposable(composableMethod: ComposableMethod) {
  composableMethod.invoke(currentComposer, null)
}

/**
 * Wraps [InvokeComposable] in the preview's `@PreviewWrapper(SomeProvider::class)` `Wrap { … }` if
 * one is present, sourcing the wrapper FQN from [wrapperFqnFromSpec] (the discovery-time class-file
 * read) with a best-effort runtime-reflection fallback for direct-payload callers that bypass the
 * manifest. Without this the daemon would call the preview body directly and bypass the wrapper —
 * e.g. `@PreviewWrapper(RemotePreviewWrapper::class)` previews would crash with
 * `IllegalStateException: Invalid applier` the moment they emit a `RemoteBox` / `RemoteColumn` /
 * `RemoteRow`, because those composables require the RemoteCompose applier the wrapper installs.
 * `:renderer-android` does the equivalent in
 * [ee.schimke.composeai.renderer.PreviewRenderStrategy]'s ComposePreviewStrategy.
 *
 * **Why the FQN comes from the spec rather than `Method.annotations`.** The upstream
 * `androidx.compose.ui.tooling.preview.PreviewWrapper` annotation has `AnnotationRetention.BINARY`
 * (issue #1440): it's emitted into the class file but not retained at runtime, so
 * `jvmMethod.annotations` will never include it. The gradle plugin's `extractWrapperFqn` reads the
 * FQN off the class-file annotation tables via ClassGraph and writes it into `previews.json`; the
 * daemon's [PreviewManifestRouter] threads it into [RenderSpec.wrapperClassName] and we read it
 * from there. The runtime-reflection fallback is retained for legacy callers and the
 * compose-bom-compat regression test (`PreviewWrapperResolutionTest`), which uses a same-FQN
 * stand-in annotation with binary retention to mirror the production retention exactly.
 *
 * Wrapper class resolution flows through [loadPreviewWrapperClass], so connector-provided SPI
 * substitutions (e.g. `:data-remotecompose-connector` swapping `RemotePreviewWrapper` for
 * `RemoteOverridablePreviewWrapper`) apply transparently.
 */
@Composable
internal fun InvokeWithOptionalWrapper(
  composableMethod: ComposableMethod,
  wrapperFqnFromSpec: String?,
  themeProviderFqn: String? = null,
) {
  val wrapper =
    remember(composableMethod, wrapperFqnFromSpec, themeProviderFqn) {
      // A `themeProvider` override wraps the preview in an app-declared theme provider IN PLACE OF
      // its own `@PreviewWrapper` — but only when it actually loads. On a stale/misspelled FQN,
      // `loadWrapperByFqnOrNull` logs and returns null; we then fall back to the preview's declared
      // wrapper rather than stripping it (which would drop a required `@PreviewWrapper` and
      // misrender). A blank/absent themeProvider skips straight to the declared wrapper.
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
 * Render an IR-backed preview through a connector-provided replay composable resolved by
 * [loadIrReplayClass]. The class exposes `@Composable Replay(bytes: ByteArray)` and a no-arg ctor;
 * we invoke it the same way [InvokeWithOptionalWrapper] invokes `PreviewWrapperProvider.Wrap` — via
 * [getDeclaredComposableMethod], which absorbs the synthetic `Composer`/`changed` tail. Used for
 * Remote Compose, whose player lives in the alpha connector the daemon can't compile against.
 */
@Composable
private fun InvokeIrReplay(replayClass: Class<*>, bytes: ByteArray) {
  val (method, instance) =
    remember(replayClass) {
      val inst = replayClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
      replayClass.getDeclaredComposableMethod("Replay", ByteArray::class.java) to (inst as Any)
    }
  method.invoke(currentComposer, instance, bytes)
}

/**
 * Resolves the `@PreviewWrapper`'s `PreviewWrapperProvider` into a `Wrap(content)` method plus an
 * instance.
 *
 * Strategy (in order):
 * 1. Use [wrapperFqnFromSpec] when supplied — production path, sourced from `previews.json` (the
 *    gradle plugin's `extractWrapperFqn` reads it from the class file at discovery time, where the
 *    `AnnotationRetention.BINARY` annotation is still visible).
 * 2. Otherwise, look up `@androidx.compose.ui.tooling.preview.PreviewWrapper` reflectively off
 *    [composableMethod]'s underlying JVM method. This is a fallback for direct-payload callers (a
 *    few internal tests and the legacy stub path) and **does not work in production** — the
 *    upstream annotation is `AnnotationRetention.BINARY`, so the lookup misses every real-world
 *    preview. Kept for compatibility with the existing `PreviewWrapperResolutionTest` regression
 *    guard, which now uses a binary-retained stand-in and the spec-driven path.
 *
 * Returns null when no wrapper is resolvable.
 *
 * Reflective lookup (rather than a compile-time import of `PreviewWrapper`) keeps the daemon
 * runnable on older Compose runtimes that predate the annotation (1.11.0-beta+). The annotation
 * parameter name (`wrapper`) matches what the ClassGraph-based discovery in
 * `gradle-plugin/preview-discovery` reads.
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

/**
 * Fallback path used only when [resolveWrapperOrNull] was called without a spec-supplied FQN.
 * Production previews come through with [RenderSpec.wrapperClassName] populated by the gradle
 * plugin's class-file scan; this path is here for legacy direct-payload tests + the binary-
 * retained regression-test stand-in (which is now also exercised via the spec path).
 */
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
      // PreviewWrapperProvider.Wrap(content: @Composable () -> Unit) compiles to
      // Wrap(Function2, Composer, int); getDeclaredComposableMethod handles the synthetic
      // Composer/changed tail, so we look up by the content param's JVM type.
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
  var compositionData: CompositionData? = null

  fun snapshot(): List<CompositionData> = listOfNotNull(compositionData)
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
@Composable
private fun InspectablePreviewContent(
  capture: PreviewSlotTableCapture,
  content: @Composable () -> Unit,
) {
  currentComposer.collectParameterInformation()
  capture.compositionData = currentComposer.compositionData
  content()
}

/**
 * What [RenderEngine.render] needs to produce a single PNG. Decoupled from the protocol's
 * `RenderRequest` so the engine has no dependency on the JSON-RPC envelope shapes.
 *
 * **Duplicated from `:daemon:desktop`'s `RenderSpec`.** Kept duplicated rather than promoted to
 * `:daemon:core` per DESIGN § 7: the two backends could share this *today* because the parser is
 * pure-data, but promoting would widen the renderer-agnostic surface for a type that's about to be
 * replaced when `RenderRequest` grows a typed `previewId: String?` field (B2.2 /
 * `IncrementalDiscovery`). Duplication has a known reconciliation cost; promotion has a known
 * revert cost. We pay the duplication cost.
 *
 * Wire format identical to desktop's so the harness's `PreviewManifestRouter` and any future
 * cross-backend driver can drive both backends with the same payload string.
 */
data class RenderSpec(
  val previewId: String? = null,
  /** Fully-qualified name of the class containing the @Preview function. */
  val className: String,
  /** Method name of the @Preview function (parameterless overload). */
  val functionName: String,
  val widthPx: Int = 320,
  val heightPx: Int = 320,
  /**
   * AS-parity wrap-content flags. Set when the preview declares no explicit size (and no device /
   * system UI), so [widthPx]/[heightPx] are a generous sandbox bound rather than a fixed frame: the
   * render measures the composable's intrinsic size on the wrapped axis and crops the PNG to it, and
   * the captured layout tree (figma-svg / wireframe / semantics derived from it) reflects the
   * preview's natural size. Without this a no-height preview rendered into the historical 320px frame
   * and any content taller than the frame reflowed to zero height — a `Column` hands each child the
   * *remaining* height, so once the 320px budget is spent the overflow children measure to 0 lines
   * (collapsed text fields / buttons in the export). Mirrors the standalone renderer's
   * `wrapWidth`/`wrapHeight` and the desktop daemon's identically-named [RenderSpec] fields.
   */
  val wrapWidth: Boolean = false,
  val wrapHeight: Boolean = false,
  val density: Float = 2.0f,
  val showBackground: Boolean = true,
  val backgroundColor: Long = 0L,
  /**
   * Per-render cleared-background toggle ("crisp outline"). When `true` the harness background is
   * forced transparent (overriding [showBackground]/[backgroundColor]) and
   * `LocalPreviewBackgroundCleared = true` is provided around the preview. Default `false`
   * preserves the discovery-time background.
   */
  val clearBackground: Boolean = false,
  /**
   * Raw `@Preview(device = …)` string when known — `id:wearos_small_round`,
   * `spec:width=…,isRound=true`, `id:pixel_5`, etc. Used by the render body to detect round Wear
   * devices and apply the circular crop / `round` resource qualifier; non-round / null values are a
   * no-op. Mirrors the standalone renderer's `RenderPreviewParams.device` for the v1 subset.
   */
  val device: String? = null,
  /** Optional data-product render mode, e.g. `theme` for `compose/theme` fetch rerenders. */
  val renderMode: String? = null,
  /** Stem used for the output PNG filename (e.g. "preview-A" → "<outputDir>/preview-A.png"). */
  val outputBaseName: String = "${className.substringAfterLast('.')}-$functionName",
  /**
   * BCP-47 locale tag — overrides the default qualifier set when non-null. Threaded through the `+`
   * qualifier prefix as `b+lang+region` (Robolectric grammar; see `applyPreviewQualifiers`).
   */
  val localeTag: String? = null,
  /**
   * Font scale multiplier. Null means "use whatever Robolectric defaults to" (1.0). Non-null routes
   * through `RuntimeEnvironment.setFontScale` — same `Configuration` knob `RoborazziCompose
   * FontScaleOption` uses.
   */
  val fontScale: Float? = null,
  /** Light/dark mode override → `notnight` / `night` qualifier. */
  val uiMode: SpecUiMode? = null,
  /** Portrait/landscape override → `port` / `land` qualifier. Overrides the size-derived guess. */
  val orientation: SpecOrientation? = null,
  /**
   * Paused-clock advance (ms) before capture. Null defaults to [CAPTURE_ADVANCE_MS]; values `<= 0`
   * are treated as null (default). Routes per-render via PROTOCOL.md § 5
   * (`renderNow.overrides.captureAdvanceMs`); animation-heavy previews can request a longer settle
   * window without editing the render body.
   */
  val captureAdvanceMs: Long? = null,
  /**
   * Per-render `LocalInspectionMode` override for one-shot renders. Null preserves preview
   * semantics (`true`); a11y mode still forces `false` so accessibility semantics are populated.
   */
  val inspectionMode: Boolean? = null,
  /**
   * Per-call overrides bag, threaded through every registered [PreviewOverrideExtension]. The
   * renderer doesn't read individual fields directly — registered planners decide what to apply.
   * Direct-applied overrides like size, density, and locale stay on this spec's typed fields above
   * because the renderer applies them itself; theme/wallpaper-style overrides ride along here so
   * adding a new override-driven feature is purely a connector concern.
   */
  val overrides: PreviewOverrides? = null,
  /**
   * Preview flavour, mirroring `ee.schimke.composeai.plugin.PreviewKind` (`"COMPOSE"` / `"TILE"` /
   * `"NOTIFICATION"` / `"GLANCE_APPWIDGET"`). Drives renderer selection: `"TILE"` routes through
   * [renderer.TilePreviewComposable], `"NOTIFICATION"` through `NotificationPreviewComposable`,
   * `"GLANCE_APPWIDGET"` through `GlanceAppWidgetPreviewComposable`. `null` / `"COMPOSE"` render as
   * a normal `@Composable`.
   */
  val kind: String? = null,
  /**
   * FQN of the `PreviewWrapperProvider` from `@PreviewWrapper(SomeProvider::class)` when the source
   * preview is annotated. Sourced from the gradle plugin's discovery JSON (`extractWrapperFqn`
   * reads it off the class-file annotation tables — the upstream annotation has
   * `AnnotationRetention.BINARY` and is invisible to `Method.annotations` at runtime). The render
   * body drives `InvokeWithOptionalWrapper` off this field when set; null falls back to the
   * (best-effort) runtime-reflection lookup for direct-payload callers that bypass the manifest.
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
     * (`true`/`false`). `className` and `functionName` are required; everything else falls back to
     * the defaults on this data class. Returns `null` when the payload doesn't carry a `className=`
     * token (the discriminator the host uses to route legacy stub-payload requests through the
     * classloader-identity path).
     */
    fun parseFromPayloadOrNull(payload: String): RenderSpec? {
      if (!payload.contains("className=")) return null
      val map = mutableMapOf<String, String>()
      for (entry in payload.split(';')) {
        val trimmed = entry.trim()
        if (trimmed.isEmpty()) continue
        val eq = trimmed.indexOf('=')
        if (eq <= 0) continue
        map[trimmed.substring(0, eq).trim()] = trimmed.substring(eq + 1).trim()
      }
      val className = map["className"] ?: return null
      val functionName = map["functionName"] ?: return null
      val defaults = RenderSpec(className = className, functionName = functionName)
      return RenderSpec(
        previewId = map["previewId"]?.takeIf { it.isNotBlank() },
        className = className,
        functionName = functionName,
        widthPx = map["widthPx"]?.toIntOrNull() ?: defaults.widthPx,
        heightPx = map["heightPx"]?.toIntOrNull() ?: defaults.heightPx,
        wrapWidth = map["wrapWidth"]?.toBoolean() ?: defaults.wrapWidth,
        wrapHeight = map["wrapHeight"]?.toBoolean() ?: defaults.wrapHeight,
        density = map["density"]?.toFloatOrNull() ?: defaults.density,
        showBackground = map["showBackground"]?.toBoolean() ?: defaults.showBackground,
        backgroundColor = map["backgroundColor"]?.toLongOrNull() ?: defaults.backgroundColor,
        clearBackground = map["clearBackground"]?.toBoolean() ?: defaults.clearBackground,
        device = map["device"]?.takeIf { it.isNotBlank() } ?: defaults.device,
        renderMode = map["mode"]?.takeIf { it.isNotBlank() },
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
        captureAdvanceMs = map["captureAdvanceMs"]?.toLongOrNull()?.takeIf { it > 0L },
        inspectionMode = map["inspectionMode"]?.toBooleanStrictOrNull(),
        overrides = map["overrides"]?.decodePreviewOverrides(),
        kind = map["kind"]?.takeIf { it.isNotBlank() },
        wrapperClassName = map["wrapperClassName"]?.takeIf { it.isNotBlank() },
      )
    }

    private val json = Json {
      ignoreUnknownKeys = true
      encodeDefaults = false
    }

    private fun String.decodePreviewOverrides(): PreviewOverrides? =
      runCatching {
          decodeBase64()?.utf8()?.let { json.decodeFromString(PreviewOverrides.serializer(), it) }
        }
        .getOrNull()
  }
}
