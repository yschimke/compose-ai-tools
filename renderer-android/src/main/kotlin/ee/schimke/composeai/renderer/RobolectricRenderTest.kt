package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.reflect.ComposableMethod
import androidx.compose.runtime.reflect.getDeclaredComposableMethod
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.RoborazziComposeSetupOption
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.background
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.fontScale
import com.github.takahirom.roborazzi.inspectionMode
import com.github.takahirom.roborazzi.locale
import com.github.takahirom.roborazzi.size
import com.github.takahirom.roborazzi.uiMode
import java.awt.image.BufferedImage
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

/**
 * Loads the previews manifest and returns the subset assigned to `shardIndex`
 * out of `shardCount` shards. Generated shard subclasses delegate their
 * `@Parameters` method here (see the plugin's `generateShardTests` task).
 *
 * With `shardCount = 1`, returns every preview — that's the default single-class path.
 *
 * System properties:
 *   composeai.render.manifest  — path to previews.json
 *   composeai.render.outputDir — directory for rendered PNGs
 */
object PreviewManifestLoader {
    private val json = Json { ignoreUnknownKeys = true }

    @JvmStatic
    fun loadShard(shardIndex: Int, shardCount: Int): List<Array<Any>> {
        require(shardCount >= 1) { "shardCount must be >= 1" }
        require(shardIndex in 0 until shardCount) { "shardIndex must be in [0, $shardCount)" }
        val manifestPath = System.getProperty("composeai.render.manifest")
            ?: return emptyList()
        val file = File(manifestPath)
        if (!file.exists()) return emptyList()

        val manifest = json.decodeFromString<RenderManifest>(file.readText())
        // Expand `@PreviewParameter` providers into one row per value BEFORE
        // sharding, so one preview's values never span multiple shards — each
        // (preview, value) row carries an already-suffixed id / renderOutput
        // ready for the test runner. Values are kept alongside the entry
        // (Array<Any>[entry, args]) instead of being serialised back into the
        // manifest: provider values can be arbitrary runtime objects, often
        // not JSON-representable.
        val expanded = manifest.previews.flatMap { expandParameterProvider(it) }
        return expanded
            .withIndex()
            .filter { (i, _) -> i % shardCount == shardIndex }
            .map { (_, row) -> arrayOf<Any>(row.entry, row.previewArgs) }
    }

    internal data class PreviewRow(val entry: RenderPreviewEntry, val previewArgs: List<Any?>)

    internal fun expandParameterProvider(entry: RenderPreviewEntry): List<PreviewRow> {
        val providerFqn = entry.params.previewParameterProviderClassName
            ?: return listOf(PreviewRow(entry, emptyList()))
        val limit = entry.params.previewParameterLimit.coerceAtLeast(0)
        if (limit == 0) return emptyList()
        val values = loadProviderValues(providerFqn, limit)
        if (values.isEmpty()) {
            System.err.println(
                "@PreviewParameter(provider = $providerFqn) on '${entry.id}' produced no values — skipping.",
            )
            return emptyList()
        }
        val suffixes = PreviewParameterLabels.suffixesFor(values)
        val rows = values.mapIndexed { idx, value ->
            val paramSuffix = suffixes[idx]
            val newCaptures = entry.captures.map { c ->
                c.copy(renderOutput = insertBeforeExtension(c.renderOutput, paramSuffix))
            }
            val newId = entry.id + paramSuffix
            PreviewRow(entry.copy(id = newId, captures = newCaptures), listOf(value))
        }
        // The renderer is authoritative about which fan-out files will exist
        // for this preview — delete any `<stem>_*<ext>` files from prior runs
        // that aren't in this run's expected output. Guards against provider
        // renames ("loading" → "busy") and the `_PARAM_<idx>` → `_<label>`
        // migration leaving a mix of old-shape and new-shape PNGs on disk.
        // Runs at shard-load time so it fires once per parameterized preview,
        // before any test body writes to the directory.
        deleteStaleFanoutFiles(entry.captures, rows)
        return rows
    }

    private fun deleteStaleFanoutFiles(
        templateCaptures: List<RenderPreviewCapture>,
        expanded: List<PreviewRow>,
    ) {
        val outDirPath = System.getProperty("composeai.render.outputDir") ?: return
        val outDir = File(outDirPath)
        if (!outDir.isDirectory) return
        val expectedNames = expanded.flatMap { it.entry.captures }
            .mapNotNull { fanoutLeaf(it.renderOutput) }
            .toSet()
        for (template in templateCaptures) {
            val templateFile = File(outDir, template.renderOutput.substringAfter("renders/"))
            val dir = templateFile.parentFile ?: continue
            val stem = templateFile.nameWithoutExtension
            val ext = ".${templateFile.extension}"
            val prefix = stem + "_"
            dir.listFiles()
                ?.filter { f ->
                    f.name.startsWith(prefix) &&
                        f.name.endsWith(ext) &&
                        f.name !in expectedNames
                }
                ?.forEach { f ->
                    if (!f.delete()) {
                        System.err.println("Failed to delete stale fan-out file: ${f.absolutePath}")
                    }
                }
        }
    }

    private fun fanoutLeaf(renderOutput: String): String? {
        if (renderOutput.isEmpty()) return null
        return renderOutput.substringAfterLast('/').ifEmpty { renderOutput }
    }

    /**
     * Inserts [suffix] before the extension of a `renders/<id>.<ext>` path.
     * `renders/foo.png` + `_PARAM_0` → `renders/foo_PARAM_0.png`. Leaves
     * paths without an extension untouched (appended at the end) so the
     * mapping stays a pure string transformation.
     */
    internal fun insertBeforeExtension(path: String, suffix: String): String {
        if (path.isEmpty()) return path
        val dot = path.lastIndexOf('.')
        val slash = path.lastIndexOf('/')
        return if (dot > slash) path.substring(0, dot) + suffix + path.substring(dot)
        else path + suffix
    }

    private fun loadProviderValues(providerFqn: String, limit: Int): List<Any?> {
        val clazz = try {
            Class.forName(providerFqn)
        } catch (e: ClassNotFoundException) {
            System.err.println("@PreviewParameter: provider class $providerFqn not found on test classpath — skipping.")
            return emptyList()
        }
        val instance = runCatching {
            val ctor = clazz.getDeclaredConstructor()
            ctor.isAccessible = true
            ctor.newInstance()
        }.getOrElse { e ->
            System.err.println(
                "@PreviewParameter: couldn't instantiate $providerFqn via nullary constructor: ${e.message}",
            )
            return emptyList()
        }
        // `PreviewParameterProvider<T>` exposes `values: Sequence<T>` as a Kotlin
        // property — its JVM signature is `getValues(): Sequence`. Look up the
        // method by name to avoid taking a compile-time dependency on the
        // provider interface (which lives in the consumer's Compose artifact).
        val getValues = runCatching { clazz.getMethod("getValues") }.getOrElse {
            System.err.println("@PreviewParameter: $providerFqn has no getValues() — not a PreviewParameterProvider?")
            return emptyList()
        }
        @Suppress("UNCHECKED_CAST")
        val sequence = getValues.invoke(instance) as? Sequence<Any?>
            ?: return emptyList()
        // `Sequence.take(Int).toList()` is the Kotlin stdlib contract —
        // drives the sequence lazily up to `limit` without requiring
        // reflective access into package-private iterator implementations
        // (`kotlin.jvm.internal.ArrayIterator`, which `Method.invoke`
        // rejects with IllegalAccessException from outside the stdlib
        // module).
        return sequence.take(limit).toList()
    }
}

