package ee.schimke.composeai.daemon

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.reflect.ComposableMethod
import androidx.compose.runtime.reflect.getDeclaredComposableMethod
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import ee.schimke.composeai.renderer.AccessibilityChecker
import java.io.File

/**
 * Robolectric/Compose render body for the preview daemon — the per-preview inner loop that turns a
 * resolved class+method reference into a PNG on disk.
 *
 * **Duplicated from
 * [`renderer-android`'s `RobolectricRenderTest`](../../../../../../../renderer-android/src/main/kotlin/ee/schimke/composeai/renderer/RobolectricRenderTest.kt).**
 * Per
 * [DESIGN.md § 7](../../../../../../docs/daemon/DESIGN.md#7-sharing-strategy--what-crosses-the-boundary)
 * the v1 render body lives in two places — the standalone JUnit-driven renderer and the daemon — so
 * the daemon doesn't depend on the renderer's `@RunWith(ParameterizedRobolectricTestRunner)` entry
 * point. v2's reconciliation extracts the body into a shared helper. Until then any change to the
 * core render body landed here also has to land in `:renderer-android`'s `renderDefault` (and vice
 * versa); the `:samples:android-daemon-bench:renderPreviews` task + CI pixel-diff catches drift.
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
 * **No-mid-render-cancellation invariant** (DESIGN § 9). Cleanup runs in `try/finally`:
 * `setContent { }` on a fresh empty body to give Compose a frame to dispose `LaunchedEffect` /
 * `DisposableEffect`, then explicit `mainClock.advanceTimeBy(CAPTURE_ADVANCE_MS)` on the empty
 * tree. The `ActivityScenario` is closed by the rule's outer statement when `evaluate()` returns;
 * we never leave one open across renders. This is the Android equivalent of desktop's
 * `scene.close()` discipline (DESIGN § 10) — without the empty-setContent flush, a `LaunchedEffect`
 * holding a Job in the previous preview survives into the next render's composition and shows up
 * as cross-render visual drift.
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
  private val dataDir: File? =
    (outputDir.parentFile ?: outputDir).resolve("data"),
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
     * B2.3 — per-sandbox lifecycle counters owned by [RobolectricHost.SandboxRunner]. Captured
     * at sandbox-init time and incremented on every render-completion via
     * [SandboxMeasurement.collect]. Defaults to a fresh per-call instance for unit tests that
     * drive the engine directly without a sandbox; the resulting metrics still populate, just
     * with a sandbox-age that resets per test render.
     */
    sandboxStats: SandboxLifecycleStats = SandboxLifecycleStats(),
    /**
     * D2 — when true, render in a11y mode (`LocalInspectionMode = false`) and dump
     * `a11y-atf.json` + `a11y-hierarchy.json` under [dataDir] so the daemon's data-product
     * registry can surface them as `a11y/atf` + `a11y/hierarchy`. Mirrors the design doc's
     * "produce always, gate emission on subscriptions" approach — the cost is a few ms per
     * render, traded for simpler implementation than per-render kind threading.
     *
     * Defaults to the [ATTACH_A11Y_PROP] system property — `composeai.daemon.attachA11y=true` set
     * by [DaemonMain] when the a11y data-product registry is wired. False (the default) keeps the
     * pre-D2 paused-clock + `LocalInspectionMode = true` fast path used by the harness's fake-mode
     * tests.
     */
    runAccessibility: Boolean = System.getProperty(ATTACH_A11Y_PROP) == "true",
  ): RenderResult {
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

    val clazz = Class.forName(spec.className, true, classLoader)
    val composableMethod: ComposableMethod = clazz.getDeclaredComposableMethod(spec.functionName)

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

    // `device = "id:wearos_*_round"` / `isRound=true` previews need a circular crop matching the
    // standalone renderer's `RobolectricRenderTest`. The standalone path also gates on
    // `showSystemUi || kind == TILE` to skip the crop on non-fullscreen previews (the crop is a
    // device frame, not part of the composable), but the daemon's v1 `RenderSpec` doesn't carry
    // either field — assume any explicit round-device request wants the crop. Refine when those
    // fields get plumbed through.
    val isRound = isRoundDevice(spec.device)

    // Per-preview Robolectric configuration — qualifiers re-applied so a previous render's size /
    // density doesn't bleed into this one. Same entrypoints `RobolectricRenderTest` uses; both
    // mutate `RuntimeEnvironment` global state, which is OK here because the sandbox is single-
    // threaded under our render loop (DESIGN § 9 invariant: no concurrent renders).
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
    @Suppress("DEPRECATION")
    val rule = createAndroidComposeRule<ComponentActivity>()
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

            rule.setContent {
              // D2 — a11y mode flips LocalInspectionMode off so Compose populates real
              // accessibility semantics (mergeMode, contentDescription, role) for ATF + the
              // hierarchy walk to consume after capture. Tradeoff: infinite animations tick
              // through rather than parking under the paused clock — same trade
              // `RobolectricRenderTest.renderWithA11y` already pays.
              CompositionLocalProvider(LocalInspectionMode provides !runAccessibility) {
                Box(modifier = Modifier.fillMaxSize()) { InvokeComposable(composableMethod) }
              }
            }

            // CAPTURE_ADVANCE_MS is the same paused-clock advance `RobolectricRenderTest` uses —
            // ≈ 2 Choreographer frames. Enough to settle initial composition + one
            // `LaunchedEffect` pass; deterministic snapshot point for any infinite animation.
            rule.mainClock.advanceTimeBy(CAPTURE_ADVANCE_MS)

            outputFile.parentFile?.mkdirs()
            // `applyDeviceCrop = true` is what produces the circular alpha mask Roborazzi paints
            // over the captured bitmap; the `round` resource qualifier set above only affects
            // `Configuration.isScreenRound`. Both are needed for parity with the standalone
            // renderer's wear-round path.
            val roborazziOptions =
              RoborazziOptions(recordOptions = RoborazziOptions.RecordOptions(applyDeviceCrop = isRound))
            rule
              .onRoot()
              .captureRoboImage(file = outputFile, roborazziOptions = roborazziOptions)

            // D2 — a11y data products. Walk the same `ViewRootForTest` ATF can populate, dump
            // `a11y-atf.json` (findings) and `a11y-hierarchy.json` (nodes) next to the PNG. The
            // dispatcher reads these on `data/fetch` / `data/subscribe` attachment via
            // `AccessibilityDataProductRegistry`. Wrapped in try/catch so an a11y failure does
            // not strand the PNG the user already cares about — the registry sees a missing
            // file as "no attachment for this kind on this render".
            if (runAccessibility && dataDir != null) {
              try {
                val view = (rule.onRoot().fetchSemanticsNode().root as ViewRootForTest).view
                val a11yResult = AccessibilityChecker.analyze(spec.outputBaseName, view)
                AccessibilityDataProducer.writeArtifacts(
                  rootDir = dataDir,
                  previewId = spec.outputBaseName,
                  findings = a11yResult.findings,
                  nodes = a11yResult.nodes,
                )
              } catch (t: Throwable) {
                System.err.println(
                  "RenderEngine: a11y data write failed for ${spec.outputBaseName}: " +
                    "${t.javaClass.simpleName}: ${t.message}"
                )
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
              rule.mainClock.advanceTimeBy(CAPTURE_ADVANCE_MS)
            } catch (t: Throwable) {
              System.err.println(
                "RenderEngine: post-capture mainClock advance failed for ${spec.className}.${spec.functionName}: ${t.message}"
              )
            }
          }
        }
      }
    // B2.0 — install the child classloader as the context classloader for the duration of the
    // render dispatch. Compose's reflection paths (notably PreviewParameter providers — see
    // CLASSLOADER.md § Risks 2) consult the context classloader; without this install they would
    // miss user classes that aren't on the parent's (sandbox) classpath. Restored in `finally` so
    // the surrounding sandbox bootstrap path is unaffected.
    val previousContext = Thread.currentThread().contextClassLoader
    Thread.currentThread().contextClassLoader = classLoader
    try {
      rule.apply(statement, description).evaluate()
    } finally {
      Thread.currentThread().contextClassLoader = previousContext
    }

    val tookMs = (System.nanoTime() - startNs) / 1_000_000L
    val metrics = SandboxMeasurement.collect(sandboxStats, tookMs = tookMs)
    return RenderResult(
      id = requestId,
      classLoaderHashCode = System.identityHashCode(classLoader),
      classLoaderName = classLoader.javaClass.name,
      pngPath = outputFile.absolutePath,
      metrics = metrics,
    )
  }

  private fun resolveBackgroundColor(spec: RenderSpec): Color =
    when {
      spec.backgroundColor != 0L -> Color(spec.backgroundColor.toInt())
      spec.showBackground -> Color.White
      else -> Color.Transparent
    }

  private fun pxToDp(px: Int, density: Float): Int {
    if (density <= 0f) return px
    return (px / density).toInt().coerceAtLeast(1)
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
    val qualifiers = buildList {
      if (!localeTag.isNullOrBlank()) add(localeTagToQualifier(localeTag))
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
     * D2 — when set to `"true"` (by [DaemonMain] after wiring the a11y data-product registry)
     * each render runs in a11y mode and writes `a11y-{atf,hierarchy}.json` artefacts under
     * `<outputDir.parent>/data/<previewId>/`. Tests / pre-D2 callers leave it unset and the
     * fast path stays unchanged.
     */
    const val ATTACH_A11Y_PROP: String = "composeai.daemon.attachA11y"

    /**
     * Virtual time to advance before capture in the paused-`mainClock` path, in milliseconds.
     * Mirrors `RobolectricRenderTest.CAPTURE_ADVANCE_MS` exactly so daemon-rendered PNGs match
     * the standalone JUnit path's settle point.
     */
    private const val CAPTURE_ADVANCE_MS = 32L
  }
}

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
  return lower.contains("_round") ||
    lower.contains("isround=true") ||
    lower.contains("shape=round")
}

