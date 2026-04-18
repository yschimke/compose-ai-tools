package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toArgb
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

        renderDefault(params, widthDp, heightDp, outputFile, roborazziOptions, composeOptions)
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
            isRound = isRoundDevice(params.device) &&
                (params.showSystemUi || params.kind == PreviewKind.TILE),
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
                rule.setContent {
                    CompositionLocalProvider(LocalInspectionMode provides !a11yEnabled) {
                        strategyFor(params.kind).Render(preview, widthDp, heightDp)
                    }
                }
                // Advance the clock by a fixed virtual-time offset, then
                // capture. With `mainClock.autoAdvance = false` this is the
                // only clock motion Compose sees, so infinite animations park
                // at exactly this virtual time across runs — repeated captures
                // are byte-identical.
                //
                // If the preview carries `@RoboComposePreviewOptions`, the
                // per-entry `advanceTimeMillis` overrides the default;
                // `DiscoverPreviewsTask` has already fanned each timing out
                // into its own manifest entry, so this value is one specific
                // timing per render.
                val advanceMs = params.advanceTimeMillis ?: CAPTURE_ADVANCE_MS
                rule.mainClock.advanceTimeBy(advanceMs)
                val onRoot = rule.onRoot()
                onRoot.captureRoboImage(
                    file = outputFile,
                    roborazziOptions = roborazziOptions,
                )

                if (a11yEnabled) {
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

    companion object {
        private const val DEFAULT_WIDTH = 400
        private const val DEFAULT_HEIGHT = 800

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