/**
 * Rendering logic — driven by a single [RenderPreviewEntry]. Subclasses supply
 * the `@RunWith` + `@Parameters` wiring. [RobolectricRenderTest] is the default
 * single-class entry; the plugin generates `RobolectricRenderTest_ShardN` subclasses
 * when `composeAiPreview.shards > 1`.
 *
 * Uses `roborazzi-compose`'s `captureRoboImage { @Composable }` overload, which
 * registers `RoborazziActivity` with Robolectric's ShadowPackageManager and drives
 * the composition without requiring `createComposeRule()` or a consumer-side
 * ui-test-manifest. Per-preview width/height/fontScale/locale/uiMode/background
 * are applied through [RoborazziComposeOptions]; it re-applies the Robolectric
 * qualifiers around each capture so different previews render at different sizes.
 *
 * The content itself is produced by a [PreviewRenderStrategy] keyed off
 * [RenderPreviewParams.kind] — @Composable previews use the reflective Compose
 * strategy, tile previews route through [TilePreviewComposable].
 *
 * Annotations that would normally live on this class (`@Config`,
 * `@GraphicsMode`) are DELIBERATELY absent. They've moved to the generated
 * `ee/schimke/composeai/renderer/robolectric.properties` file on the test
 * classpath, which Robolectric merges into the effective config for every
 * test in this package. The motivation is issue #142: JUnit's
 * `AnnotationParser.parseClassValue` eagerly resolves `@Config.application()`
 * default (`android.app.Application`) during test-class discovery, and on
 * some JVMs (JDK 25 on certain Linux distros) that resolution fails with
 * `ClassNotFoundException` because the test worker forks on a JVM where
 * `android.jar` isn't on the bootstrap classpath. Removing `@Config` from
 * the bytecode removes that parse path entirely.
 *
 * The properties file pins:
 *   - `sdk=35` — matches the renderer's SDK assumption (was `@Config(sdk=[35])`)
 *   - `graphicsMode=NATIVE` — HardwareRenderer path for Compose capture
 *   - `application=android.app.Application` (default) — skips the consumer's
 *     custom `Application.onCreate()` so preview rendering sidesteps
 *     platform-specific init (BridgingManager on non-Wear sandboxes,
 *     Firebase, Play Services, WorkManager) that routinely fails inside
 *     Robolectric. Consumers can set `composePreview.useConsumerApplication = true`
 *     to restore the manifest-declared Application.
 *   - `shadows=…ShadowFontsContractCompat` — GoogleFont shadow is always on.
 */
