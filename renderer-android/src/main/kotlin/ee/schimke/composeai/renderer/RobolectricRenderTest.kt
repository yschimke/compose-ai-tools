package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.reflect.ComposableMethod
import androidx.compose.runtime.reflect.getDeclaredComposableMethod
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.ViewRootForTest
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
 */
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class RobolectricRenderTestBase(private val preview: RenderPreviewEntry) {

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun renderPreview() {
        val outputDir = File(System.getProperty("composeai.render.outputDir") ?: "build/compose-previews/renders")
        val outputFile = File(outputDir, "${preview.id}.png")
        outputFile.parentFile?.mkdirs()

        val params = preview.params
        val widthDp = params.widthDp?.takeIf { it > 0 } ?: DEFAULT_WIDTH
        val heightDp = params.heightDp?.takeIf { it > 0 } ?: DEFAULT_HEIGHT
        val isRound = isRoundDevice(params.device) && params.showSystemUi

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

        val a11yEnabled = System.getProperty("composeai.a11y.enabled") == "true"
        if (a11yEnabled) {
            renderWithA11y(params, widthDp, heightDp, outputFile, roborazziOptions, composeOptions)
        } else {
            renderDefault(params, widthDp, heightDp, outputFile, roborazziOptions, composeOptions)
        }
    }

    /**
     * Default render path — paused `mainClock`, pump [FRAME_BUDGET] frames, capture.
     *
     * Replaces the earlier `captureRoboImage { @Composable }` flow. The
     * composable overload drives the composition to idle before capturing,
     * which hangs on infinite animations (`CircularProgressIndicator()`,
     * `rememberInfiniteTransition`, hand-rolled `withFrameNanos` loops) and
     * was the root cause of the 12-minute / OOM runs that PR #14 papered
     * over. With `mainClock.autoAdvance = false` we never wait for idle —
     * each `advanceTimeByFrame()` deterministically dispatches one frame
     * cycle, and after [FRAME_BUDGET] frames we just capture whatever the
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
        outputFile: File,
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
            isRound = isRoundDevice(params.device) && params.showSystemUi,
            locale = params.locale,
            uiMode = params.uiMode,
        )
        // fontScale isn't a Robolectric resource qualifier — it's a
        // Configuration field. `RuntimeEnvironment.setFontScale(Float)` is the
        // Robolectric API that updates Configuration before the activity
        // launches; Roborazzi's own `RoborazziComposeFontScaleOption` uses the
        // same entrypoint. Applied unconditionally (default 1f) so it resets
        // between previews sharing the same Robolectric sandbox.
        org.robolectric.RuntimeEnvironment.setFontScale(params.fontScale)

        val rule = createAndroidComposeRule<ComponentActivity>()
        val description = org.junit.runner.Description.createTestDescription(
            this::class.java,
            "renderDefault_${preview.id}",
        )
        val statement = object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                rule.mainClock.autoAdvance = false
                rule.setContent {
                    CompositionLocalProvider(LocalInspectionMode provides true) {
                        val bg = resolveBackgroundColor(params)
                        androidx.compose.foundation.layout.Box(
                            modifier = androidx.compose.ui.Modifier
                                .fillMaxSize()
                                .background(bg),
                        ) {
                            strategyFor(params.kind).Render(preview, widthDp, heightDp)
                        }
                    }
                }
                // Pump a fixed number of frames. With mainClock paused, each
                // advanceTimeByFrame() deterministically dispatches one frame
                // (layout, draw, animations advance by frameInterval).
                // Infinite animations park at t = FRAME_BUDGET * frameInterval
                // regardless of wall clock — repeated captures are byte-identical.
                repeat(FRAME_BUDGET) {
                    rule.mainClock.advanceTimeByFrame()
                }
                rule.onRoot().captureRoboImage(
                    file = outputFile,
                    roborazziOptions = roborazziOptions,
                )
            }
        }
        rule.apply(statement, description).evaluate()
    }

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

    /**
     * A11y-enabled render path.
     *
     * Uses `createAndroidComposeRule<ComponentActivity>()` + `onRoot()
     * .captureRoboImage(...)` for the screenshot and runs ATF against the
     * [ViewRootForTest]-backed view fetched through the same
     * [androidx.compose.ui.test.SemanticsNodeInteraction]. That's the combo
     * roborazzi-accessibility-check uses internally (see
     * `checkRoboAccessibility` in RoborazziATFAccessibilityChecker.kt); the
     * composable form of `captureRoboImage` bails out too early for ATF
     * because it eagerly closes the ActivityScenario, and running ATF on the
     * Activity's DecorView under Robolectric yields all NOT_RUN — whereas
     * `ViewRootForTest.view` is exactly what ATF was written to walk.
     *
     * Inspection mode is intentionally OFF here — `LocalInspectionMode=true`
     * suppresses compose's accessibility semantics population, leaving ATF
     * with nothing to evaluate. Opting into a11y therefore trades off a bit
     * of inspection-mode determinism (infinite animations tick instead of
     * parking on frame 0) for real findings.
     *
     * The rule is created and applied manually so the default (a11y-off)
     * path keeps its lightweight `captureRoboImage { @Composable }` flow
     * and doesn't have to pay the `createAndroidComposeRule` setup cost.
     */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun renderWithA11y(
        params: RenderPreviewParams,
        widthDp: Int,
        heightDp: Int,
        outputFile: File,
        roborazziOptions: RoborazziOptions,
        composeOptions: RoborazziComposeOptions,
    ) {
        // Robolectric 4.13+ rejects `ActivityScenario.launch` when no manifest
        // entry resolves the default MAIN/LAUNCHER intent for the activity
        // class (robolectric/robolectric#4736). ui-test-manifest adds
        // `<activity android:name="androidx.activity.ComponentActivity">` but
        // without an intent filter, so we register the component explicitly
        // with ShadowPackageManager — cheap, idempotent, and works regardless
        // of whether the consumer's merged manifest has the right filter.
        val appContext: android.app.Application =
            androidx.test.core.app.ApplicationProvider.getApplicationContext()
        org.robolectric.Shadows.shadowOf(appContext.packageManager)
            .addActivityIfNotPresent(
                android.content.ComponentName(appContext.packageName, ComponentActivity::class.java.name),
            )

        val rule = createAndroidComposeRule<ComponentActivity>()
        val description = org.junit.runner.Description.createTestDescription(
            this::class.java,
            "a11yRender_${preview.id}",
        )
        val statement = object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                rule.setContent {
                    CompositionLocalProvider(LocalInspectionMode provides false) {
                        strategyFor(params.kind).Render(preview, widthDp, heightDp)
                    }
                }
                val onRoot = rule.onRoot()
                onRoot.captureRoboImage(
                    file = outputFile,
                    roborazziOptions = roborazziOptions,
                )

                val view = (onRoot.fetchSemanticsNode().root as ViewRootForTest).view
                val findings = AccessibilityChecker.check(preview.id, view)
                val a11yDir = File(
                    System.getProperty("composeai.a11y.outputDir")
                        ?: "build/compose-previews/accessibility-per-preview",
                )
                AccessibilityChecker.writePerPreviewReport(a11yDir, preview.id, findings)
            }
        }
        rule.apply(statement, description).evaluate()
    }

    companion object {
        private const val DEFAULT_WIDTH = 400
        private const val DEFAULT_HEIGHT = 800

        /**
         * Frames to drive before capture in the paused-`mainClock` path.
         * Small on purpose: with [ui-test-manifest]'s ComposeTestRule we own
         * the clock, so "settled" is `autoAdvance = false` + however many
         * frames we explicitly pump. 2 is enough for static previews (initial
         * composition + one settle pass for `LaunchedEffect`s); for infinite
         * animations it defines the deterministic snapshot point.
         */
        private const val FRAME_BUDGET = 2
    }
}

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
