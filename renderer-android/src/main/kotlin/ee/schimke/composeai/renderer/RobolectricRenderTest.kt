package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
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

    @OptIn(ExperimentalRoborazziApi::class)
    private fun renderDefault(
        params: RenderPreviewParams,
        widthDp: Int,
        heightDp: Int,
        outputFile: File,
        roborazziOptions: RoborazziOptions,
        composeOptions: RoborazziComposeOptions,
    ) {
        captureRoboImage(
            file = outputFile,
            roborazziOptions = roborazziOptions,
            roborazziComposeOptions = composeOptions,
        ) {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                strategyFor(params.kind).Render(preview, widthDp, heightDp)
            }
        }
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