abstract class RobolectricRenderTestBase(
    private val preview: RenderPreviewEntry,
    /**
     * Values supplied to the preview composable — non-empty only when the
     * preview's `@PreviewParameter` fan-out produced a row. The test-runner
     * row carries `(entry, args)` so values never round-trip through JSON;
     * the loader enumerates the provider on the test JVM and passes the raw
     * objects straight through.
     */
    private val previewArgs: List<Any?>,
) {

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun renderPreview() {
        val outputDir = File(System.getProperty("composeai.render.outputDir") ?: "build/compose-previews/renders")
        outputDir.mkdirs()

        val params = preview.params
        // AS-parity sizing: an axis wraps to intrinsic content when the user
        // didn't specify it (and didn't pick a device/showSystemUi frame —
        // discovery has already pre-resolved those cases). We use a generous
        // sandbox dp for wrapped axes so the Robolectric window / Configuration
        // has a finite, coherent size; the captured PNG is cropped back down
        // to the measured content bounds after capture.
        val wrapWidth = params.widthDp == null || params.widthDp <= 0
        val wrapHeight = params.heightDp == null || params.heightDp <= 0
        val widthDp = params.widthDp?.takeIf { it > 0 } ?: SANDBOX_WIDTH_DP
        val heightDp = params.heightDp?.takeIf { it > 0 } ?: SANDBOX_HEIGHT_DP
        // Round crop fires when the preview is on a round device AND it's the
        // kind of surface that fills the watch — either a @Composable the user
        // asked for system UI on, or a tile (tiles always fill the watchface,
        // so `showSystemUi` is never set for them). Without the tile branch,
        // tile previews render as rectangles even on wearos_*_round devices.
        val isRound = isRoundDevice(params.device) &&
            (params.showSystemUi || params.kind == PreviewKind.TILE)

        val composeOptions = RoborazziComposeOptions.Builder().apply {
            size(widthDp, heightDp)
            if (isRound) addOption(RoundScreenOption)
            if (params.fontScale != 1.0f) fontScale(params.fontScale)
            if (params.uiMode != 0) uiMode(params.uiMode)
            params.locale?.let { locale(it) }
            background(params.showBackground, params.backgroundColor)
            inspectionMode(true)
        }.build()

        val roborazziOptions = RoborazziOptions(
            recordOptions = RoborazziOptions.RecordOptions(applyDeviceCrop = isRound),
        )

        renderDefault(
            params = params,
            widthDp = widthDp,
            heightDp = heightDp,
            wrapWidth = wrapWidth,
            wrapHeight = wrapHeight,
            outputDir = outputDir,
            roborazziOptions = roborazziOptions,
            composeOptions = composeOptions,
        )
    }

    /**
     * Resolve one capture's output file by stripping the module-relative
     * `renders/` prefix the manifest carries and re-rooting under the
     * configured output dir.
     */
    private fun outputFileFor(capture: RenderPreviewCapture, outputDir: File): File {
        val leafName = capture.renderOutput.substringAfterLast('/').ifEmpty { "${preview.id}.png" }
        return File(outputDir, leafName)
    }

    /**
     * Default render path — paused `mainClock`, pump by [CAPTURE_ADVANCE_MS], capture.
     *
     * Replaces the earlier `captureRoboImage { @Composable }` flow. The
     * composable overload drives the composition to idle before capturing,
     * which hangs on infinite animations (`CircularProgressIndicator()`,
     * `rememberInfiniteTransition`, hand-rolled `withFrameNanos` loops) and
     * was the root cause of the 12-minute / OOM runs that PR #14 papered
     * over. With `mainClock.autoAdvance = false` we never wait for idle —
     * each `advanceTimeByFrame()` deterministically dispatches one frame
     * cycle, and after by [CAPTURE_ADVANCE_MS] we just capture whatever the
     * composition has drawn.
     *
     * `ui-test-manifest` (injected into the consumer's `testImplementation`
     * by the plugin) supplies the `ComponentActivity` entry
     * `createAndroidComposeRule` needs; we still register the component
     * explicitly with `ShadowPackageManager` to satisfy Robolectric 4.13+'s
     * intent-resolution check (robolectric/robolectric#4736).
     *
     * Options (size, locale, uiMode, round, fontScale, background,
     * inspectionMode) are applied by hand here rather than through
     * [RoborazziComposeOptions], because the option chain wants an
     * [ActivityScenario] it owns and that's awkward to share with a
     * [ComposeTestRule]. size/locale/uiMode/round go through Robolectric
     * resource qualifiers; fontScale goes through
     * `RuntimeEnvironment.setFontScale` (matching Roborazzi's own
     * `RoborazziComposeFontScaleOption`) since fontScale is a Configuration
     * field, not a qualifier; background/inspection go through composition
     * locals.
     */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun renderDefault(
        params: RenderPreviewParams,
        widthDp: Int,
        heightDp: Int,
        wrapWidth: Boolean,
        wrapHeight: Boolean,
        outputDir: File,
        roborazziOptions: RoborazziOptions,
        composeOptions: RoborazziComposeOptions,
    ) {
        val appContext: android.app.Application =
            androidx.test.core.app.ApplicationProvider.getApplicationContext()
        org.robolectric.Shadows.shadowOf(appContext.packageManager)
            .addActivityIfNotPresent(
                android.content.ComponentName(appContext.packageName, ComponentActivity::class.java.name),
            )

        // Seed `Typeface.sSystemFontMap` with the Pixel-system-family aliases
        // that map onto public Google Fonts. Makes
        // `Font(DeviceFontFamilyName("roboto-flex"), weight = …)` — the
        // production shape consumers use when targeting Pixel's bundled
        // variable fonts — resolve to a cached downloadable TTF instead of
        // silently falling back to Roboto. Idempotent + process-level cached,
        // so the first preview pays the download cost once per session and
        // every subsequent preview hits the warm map. See
        // [PixelSystemFontAliases].
        PixelSystemFontAliases.seedSystemFontMap()

        applyPreviewQualifiers(
            widthDp = widthDp,
            heightDp = heightDp,
            isRound = isRoundDevice(params.device) &&
                (params.showSystemUi || params.kind == PreviewKind.TILE),
            locale = params.locale,
            uiMode = params.uiMode,
            density = params.density,
        )
        // fontScale isn't a Robolectric resource qualifier — it's a
        // Configuration field. `RuntimeEnvironment.setFontScale(Float)` is the
        // Robolectric API that updates Configuration before the activity
        // launches; Roborazzi's own `RoborazziComposeFontScaleOption` uses the
        // same entrypoint. Applied unconditionally (default 1f) so it resets
        // between previews sharing the same Robolectric sandbox.
        org.robolectric.RuntimeEnvironment.setFontScale(params.fontScale)

        // ATF opt-in. When enabled:
        //   - `LocalInspectionMode` flips to `false` so Compose populates the
        //     accessibility semantics tree (inspection mode suppresses it,
        //     leaving ATF with nothing to flag).
        //   - After capture we pull the `ViewRootForTest`-backed view off the
        //     still-attached SemanticsNode and hand it to [AccessibilityChecker].
        //     Capturing before ATF keeps the PNG output stable — findings only
        //     gate the *sidecar* artifacts.
        val a11yEnabled = System.getProperty("composeai.a11y.enabled") == "true"
        val annotate = a11yEnabled && System.getProperty("composeai.a11y.annotate") != "false"

        val rule = createAndroidComposeRule<ComponentActivity>()
        val description = org.junit.runner.Description.createTestDescription(
            this::class.java,
            "renderDefault_${preview.id}",
        )
        // Captures the content's intrinsic pixel size when either axis wraps.
        // Read after `captureRoboImage` to crop the PNG down to the composable's
        // actual bounds.
        var measured: IntSize? = null
        val statement = object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                rule.mainClock.autoAdvance = false
                // Paint the preview background on the host activity's window
                // rather than wrapping our setContent body in
                // `Box(Modifier.fillMaxSize().background(bg)) { … }`. The
                // compose-compiler emits `ComposeUiNode.setCompositeKeyHash`
                // calls for every layout node in the renderer bytecode; those
                // methods only exist on compose-ui 1.8+. Consumers pinned to
                // older Compose BOMs (e.g. WearTilesKotlin's 1.6.x) hit
                // `NoSuchMethodError` at render time. Painting the background
                // natively sidesteps the wrapper composable entirely — the
                // preview's own @Composable body still runs through its own
                // compose-compiler (the consumer's), so the consumer's
                // emitted bytecode naturally targets their runtime.
                val bg = resolveBackgroundColor(params).toArgb()
                rule.runOnUiThread {
                    rule.activity.window.decorView.setBackgroundColor(bg)
                }
                // Mirror Compose's system long-screenshot signal so composables
                // can suppress transient UI (e.g. Wear's `ScreenScaffold` scroll
                // indicator) by reading `LocalScrollCaptureInProgress.current`.
                //
                // Only set for `@ScrollingPreview(modes = [LONG])`: stitched
                // captures composite many frames into one tall PNG, and a
                // fading indicator at arbitrary opacity per slice dominates
                // the diff. END mode is a single frame at the natural
                // scroll-to-end position — the indicator there is what a real
                // app would show, so we leave it visible.
                //
                // `LocalScrollCaptureInProgress` shipped in compose-ui 1.7.
                // Looked up reflectively so the renderer compiles against an
                // older Compose floor and consumers on pre-1.7 Compose get a
                // null lookup (scroll-capture becomes a no-op — the natural
                // transient UI stays visible at stitched seams). See
                // [ScrollCaptureInProgressLocal].
                val scrollCaptureInProgress =
                    preview.captures.any { it.scroll?.mode == ScrollMode.LONG }
                val scrollCaptureProvidable =
                    if (scrollCaptureInProgress) ScrollCaptureInProgressLocal.get() else null
                // Wear-only: flatten `TransformingLazyColumn` item scaling for
                // `@ScrollingPreview(..., reduceMotion = true)` captures.
                // Without this, items mid-transform at a viewport edge get
                // captured at non-1.0 scale, then the stitcher paints that
                // same item again (at its next-slice scale) one viewport
                // down — producing the ghost/duplicate rows at slice seams
                // the user sees on long Wear previews. `LocalReduceMotion`
                // is looked up reflectively so this file stays free of a
                // Wear Compose compile dep; on non-Wear modules the lookup
                // returns null and the flag is a no-op.
                val reduceMotion =
                    preview.captures.any { it.scroll?.reduceMotion == true }
                val reduceMotionLocal =
                    if (reduceMotion) WearReduceMotionLocal.get() else null
                // @AnimatedPreview(showCurves = true): capture the slot table
                // by wrapping the composition in `InspectablePreviewContent`,
                // which seeds parameter information collection and snapshots
                // `currentComposer.compositionData` into the holder for
                // `AnimationInspector.attach(...)` to read post-settle. Held
                // nullable so non-curve previews don't pay the
                // collectParameterInformation cost.
                val animationCurveCapture: SlotTreeCapture? =
                    preview.captures.firstNotNullOfOrNull {
                        it.animation?.takeIf { a -> a.showCurves }
                    }?.let { SlotTreeCapture() }
                val providedValues = buildList {
                    add(LocalInspectionMode provides !a11yEnabled)
                    if (scrollCaptureProvidable != null) {
                        add(scrollCaptureProvidable provides scrollCaptureInProgress)
                    }
                    if (reduceMotionLocal != null) {
                        add(reduceMotionLocal provides true)
                    }
                }.toTypedArray()
                rule.setContent {
                    CompositionLocalProvider(values = providedValues) {
                        val previewBody: @Composable () -> Unit = {
                            if (wrapWidth || wrapHeight) {
                                MeasuredWrapBox(
                                    wrapWidth = wrapWidth,
                                    wrapHeight = wrapHeight,
                                    onMeasured = { measured = it },
                                ) {
                                    strategyFor(params.kind).Render(preview, widthDp, heightDp, previewArgs)
                                }
                            } else {
                                strategyFor(params.kind).Render(preview, widthDp, heightDp, previewArgs)
                            }
                        }
                        if (animationCurveCapture != null) {
                            InspectablePreviewContent(animationCurveCapture, previewBody)
                        } else {
                            previewBody()
                        }
                    }
                }
                // With `mainClock.autoAdvance = false` the clock stays at 0
                // until we step it, so capturing each frame at its intended
                // virtual time is a matter of advancing the delta.
                // `DiscoverPreviewsTask` guarantees `captures` is ordered by
                // ascending `advanceTimeMillis`, so we accumulate forward-only.
                var currentTime = 0L
                val onRoot = rule.onRoot()
                preview.captures.forEachIndexed { idx, capture ->
                    val target = capture.advanceTimeMillis ?: CAPTURE_ADVANCE_MS
                    require(target >= currentTime) {
                        "Preview ${preview.id}: capture advanceTimeMillis must be ascending " +
                            "(got $target after clock was at $currentTime)"
                    }
                    val delta = target - currentTime
                    if (delta > 0) {
                        rule.mainClock.advanceTimeBy(delta)
                        currentTime = target
                    }

                    // @ScrollingPreview(END): drive the first scrollable on the
                    // requested axis to the end of its content before a single
                    // capture.
                    // @ScrollingPreview(LONG): stitched capture — one slice per
                    // viewport-height of scroll, then Java2D-stitched into one
                    // tall PNG with an optional Wear pill clip. See
                    // [handleLongCapture].
                    val outputFile = outputFileFor(capture, outputDir)
                    outputFile.parentFile?.mkdirs()

                    val scroll = capture.scroll
                    val longHandled = scroll != null &&
                        scroll.mode == ScrollMode.LONG &&
                        scroll.axis == ScrollAxis.VERTICAL &&
                        handleLongCapture(
                            rule = rule,
                            scroll = scroll,
                            previewId = preview.id,
                            heightDp = heightDp,
                            isRound = isRoundDevice(params.device) &&
                                (params.showSystemUi || params.kind == PreviewKind.TILE),
                            outputFile = outputFile,
                        )
                    // @ScrollingPreview(GIF): drive the scroller by small
                    // steps and encode the sequence as an animated GIF.
                    // Same multi-frame shape as LONG, but encodes into a
                    // GIF container instead of stitching one tall PNG.
                    val gifHandled = !longHandled &&
                        scroll != null &&
                        scroll.mode == ScrollMode.GIF &&
                        scroll.axis == ScrollAxis.VERTICAL &&
                        handleGifCapture(
                            rule = rule,
                            scroll = scroll,
                            previewId = preview.id,
                            heightDp = heightDp,
                            isRound = isRoundDevice(params.device) &&
                                (params.showSystemUi || params.kind == PreviewKind.TILE),
                            outputFile = outputFile,
                        )

                    // @AnimatedPreview: paused mainClock, advance per frame
                    // across the annotation's window, capture each frame,
                    // encode as GIF. When `showCurves = true`, the outer
                    // setContent has already wrapped the composition in
                    // Inspectable(animationCurveRecord, …) so we can attach
                    // `AnimationInspector` to sample property values across
                    // the same time window.
                    val animationHandled = !longHandled && !gifHandled &&
                        capture.animation != null &&
                        handleAnimatedCapture(
                            rule = rule,
                            animation = capture.animation,
                            previewId = preview.id,
                            isRound = isRoundDevice(params.device) &&
                                (params.showSystemUi || params.kind == PreviewKind.TILE),
                            outputFile = outputFile,
                            curveCapture = animationCurveCapture,
                        ).also { handled ->
                            // The clock has been driven well past `currentTime`
                            // by the animation pass — keep our local marker in
                            // sync so any subsequent capture in the same
                            // composition asserts ascending time correctly.
                            if (handled) currentTime += capture.animation.durationMs.toLong()
                        }

                    if (!longHandled && !gifHandled && !animationHandled) {
                        // TOP mode is the unscrolled initial frame — no
                        // drive, just a capture. END mode drives the first
                        // scrollable on the requested axis to its content
                        // end before capturing.
                        if (scroll != null && scroll.mode == ScrollMode.END) {
                            val result = driveScrollToEnd(
                                rule = rule,
                                axis = scroll.axis,
                                maxScrollPx = scroll.maxScrollPx,
                            )
                            if (result is ScrollDriveResult.NoScrollable) {
                                System.err.println(
                                    "@ScrollingPreview on '${preview.id}' but no scrollable " +
                                        "composable found on axis ${scroll.axis} — capturing initial frame.",
                                )
                            }
                        }
                        onRoot.captureRoboImage(
                            file = outputFile,
                            roborazziOptions = roborazziOptions,
                        )
                    }

                    // AS-parity: crop the PNG down to the composable's
                    // intrinsic size on wrapped axes. Skipped for stitched
                    // LONG output, scroll GIF output, and @AnimatedPreview
                    // GIF output — those files' dimensions are the full
                    // scrollable extent / frame size, not the composable's
                    // intrinsic box.
                    if (!longHandled && !gifHandled && !animationHandled &&
                        (wrapWidth || wrapHeight) && measured != null) {
                        cropPngTopLeft(
                            file = outputFile,
                            wrapWidth = wrapWidth,
                            wrapHeight = wrapHeight,
                            measured = measured!!,
                        )
                    }

                    if (a11yEnabled && idx == a11yCaptureIndex()) {
                        // `fetchSemanticsNode().root as ViewRootForTest` is the
                        // exact view roborazzi-accessibility-check's
                        // `checkRoboAccessibility` walks — it's the only view
                        // under Robolectric where the compose a11y delegate
                        // produces populated AccessibilityNodeInfo. DecorView
                        // here returns all NOT_RUN.
                        val view = (onRoot.fetchSemanticsNode().root as ViewRootForTest).view
                        // analyze() runs ATF and walks the hierarchy in one
                        // pass — the overlay needs both findings (red badge
                        // layer) and ANI nodes (the colour-swatched legend).
                        val result = AccessibilityChecker.analyze(preview.id, view)
                        val a11yDir = File(
                            System.getProperty("composeai.a11y.outputDir")
                                ?: "build/compose-previews/accessibility-per-preview",
                        )
                        AccessibilityChecker.writePerPreviewReport(
                            outputDir = a11yDir,
                            previewId = preview.id,
                            findings = result.findings,
                            nodes = result.nodes,
                            screenshot = outputFile.takeIf { annotate },
                        )
                    }
                }
            }
        }
        rule.apply(statement, description).evaluate()
    }

    /**
     * ATF runs on a single frame per preview — for animated previews, prefer
     * the SECOND frame (index 1) when available: frame 0 is often the t=0
     * pre-settle state (fade-ins still transparent, AnimatedVisibility not on
     * screen yet) which isn't a representative moment for accessibility.
     * Static / single-capture previews fall through to index 0.
     */
    private fun a11yCaptureIndex(): Int =
        if (preview.captures.size > 1) 1 else 0

    /**
     * Match how Roborazzi's `RoborazziComposeSizeOption` / `LocaleOption` /
     * `UiModeOption` express themselves as Robolectric qualifiers — applied
     * before the ComposeTestRule's ActivityScenario launches so the
     * Configuration the activity picks up has our intended dimensions / locale
     * / night bits.
     *
     * Order matters: Robolectric's parser (and Android's underlying grammar —
     * https://developer.android.com/guide/topics/resources/providing-resources#QualifierRules)
     * is strict about the sequence. Locale comes before width/height, which
     * come before orientation, which comes before night-mode, which comes
     * before density. Out-of-order qualifiers produce
     * `IllegalArgumentException: failed to parse qualifiers` at runtime.
     */
    private fun applyPreviewQualifiers(
        widthDp: Int,
        heightDp: Int,
        isRound: Boolean,
        locale: String?,
        uiMode: Int,
        density: Float?,
    ) {
        val qualifiers = buildList {
            if (!locale.isNullOrBlank()) add(locale)
            if (widthDp > 0) add("w${widthDp}dp")
            if (heightDp > 0) add("h${heightDp}dp")
            if (isRound) add("round")
            if (widthDp > 0 && heightDp > 0) {
                add(if (widthDp > heightDp) "land" else "port")
            }
            if (uiMode != 0) {
                when (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) {
                    android.content.res.Configuration.UI_MODE_NIGHT_YES -> add("night")
                    android.content.res.Configuration.UI_MODE_NIGHT_NO -> add("notnight")
                }
            }
            // `<n>dpi` — same shape sergio-sastre/ComposablePreviewScanner's
            // RobolectricDeviceQualifierBuilder emits, so output dimensions
            // match what Studio (and a Roborazzi/scanner-support setup) renders
            // for the same `@Preview`. Without this Robolectric defaults to
            // `mdpi` (1.0x) and bitmaps come out smaller than Studio's preview.
            if (density != null && density > 0f) {
                add("${(density * 160).toInt()}dpi")
            }
        }
        if (qualifiers.isNotEmpty()) {
            org.robolectric.RuntimeEnvironment.setQualifiers("+${qualifiers.joinToString("-")}")
        }
    }

    /**
     * AS-parity wrap-to-content: measure the strategy's composable with
     * unbounded constraints on wrapped axes, capture the child's pixel
     * size via [onMeasured], then size the outer Box to match. Doing this
     * in a single layout pass (rather than via onGloballyPositioned) keeps
     * the measurement deterministic even when Compose's post-layout
     * scheduling is short-circuited by Robolectric. Measures with bounded
     * sandbox constraints (not Infinity): that's what Android Studio's
     * preview pane does, and it's the only shape that `Modifier.fillMax*`
     * / `LazyColumn` accept without throwing from `InlineClassHelper`. Min
     * constraint on wrapped axes is relaxed to 0 so small composables can
     * shrink below the sandbox; `fillMaxWidth` composables still measure
     * at the sandbox width and no width-crop happens on that axis.
     */
    @Composable
    private fun MeasuredWrapBox(
        wrapWidth: Boolean,
        wrapHeight: Boolean,
        onMeasured: (IntSize) -> Unit,
        content: @Composable () -> Unit,
    ) {
        Box(
            modifier = Modifier.layout { measurable, constraints ->
                val wrappedConstraints = Constraints(
                    minWidth = if (wrapWidth) 0 else constraints.minWidth,
                    maxWidth = constraints.maxWidth,
                    minHeight = if (wrapHeight) 0 else constraints.minHeight,
                    maxHeight = constraints.maxHeight,
                )
                val placeable = measurable.measure(wrappedConstraints)
                onMeasured(IntSize(placeable.width, placeable.height))
                layout(placeable.width, placeable.height) {
                    placeable.place(0, 0)
                }
            },
        ) {
            content()
        }
    }

    private fun resolveBackgroundColor(params: RenderPreviewParams): androidx.compose.ui.graphics.Color = when {
        params.backgroundColor != 0L -> androidx.compose.ui.graphics.Color(params.backgroundColor.toInt())
        params.showBackground -> androidx.compose.ui.graphics.Color.White
        else -> androidx.compose.ui.graphics.Color.Transparent
    }

    companion object {
        /**
         * Sandbox dp used for wrapped axes. Matches the historical phone-shaped
         * 400×800 dp default that stood in for "no device" before AS-parity
         * sizing — `fillMax*` composables get a reasonable viewport instead of
         * a giant square. Mirrors `DeviceDimensions.SANDBOX_WIDTH/HEIGHT_DP`
         * on the plugin side.
         */
        private const val SANDBOX_WIDTH_DP = 400
        private const val SANDBOX_HEIGHT_DP = 800

        /**
         * Virtual time to advance before capture in the paused-`mainClock`
         * path, in milliseconds. Small on purpose: "settled" is
         * `autoAdvance = false` + however far we step. 32ms (≈ 2 Choreographer
         * frames) is enough for static previews (initial composition + one
         * settle pass for `LaunchedEffect`s); for infinite animations it
         * defines the deterministic snapshot point.
         *
         * Expressed in ms rather than frame count to line up with Roborazzi's
         * `@RoboComposePreviewOptions` / `ManualClockOptions.advanceTimeMillis`
         * convention — per-preview overrides would plug straight into
         * `mainClock.advanceTimeBy(...)` with no translation.
         */
        private const val CAPTURE_ADVANCE_MS = 32L
    }
}

