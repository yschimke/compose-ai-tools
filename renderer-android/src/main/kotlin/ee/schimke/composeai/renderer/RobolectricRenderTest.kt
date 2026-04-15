package ee.schimke.composeai.renderer

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.reflect.ComposableMethod
import androidx.compose.runtime.reflect.getDeclaredComposableMethod
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.json.Json
import org.junit.Rule
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
 */
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class RobolectricRenderTestBase(private val preview: RenderPreviewEntry) {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun renderPreview() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                val clazz = Class.forName(preview.className)
                // @PreviewWrapper(Provider::class) — instantiate the provider reflectively
                // and wrap the composable body so preview-only scaffolding (themes,
                // CompositionLocals, insets) is applied consistently with the IDE preview.
                val composableMethod = clazz.getDeclaredComposableMethod(preview.functionName)
                val bgColor = when {
                    preview.params.backgroundColor != 0L -> Color(preview.params.backgroundColor.toInt())
                    preview.params.showBackground -> Color.White
                    else -> Color.Transparent
                }
                val body: @Composable () -> Unit = {
                    Box(modifier = Modifier.fillMaxSize().background(bgColor)) {
                        InvokeComposable(composableMethod, null)
                    }
                }
                val wrapperFqn = preview.params.wrapperClassName
                if (wrapperFqn != null) {
                    InvokeWrappedComposable(wrapperFqn, body)
                } else {
                    body()
                }
            }
        }

        composeTestRule.waitForIdle()

        val outputDir = File(System.getProperty("composeai.render.outputDir") ?: "build/compose-previews/renders")
        val outputFile = File(outputDir, "${preview.id}.png")
        outputFile.parentFile?.mkdirs()

        val tempFile = File.createTempFile("roborazzi", ".png")
        val rootNode = composeTestRule.onAllNodes(androidx.compose.ui.test.isRoot())[0]
        rootNode.captureRoboImage(tempFile.absolutePath)
        val bitmap = android.graphics.BitmapFactory.decodeFile(tempFile.absolutePath)
        tempFile.delete()

        val finalBitmap = if (isRoundDevice(preview.params.device) && preview.params.showSystemUi) {
            val clipped = applyCircularClip(bitmap)
            bitmap.recycle()
            clipped
        } else {
            bitmap
        }

        FileOutputStream(outputFile).use { fos ->
            finalBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        finalBitmap.recycle()
    }
}

@Composable
private fun InvokeComposable(
    composableMethod: ComposableMethod,
    instance: Any?,
) {
    composableMethod.invoke(currentComposer, instance)
}

/**
 * Reflectively instantiates the `PreviewWrapperProvider` identified by [wrapperFqn]
 * and invokes its `Wrap(content)` composable around [body].
 *
 * `PreviewWrapperProvider.Wrap(content: @Composable () -> Unit)` compiles to
 * `Wrap(Function2, Composer, int)` at the bytecode level — [getDeclaredComposableMethod]
 * handles the synthetic Composer/changed args, so we look the method up by the
 * content parameter's JVM type.
 */
@Composable
private fun InvokeWrappedComposable(
    wrapperFqn: String,
    body: @Composable () -> Unit,
) {
    val resolved = remember(wrapperFqn) { resolveWrapper(wrapperFqn) }
    resolved.first.invoke(currentComposer, resolved.second, body)
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
