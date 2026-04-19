package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
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
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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
        return manifest.previews
            .withIndex()
            .filter { (i, _) -> i % shardCount == shardIndex }
            .map { (_, p) -> arrayOf<Any>(p) }
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
 * `@Config.application` is deliberately unset here. The plugin writes a
 * package-level `ee/schimke/composeai/renderer/robolectric.properties` onto the
 * test classpath that Robolectric merges into the effective config. By default
 * that file pins `application=android.app.Application`, so the consumer's
 * custom `Application.onCreate()` does NOT run for preview rendering —
 * sidesteps platform-specific init (BridgingManager on non-Wear sandboxes,
 * Firebase, Play Services, WorkManager) that routinely fails inside
 * Robolectric. Consumers can set `composePreview.useConsumerApplication = true`
 * to restore the manifest-declared Application.
 */
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class RobolectricRenderTestBase(private val preview: RenderPreviewEntry) {

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
                        if (wrapWidth || wrapHeight) {
                            // AS-parity wrap-to-content: measure the strategy's
                            // composable with unbounded constraints on wrapped
                            // axes, capture the child's pixel size, then size
                            // the outer Box to match. Doing this in a single
                            // layout pass (rather than via onGloballyPositioned)
                            // keeps the measurement deterministic even when
                            // Compose's post-layout scheduling is short-circuited
                            // by Robolectric. The wrapping Box re-introduces the
                            // layout-node bytecode the compat workaround in the
                            // comment below avoids — acceptable because
                            // wrap-content is only emitted when the user didn't
                            // name a device / widthDp / heightDp, and the
                            // old-Compose-BOM consumers who need that workaround
                            // use device-pinned (e.g. wearos_*) previews.
                            // Measure with *bounded* sandbox constraints (not
                            // Infinity): that's what Android Studio's preview
                            // pane does, and it's the only shape that
                            // `Modifier.fillMax*` / `LazyColumn` accept without
                            // throwing from `InlineClassHelper`. Relax the min
                            // constraint on wrapped axes so small composables
                            // can shrink below the sandbox; a `fillMaxWidth`
                            // composable still measures at the sandbox width
                            // and no width-crop happens on that axis.
                            Box(
                                modifier = Modifier.layout { measurable, constraints ->
                                    val wrappedConstraints = Constraints(
                                        minWidth = if (wrapWidth) 0 else constraints.minWidth,
                                        maxWidth = constraints.maxWidth,
                                        minHeight = if (wrapHeight) 0 else constraints.minHeight,
                                        maxHeight = constraints.maxHeight,
                                    )
                                    val placeable = measurable.measure(wrappedConstraints)
                                    measured = IntSize(placeable.width, placeable.height)
                                    layout(placeable.width, placeable.height) {
                                        placeable.place(0, 0)
                                    }
                                },
                            ) {
                                strategyFor(params.kind).Render(preview, widthDp, heightDp)
                            }
                        } else {
                            strategyFor(params.kind).Render(preview, widthDp, heightDp)
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

                    if (!longHandled && !gifHandled) {
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
                    // LONG output and animated GIF output — those files'
                    // dimensions are the full scrollable extent / frame
                    // size, not the composable's intrinsic box.
                    if (!longHandled && !gifHandled && (wrapWidth || wrapHeight) && measured != null) {
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
                        val findings = AccessibilityChecker.check(preview.id, view)
                        val a11yDir = File(
                            System.getProperty("composeai.a11y.outputDir")
                                ?: "build/compose-previews/accessibility-per-preview",
                        )
                        AccessibilityChecker.writePerPreviewReport(
                            outputDir = a11yDir,
                            previewId = preview.id,
                            findings = findings,
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
        // has landed) before composing the final stitched frame. The
        // per-step 250ms advance inside `driveScrollByViewport` is tuned
        // for scroll settling, not for animations that START when the
        // scroll reaches its end — without this step the last slice
        // captures the EdgeButton mid-reveal and the stitched PNG shows a
        // thin pill at the bottom instead of the fully-expanded button.
        //
        // Tick one frame at a time so any withFrameNanos-driven animation
        // gets each cycle it's waiting on. Bounded (POST_SCROLL_SETTLE_MS
        // / 16ms frames) so infinite animations can't run away — they keep
        // the paused-clock semantics of the rest of the render path.
        settlePostScrollAnimations(rule)
        val lastSlice = slices.last()
        lastSlice.file.delete()
        rule.onRoot().captureRoboImage(file = lastSlice.file, roborazziOptions = sliceRoborazziOptions)

        stitchSlices(slices, viewportLayoutPx, outputFile) ?: return false
        if (isRound) applyWearPillClip(outputFile)
        System.err.println(
            "@ScrollingPreview(LONG) on '$previewId': stitched ${slices.size} slices.",
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
 * Handles `@ScrollingPreview(modes = [GIF])` captures. Drives the scroller
 * by a small fraction of the viewport per step via [driveScrollByViewport],
 * captures each step to a temp PNG, then encodes the sequence as an
 * animated GIF at [outputFile] via [ScrollGifEncoder].
 *
 * Unlike LONG, every frame is a full viewport-sized image — the GIF shows
 * the scroll as an animation rather than stitching frames into one tall
 * still. Per-frame round crop stays ON (each GIF frame should show a
 * proper watch-shaped viewport), so we reuse the default
 * `RoborazziOptions` the caller wired for single-frame captures.
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

    val frameFiles = mutableListOf<File>()
    try {
        // 20% of viewport per step → ~5 frames per viewport of scrolled
        // content. With 80ms default cadence that's ~0.4s of animation
        // per viewport — smooth scroll without an explosive frame count
        // on tall lists. Too small jitters, too large stutters.
        val result = driveScrollByViewport(
            rule = rule,
            axis = scroll.axis,
            stepPx = viewportLayoutPx * GIF_STEP_FRACTION,
            maxScrollPx = scroll.maxScrollPx,
        ) { _ ->
            val frameFile = File(framesDir, "frame_${frameFiles.size}.png")
            rule.onRoot().captureRoboImage(file = frameFile, roborazziOptions = frameRoborazziOptions)
            frameFiles += frameFile
        }
        if (result is ScrollDriveResult.NoScrollable) {
            System.err.println(
                "@ScrollingPreview(GIF) on '$previewId': no scrollable composable — falling through.",
            )
            return false
        }
        if (frameFiles.isEmpty()) return false

        // Mirror [handleLongCapture]'s post-scroll settle: re-capture the
        // final frame after advancing the clock so animations that begin
        // once the scroll lands (Wear `EdgeButton` reveal, spring overshoot)
        // show their resting state at the end of the GIF rather than a
        // caught-mid-reveal transient. Previous frames stay as captured —
        // they represent the scroll-in-progress state, which is what a
        // user would see while scrolling.
        settlePostScrollAnimations(rule)
        val lastFrameFile = frameFiles.last()
        lastFrameFile.delete()
        rule.onRoot().captureRoboImage(file = lastFrameFile, roborazziOptions = frameRoborazziOptions)

        val frames = frameFiles.map {
            javax.imageio.ImageIO.read(it) ?: error("Failed to read GIF frame PNG: $it")
        }
        val delay = if (scroll.frameIntervalMs > 0) scroll.frameIntervalMs
                    else ScrollGifEncoder.DEFAULT_FRAME_DELAY_MS
        val written = ScrollGifEncoder.encode(frames, outputFile, delay) ?: return false
        System.err.println(
            "@ScrollingPreview(GIF) on '$previewId': encoded ${frames.size} frames → ${written.name}.",
        )
        return true
    } finally {
        framesDir.deleteRecursively()
    }
}

// 20% of viewport per step balances smoothness against frame count.
private const val GIF_STEP_FRACTION = 0.2f

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
class RobolectricRenderTest(preview: RenderPreviewEntry) : RobolectricRenderTestBase(preview) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun previews(): List<Array<Any>> = PreviewManifestLoader.loadShard(0, 1)
    }
}