/**
 * Crops [file] in-place to the top-left region defined by [measured] on the
 * wrapped axes. The non-wrapped axis keeps its original pixel extent — we
 * never expand beyond the captured PNG, so a wrapped-axis crop that somehow
 * exceeds the sandbox is clamped.
 *
 * Uses `javax.imageio` rather than Android's `Bitmap` so the path doesn't need
 * a Robolectric shadow: this runs on the JVM side after `captureRoboImage`
 * has already written a standard PNG.
 */
private fun cropPngTopLeft(
    file: File,
    wrapWidth: Boolean,
    wrapHeight: Boolean,
    measured: IntSize,
) {
    if (!file.exists()) return
    val original = javax.imageio.ImageIO.read(file) ?: return
    val cropW = (if (wrapWidth) measured.width else original.width).coerceIn(1, original.width)
    val cropH = (if (wrapHeight) measured.height else original.height).coerceIn(1, original.height)
    if (cropW == original.width && cropH == original.height) return
    val cropped = original.getSubimage(0, 0, cropW, cropH)
    javax.imageio.ImageIO.write(cropped, "PNG", file)
}

/**
 * Handles `@ScrollingPreview(modes = [LONG])` captures. Drives the first
 * scrollable on [ScrollCapture.axis] by one viewport-height per step via
 * [driveScrollByViewport], captures each slice to a temp PNG with per-slice
 * round crop DISABLED, and Java2D-stitches them via [stitchSlices].
 *
 * For round Wear devices ([isRound] = true), the stitched output gets a
 * `capsule` clip — half-circle at the very top, rectangular middle,
 * half-circle at the very bottom ([applyWearPillClip]) — so the captured
 * scroll preserves the round screen edge at the first and last frames.
 *
 * Returns `true` when [outputFile] was written; `false` to let the caller
 * fall through to END-style single capture (e.g. when no scrollable matched).
 */