/**
 * Tiny @Composable trampoline that invokes [composableMethod] reflectively against the current
 * composer. Mirrors `:daemon:desktop`'s and `:renderer-desktop`'s private
 * `InvokeComposable` — kept private+top-level so the compose-compiler plugin recognises it as a
 * composable function.
 */
@Composable
private fun InvokeComposable(composableMethod: ComposableMethod) {
  composableMethod.invoke(currentComposer, null)
}

/**
 * What [RenderEngine.render] needs to produce a single PNG. Decoupled from the protocol's
 * `RenderRequest` so the engine has no dependency on the JSON-RPC envelope shapes.
 *
 * **Duplicated from `:daemon:desktop`'s `RenderSpec`.** Kept duplicated rather than
 * promoted to `:daemon:core` per DESIGN § 7: the two backends could share this *today*
 * because the parser is pure-data, but promoting would widen the renderer-agnostic surface for a
 * type that's about to be replaced when `RenderRequest` grows a typed `previewId: String?` field
 * (B2.2 / `IncrementalDiscovery`). Duplication has a known reconciliation cost; promotion has a
 * known revert cost. We pay the duplication cost.
 *
 * Wire format identical to desktop's so the harness's `PreviewManifestRouter` and any future
 * cross-backend driver can drive both backends with the same payload string.
 */