@OptIn(ExperimentalRoborazziApi::class)
private fun handleLongCapture(
    rule: AndroidComposeTestRule<*, ComponentActivity>,
    scroll: ScrollCapture,
    previewId: String,
    heightDp: Int,
    isRound: Boolean,
    outputFile: File,
): Boolean {
    val density = rule.activity.resources.displayMetrics.density
    val viewportLayoutPx = (heightDp * density).toInt().coerceAtLeast(1)
    val slicesDir = File(outputFile.parentFile, "${outputFile.nameWithoutExtension}_slices")
    slicesDir.deleteRecursively()
    slicesDir.mkdirs()

    // For stitched capture on round devices, suppress per-slice round crop —
    // otherwise every slice has a circle cut out of it and the stitched output
    // has scalloped scallops down the middle. We apply a capsule clip after
    // stitching instead.
    val sliceRoborazziOptions = RoborazziOptions(
        recordOptions = RoborazziOptions.RecordOptions(applyDeviceCrop = false),
    )

    val slices = mutableListOf<SliceCapture>()
    try {
        // Multi-mode annotations (e.g. END + LONG) run captures in enum
        // ordinal order against the same composition, so an earlier END
        // leaves the scrollable at content end. Reset to the top before
        // slicing — otherwise the first slice is the end state and
        // `driveScrollByViewport`'s first iteration bails with
        // remaining ≈ 0, yielding a single "stitched" slice. See #154.
        driveScrollToStart(rule, scroll.axis)

        // Drive at 80% of the viewport so each consecutive slice pair has a
        // ~20% physical overlap for the content-aware stitcher to lock onto.
        // The stitcher uses scrolledPx only as a hint — the actual vertical
        // placement is decided by pixel matching.
        val result = driveScrollByViewport(
            rule = rule,
            axis = scroll.axis,
            stepPx = viewportLayoutPx * 0.8f,
            maxScrollPx = scroll.maxScrollPx,
        ) { scrolledPx ->
            val sliceFile = File(slicesDir, "slice_${slices.size}.png")
            rule.onRoot().captureRoboImage(file = sliceFile, roborazziOptions = sliceRoborazziOptions)
            slices += SliceCapture(scrolledPx, sliceFile)
        }
        if (result is ScrollDriveResult.NoScrollable) {
            System.err.println(
                "@ScrollingPreview(LONG) on '$previewId': no scrollable composable — falling through.",
            )
            return false
        }
        if (slices.isEmpty()) return false

        // Settle post-scroll animations (Wear `EdgeButton` reveal, spring
        // snaps, AnimatedVisibility fade-ins that only start once the list
        // has landed) before capturing the final frame. The per-step 250ms
        // advance inside `driveScrollByViewport` is tuned for scroll
        // settling, not for animations that START when the scroll reaches
        // its end.
        //
        // Tick one frame at a time so any withFrameNanos-driven animation
        // gets each cycle it's waiting on. Bounded (POST_SCROLL_SETTLE_MS
        // / 16ms frames) so infinite animations can't run away — they keep
        // the paused-clock semantics of the rest of the render path.
        settlePostScrollAnimations(rule)

        // Capture the settled end-state viewport as a stand-alone frame
        // (not overwriting the last in-scroll slice). `stitchSlicesWithFinalFrame`
        // then composes the during-scroll history above this full settled
        // viewport and cuts the seam cleanly using the same weighted-SAD
        // row matcher, so the complete revealed tail (EdgeButton, bottom
        // bar, FAB — whatever animates in at scroll-end) is always
        // present. Generic over layout: not Wear-specific.
        val finalFrameFile = File(slicesDir, "final_frame.png")
        rule.onRoot().captureRoboImage(file = finalFrameFile, roborazziOptions = sliceRoborazziOptions)

        stitchSlicesWithFinalFrame(
            slices = slices,
            finalFrameFile = finalFrameFile,
            viewportLayoutPx = viewportLayoutPx,
            outputFile = outputFile,
            isRound = isRound,
        ) ?: return false
        if (isRound) applyWearPillClip(outputFile)
        System.err.println(
            "@ScrollingPreview(LONG) on '$previewId': stitched ${slices.size} slices + settled final frame.",
        )
        return true
    } finally {
        slicesDir.deleteRecursively()
    }
}

/**
 * Advance the paused `mainClock` one frame at a time up to
 * [POST_SCROLL_SETTLE_MS], letting animations that begin once the scroll
 * lands (e.g. Wear `ScreenScaffold`'s `EdgeButton` reveal) run to their
 * resting state before the final slice is captured.
 *
 * Bounded so infinite animations (`rememberInfiniteTransition`, etc.)
 * don't turn the settle step into an open-ended render. 1000ms is ~4×
 * Wear Material3's 250ms EdgeButton reveal spec — enough headroom for
 * chained animations or a spring overshoot, still cheap per preview.
 */
private fun settlePostScrollAnimations(rule: AndroidComposeTestRule<*, *>) {
    val frameMs = 16L
    val frames = POST_SCROLL_SETTLE_MS / frameMs
    repeat(frames.toInt()) {
        rule.mainClock.advanceTimeByFrame()
    }
}

/**
 * Total virtual-time budget for [settlePostScrollAnimations]. Sized for
 * Wear Material3's `EdgeButton` expand spec (~250ms at the time of
 * writing) with comfortable headroom for overshoot / chained animations.
 */
private const val POST_SCROLL_SETTLE_MS = 1000L

/**
 * Handles `@ScrollingPreview(modes = [GIF])` captures with a "realistic
 * user" scroll shape: 1 s hold at the top, a slow finger-drag ramp, one
 * or more fling bursts sized for the content, then a 1 s hold on the
 * settled final frame. See [buildGifScrollScript] for the plan shape.
 *
 * Every frame is a full viewport-sized image — the GIF shows the scroll
 * as an animation rather than stitching frames into one tall still.
 * Per-frame round crop stays ON (each GIF frame should show a proper
 * watch-shaped viewport), so we reuse the default `RoborazziOptions`
 * the caller wired for single-frame captures.
 *
 * Returns `true` when [outputFile] was written; `false` to fall through
 * to the default single-capture path (e.g. when no scrollable matched, or
 * when the encoder declines).
 */
@OptIn(ExperimentalRoborazziApi::class)
private fun handleGifCapture(
    rule: AndroidComposeTestRule<*, ComponentActivity>,
    scroll: ScrollCapture,
    previewId: String,
    heightDp: Int,
    isRound: Boolean,
    outputFile: File,
): Boolean {
    val density = rule.activity.resources.displayMetrics.density
    val viewportLayoutPx = (heightDp * density).toInt().coerceAtLeast(1)
    val framesDir = File(outputFile.parentFile, "${outputFile.nameWithoutExtension}_gif_frames")
    framesDir.deleteRecursively()
    framesDir.mkdirs()

    // Per-frame crop matches END mode: each GIF frame should look like a
    // normal single capture, circle-clipped on round devices included.
    val frameRoborazziOptions = RoborazziOptions(
        recordOptions = RoborazziOptions.RecordOptions(applyDeviceCrop = isRound),
    )

    val frameIntervalMs = if (scroll.frameIntervalMs > 0) scroll.frameIntervalMs
                          else ScrollGifEncoder.DEFAULT_FRAME_DELAY_MS
    val frameFiles = mutableListOf<File>()
    val frameDelays = mutableListOf<Int>()

    fun captureFrame(delayMs: Int) {
        val frameFile = File(framesDir, "frame_${frameFiles.size}.png")
        rule.onRoot().captureRoboImage(file = frameFile, roborazziOptions = frameRoborazziOptions)
        frameFiles += frameFile
        frameDelays += delayMs
    }

    try {
        // Multi-mode annotations (`modes = [..., GIF]`) run captures in
        // enum ordinal order against the same composition, so an earlier
        // END / LONG leaves the scrollable at content end and the GIF
        // would animate "from end to end" — a single frame indistinguishable
        // from the END capture. Reset to the top before the frame walk
        // starts. Fix for #154.
        val resetResult = driveScrollToStart(rule, scroll.axis)
        if (resetResult is ScrollDriveResult.NoScrollable) {
            System.err.println(
                "@ScrollingPreview(GIF) on '$previewId': no scrollable composable — falling through.",
            )
            return false
        }

        // Hold-start: the viewer needs a beat to read the top of the
        // screen before motion begins. 1 s dwell.
        captureFrame(HOLD_START_MS)

        // Upfront extent hint — capped to `maxScrollPx` when the
        // annotation sets one. Runtime clamps each step to live remaining
        // anyway, so LazyList's progressive maxValue doesn't over-scroll.
        val liveRemaining = remainingScrollPx(rule, scroll.axis)
        val cap = if (scroll.maxScrollPx > 0) scroll.maxScrollPx.toFloat() else Float.POSITIVE_INFINITY
        val extentHint = minOf(liveRemaining, cap)

        val script = buildGifScrollScript(
            contentExtentPxHint = extentHint,
            viewportPx = viewportLayoutPx.toFloat(),
            density = density,
            frameIntervalMs = frameIntervalMs,
        )

        var scrolledPx = 0f
        var scriptHitEnd = false
        for (step in script) {
            if (step.scrollPx > 0f) {
                val headroom = (cap - scrolledPx).coerceAtLeast(0f)
                val target = minOf(step.scrollPx, headroom)
                if (target <= 0f) {
                    scriptHitEnd = true
                    break
                }
                val actual = driveScrollBy(rule, scroll.axis, target)
                if (actual <= 0f) {
                    scriptHitEnd = true
                    break
                }
                scrolledPx += actual
            } else {
                // Inter-fling dwell: no scroll, just a pause frame. Advance
                // virtual time a little so animations mid-composition keep
                // ticking honestly across the hold.
                rule.mainClock.advanceTimeBy(frameIntervalMs.toLong())
            }
            captureFrame(step.delayMs)
        }

        // Tail flings: LazyList reports `maxValue` progressively, so the
        // upfront `extentHint` can under-cover — the script finishes with
        // the scroll still mid-content. Keep emitting fling bursts against
        // the *live* remaining until the scrollable is exhausted (or the
        // `cap` / safety cap kicks in). Without this the final frame of
        // the GIF can land in the middle of the gradient on tall lists.
        if (!scriptHitEnd) {
            emitTailFlings(
                rule = rule,
                axis = scroll.axis,
                density = density,
                viewportPx = viewportLayoutPx.toFloat(),
                frameIntervalMs = frameIntervalMs,
                cap = cap,
                alreadyScrolledPx = scrolledPx,
                captureFrame = ::captureFrame,
            )
        }

        // Settle + hold-end: let animations that start when the scroll
        // lands (Wear `EdgeButton` reveal, spring overshoot) reach their
        // resting state, then capture one final frame with a long dwell.
        settlePostScrollAnimations(rule)
        captureFrame(HOLD_END_MS)

        if (frameFiles.isEmpty()) return false

        val frames = frameFiles.map {
            javax.imageio.ImageIO.read(it) ?: error("Failed to read GIF frame PNG: $it")
        }
        val written = ScrollGifEncoder.encode(
            frames = frames,
            outputFile = outputFile,
            frameDelaysMs = frameDelays.toIntArray(),
        ) ?: return false
        System.err.println(
            "@ScrollingPreview(GIF) on '$previewId': encoded ${frames.size} frames → ${written.name}.",
        )
        return true
    } finally {
        framesDir.deleteRecursively()
    }
}