data class RenderSpec(
  /** Fully-qualified name of the class containing the @Preview function. */
  val className: String,
  /** Method name of the @Preview function (parameterless overload). */
  val functionName: String,
  val widthPx: Int = 320,
  val heightPx: Int = 320,
  val density: Float = 2.0f,
  val showBackground: Boolean = true,
  val backgroundColor: Long = 0L,
  /**
   * Raw `@Preview(device = …)` string when known — `id:wearos_small_round`,
   * `spec:width=…,isRound=true`, `id:pixel_5`, etc. Used by the render body to detect round Wear
   * devices and apply the circular crop / `round` resource qualifier; non-round / null values are
   * a no-op. Mirrors the standalone renderer's `RenderPreviewParams.device` for the v1 subset.
   */
  val device: String? = null,
  /** Stem used for the output PNG filename (e.g. "preview-A" → "<outputDir>/preview-A.png"). */
  val outputBaseName: String = "${className.substringAfterLast('.')}-$functionName",
  /**
   * BCP-47 locale tag — overrides the default qualifier set when non-null. Threaded through the
   * `+` qualifier prefix as `b+lang+region` (Robolectric grammar; see `applyPreviewQualifiers`).
   */
  val localeTag: String? = null,
  /**
   * Font scale multiplier. Null means "use whatever Robolectric defaults to" (1.0). Non-null
   * routes through `RuntimeEnvironment.setFontScale` — same `Configuration` knob `RoborazziCompose
   * FontScaleOption` uses.
   */
  val fontScale: Float? = null,
  /** Light/dark mode override → `notnight` / `night` qualifier. */
  val uiMode: SpecUiMode? = null,
  /** Portrait/landscape override → `port` / `land` qualifier. Overrides the size-derived guess. */
  val orientation: SpecOrientation? = null,
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
     * `uiMode` (`light`/`dark`), `orientation` (`portrait`/`landscape`). `className` and
     * `functionName` are required; everything else falls back to the defaults on this data class.
     * Returns `null` when the payload doesn't carry a `className=` token (the discriminator the
     * host uses to route legacy stub-payload requests through the classloader-identity path).
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
        className = className,
        functionName = functionName,
        widthPx = map["widthPx"]?.toIntOrNull() ?: defaults.widthPx,
        heightPx = map["heightPx"]?.toIntOrNull() ?: defaults.heightPx,
        density = map["density"]?.toFloatOrNull() ?: defaults.density,
        showBackground = map["showBackground"]?.toBoolean() ?: defaults.showBackground,
        backgroundColor = map["backgroundColor"]?.toLongOrNull() ?: defaults.backgroundColor,
        device = map["device"]?.takeIf { it.isNotBlank() } ?: defaults.device,
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
      )
    }
  }
}