/**
 * Drives the remaining scroll to content end in fling-shaped bursts,
 * capturing one frame per step. Used after the scripted plan runs out
 * of steps on content taller than the initial extent hint — the common
 * LazyList progressive-materialisation case where the first
 * [remainingScrollPx] read under-reports the true scroll extent.
 *
 * Each iteration emits one fling (geometric decay from peak to min
 * step, capped at [FLING_MAX_DISTANCE_VIEWPORTS] of a viewport) preceded
 * by a short inter-fling hold so the continuation reads as "user
 * swiped again" rather than one endless glide. Bounded by
 * [MAX_TAIL_FLINGS] so a runaway `maxValue` (infinite LazyList with a
 * no-op `ScrollBy`) can't spin forever.
 */
@Suppress("LongParameterList")
private fun emitTailFlings(
    rule: AndroidComposeTestRule<*, *>,
    axis: ScrollAxis,
    density: Float,
    viewportPx: Float,
    frameIntervalMs: Int,
    cap: Float,
    alreadyScrolledPx: Float,
    captureFrame: (delayMs: Int) -> Unit,
) {
    val flingPeakPx = FLING_PEAK_DP_PER_FRAME * density
    val flingMinPx = FLING_MIN_STEP_DP * density
    val flingCapPx = FLING_MAX_DISTANCE_VIEWPORTS * viewportPx
    var scrolledPx = alreadyScrolledPx

    repeat(MAX_TAIL_FLINGS) {
        val remaining = remainingScrollPx(rule, axis)
        if (remaining <= TAIL_FLING_EPSILON_PX) return
        val headroom = (cap - scrolledPx).coerceAtLeast(0f)
        if (headroom <= TAIL_FLING_EPSILON_PX) return

        // Inter-fling hold frame: short dwell + no scroll. Makes the
        // follow-up read as a distinct swipe.
        rule.mainClock.advanceTimeBy(frameIntervalMs.toLong())
        captureFrame(INTER_FLING_HOLD_MS)

        var step = flingPeakPx
        var distanceInFling = 0f
        while (step >= flingMinPx && distanceInFling < flingCapPx) {
            val live = remainingScrollPx(rule, axis)
            val remainingHeadroom = (cap - scrolledPx).coerceAtLeast(0f)
            val cappedRemaining = minOf(live, remainingHeadroom)
            if (cappedRemaining <= TAIL_FLING_EPSILON_PX) return
            val emit = minOf(step, cappedRemaining, flingCapPx - distanceInFling)
            if (emit <= 0f) return
            val actual = driveScrollBy(rule, axis, emit)
            if (actual <= 0f) return
            distanceInFling += actual
            scrolledPx += actual
            captureFrame(frameIntervalMs)
            step *= FLING_DECAY
        }
    }
}

// Safety cap on continuation flings so an infinite or pathological
// LazyList can't spin. Four flings × 1.5 viewports/fling covers six more
// viewports beyond the initial scripted walk — enough for any reasonable
// real-world preview.
private const val MAX_TAIL_FLINGS = 4

// Slightly looser than SETTLED_EPSILON_PX in ScrollDriver: LazyList's
// maxValue can wobble a pixel or two as items recompose, and we don't
// want a sub-pixel remainder to keep us spinning in the tail loop.
private const val TAIL_FLING_EPSILON_PX = 1f

/**
 * Handles `@AnimatedPreview` captures.
 *
 * Single-pass with an inline measure step:
 *
 *   1. Tick one frame to settle the composition — any
 *      `LaunchedEffect(Unit) { … }` that kicks an animation off must
 *      fire before the inspector attaches, otherwise AnimationSearch
 *      sees a target == initial transition and the curve is flat.
 *   2. When `showCurves = true`, attach an [AnimationInspector] over
 *      the slot table captured by [InspectablePreviewContent]. Read
 *      `inspector.maxDurationMs` to determine the actual animation
 *      duration; the user's `durationMs > 0` overrides this.
 *   3. Loop one frame per `frameIntervalMs` of virtual time across
 *      the effective duration, capturing the screenshot to a temp PNG
 *      and seeking the inspector to the same time to sample each
 *      animated property's value.
 *   4. Build the output GIF. With `showCurves = true`, every frame is
 *      a composite of (screenshot on top, curve panel below with a
 *      moving dot on each curve at the current virtual time). With
 *      `showCurves = false`, the GIF is screenshot-only.
 *
 * Returns `true` when [outputFile] was written.
 */
@OptIn(ExperimentalRoborazziApi::class)
private fun handleAnimatedCapture(
    rule: AndroidComposeTestRule<*, ComponentActivity>,
    animation: AnimationCapture,
    previewId: String,
    isRound: Boolean,
    outputFile: File,
    curveCapture: SlotTreeCapture?,
): Boolean {
    val framesDir = File(outputFile.parentFile, "${outputFile.nameWithoutExtension}_anim_frames")
    framesDir.deleteRecursively()
    framesDir.mkdirs()

    val frameRoborazziOptions = RoborazziOptions(
        recordOptions = RoborazziOptions.RecordOptions(applyDeviceCrop = isRound),
    )

    val frameIntervalMs = animation.frameIntervalMs.coerceAtLeast(10)

    val frameFiles = mutableListOf<File>()

    // Settle the composition by ticking one frame so any
    // LaunchedEffect(Unit) { … } has fired before the inspector reads
    // the slot table.
    rule.mainClock.advanceTimeByFrame()
    val inspector = if (animation.showCurves && curveCapture != null) {
        runCatching { AnimationInspector.attach(curveCapture) }
            .onFailure { e ->
                framesDir.deleteRecursively()
                throw e
            }.getOrNull()
    } else null

    // Effective duration: user override (>0) wins; otherwise ask the
    // inspector how long the discovered animations actually run, capped
    // at AUTO_DURATION_MAX_MS so an InfiniteTransition or a measure
    // glitch can't blow up GIF size, and floored at frameIntervalMs so
    // we always emit at least one tick.
    val measuredDurationMs = inspector?.maxDurationMs ?: -1L
    val effectiveDurationMs = when {
        animation.durationMs > 0 -> animation.durationMs.coerceAtMost(AUTO_DURATION_MAX_MS)
        measuredDurationMs > 0 -> {
            // Add a small tail beyond the animation's natural end so
            // the viewer sees the settled state for a beat before the
            // GIF loops. Cap at AUTO_DURATION_MAX_MS.
            (measuredDurationMs + AUTO_DURATION_TAIL_MS)
                .coerceAtMost(AUTO_DURATION_MAX_MS.toLong())
                .toInt()
        }
        else -> AUTO_DURATION_FALLBACK_MS
    }.coerceAtLeast(frameIntervalMs)
    val totalFrames = (effectiveDurationMs / frameIntervalMs).coerceAtLeast(1)

    val curveTracksByLabel = linkedMapOf<String, MutableList<Pair<Long, Any?>>>()
    val frameTimes = mutableListOf<Long>()

    fun captureFrame(virtualTimeMs: Long) {
        val frameFile = File(framesDir, "frame_${frameFiles.size}.png")
        rule.onRoot().captureRoboImage(file = frameFile, roborazziOptions = frameRoborazziOptions)
        frameFiles += frameFile
        frameTimes += virtualTimeMs

        if (inspector != null) {
            // Drive PreviewAnimationClock to the same virtual time the
            // outer mainClock is at — `setClockTime` seeks every tracked
            // transition to that point, so `getAnimatedProperties` reads
            // a fresh value rather than the cached value from when the
            // animation was first registered.
            inspector.setClockTime(virtualTimeMs)
            inspector.snapshot().forEach { tracked ->
                tracked.samples.forEach { sample ->
                    val key = "${tracked.label} · ${sample.label}"
                    curveTracksByLabel.getOrPut(key) { mutableListOf() }
                        .add(virtualTimeMs to sample.value)
                }
            }
        }
    }

    try {
        captureFrame(virtualTimeMs = 0L)
        var t = 0L
        repeat(totalFrames) {
            t += frameIntervalMs.toLong()
            rule.mainClock.advanceTimeBy(frameIntervalMs.toLong())
            captureFrame(virtualTimeMs = t)
        }

        if (frameFiles.isEmpty()) return false

        val rawFrames = frameFiles.map {
            javax.imageio.ImageIO.read(it) ?: error("Failed to read animation frame PNG: $it")
        }
        // Drop tracks that never visibly changed across the captured
        // window. AnimatedVisibility, AnimatedContent, and a few other
        // composables register internal book-keeping animations
        // (`Built-in InterruptionHandlingOffset` — slide-target on
        // mid-flight interruption; `Built-in shrink/expand` — geometry
        // change on a non-resizing reveal) that the inspector exposes
        // alongside the user-meaningful properties (alpha, etc.). For
        // a single state flip those internals stay flat at 0, so they
        // add an empty 80px row each to the curve panel without
        // information. Filtering by "values change" is more principled
        // than maintaining a denylist of internal labels — if a track
        // genuinely doesn't move, it's not interesting to plot.
        val tracks = curveTracksByLabel
            .map { (label, samples) ->
                AnimationCurvePlotter.Track(label = label, samples = samples)
            }
            .filter { it.hasVisibleVariation() }

        // Combined-GIF mode: each frame composes the screenshot above a
        // curve panel that highlights the current frame's position with
        // a moving dot on every track. Falls back to screenshot-only
        // when there are no tracks (e.g. showCurves = false, or the
        // inspector found nothing — which can happen on a static
        // preview accidentally annotated).
        val composedFrames: List<BufferedImage> =
            if (tracks.isNotEmpty()) {
                rawFrames.mapIndexed { i, screenshot ->
                    AnimationCurvePlotter.composeFrameWithCurves(
                        screenshot = screenshot,
                        tracks = tracks,
                        durationMs = effectiveDurationMs,
                        currentTimeMs = frameTimes[i],
                    )
                }
            } else {
                rawFrames
            }

        // Hold the first frame for [HOLD_START_MS] and the last for
        // [HOLD_END_MS] so the GIF reads as "pre-state → animation → settled
        // state" rather than instantly looping back. Single-frame GIFs
        // collapse to one long-hold image.
        val frameDelays = IntArray(composedFrames.size) { i ->
            when (i) {
                0 -> HOLD_START_ANIM_MS
                composedFrames.lastIndex -> HOLD_END_ANIM_MS
                else -> frameIntervalMs
            }
        }
        val written = ScrollGifEncoder.encode(
            frames = composedFrames,
            outputFile = outputFile,
            frameDelaysMs = frameDelays,
        ) ?: return false

        val durationLabel = if (animation.durationMs == 0) {
            "auto-detected ${effectiveDurationMs}ms (measured ${measuredDurationMs}ms)"
        } else {
            "${effectiveDurationMs}ms"
        }
        val curvesLabel = if (tracks.isNotEmpty()) " + ${tracks.size} curve track(s)" else ""
        System.err.println(
            "@AnimatedPreview on '$previewId': encoded ${composedFrames.size} frames " +
                "($durationLabel)$curvesLabel → ${written.name}.",
        )
        return true
    } finally {
        framesDir.deleteRecursively()
    }
}

/**
 * Hard cap on auto-detected animation duration. `InfiniteTransition`
 * and a few hand-rolled `withFrameNanos` loops report enormous
 * `maxDuration` values; we don't want one of them to spawn a 10-MB GIF.
 */
private const val AUTO_DURATION_MAX_MS = 5000

/** Floor used when `durationMs = 0` and no animations were discovered. */
private const val AUTO_DURATION_FALLBACK_MS = 1500

/**
 * Extra tail appended after the auto-detected animation end, so the
 * GIF holds the settled state visibly for a moment before looping.
 */
private const val AUTO_DURATION_TAIL_MS = 200L

/**
 * Per-frame `delayTime` overrides for the first and last frames of an
 * `@AnimatedPreview` GIF. Without them the GIF transitions straight from
 * settled-end back to pre-animation start without giving the viewer time
 * to read either state. Mirrors [HOLD_START_MS] / [HOLD_END_MS] in
 * `@ScrollingPreview(GIF)`'s scripted scroll cadence.
 */
private const val HOLD_START_ANIM_MS = 500
private const val HOLD_END_ANIM_MS = 1000

/**
 * Adds Robolectric's `+round` qualifier so `Configuration.isScreenRound` becomes
 * true before capture — that's what Roborazzi's `applyDeviceCrop` keys off to
 * produce a circular crop.
 */
@OptIn(ExperimentalRoborazziApi::class)
private object RoundScreenOption : RoborazziComposeSetupOption {
    override fun configure(configBuilder: RoborazziComposeSetupOption.ConfigBuilder) {
        configBuilder.addRobolectricQualifier("round")
    }
}

internal fun resolveWrapper(wrapperFqn: String): Pair<ComposableMethod, Any> {
    val cls = Class.forName(wrapperFqn)
    val instance = cls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
    // PreviewWrapperProvider.Wrap(content: @Composable () -> Unit) compiles to
    // Wrap(Function2, Composer, int). getDeclaredComposableMethod handles the
    // synthetic Composer/changed tail, so we look up by the content param's JVM type.
    val method = cls.getDeclaredComposableMethod("Wrap", Function2::class.java)
    return method to instance
}

/**
 * Default single-shard entry. Runs every preview in the manifest in one JVM,
 * reusing the sandbox across all parameter values. Generated shard subclasses
 * (see the plugin's `generateShardTests` task) replace this class when
 * `composeAiPreview.shards > 1`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class RobolectricRenderTest(
    preview: RenderPreviewEntry,
    @Suppress("UNCHECKED_CAST") previewArgs: List<Any?>,
) : RobolectricRenderTestBase(preview, previewArgs) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun previews(): List<Array<Any>> = PreviewManifestLoader.loadShard(0, 1)
    }
}
