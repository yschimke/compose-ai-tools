package ee.schimke.composeai.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Parameterized Robolectric test — one instance per preview entry in the manifest.
 *
 * Robolectric is configured via robolectric.properties and system properties set
 * by the Gradle plugin. android.jar must be on the test classpath for the runner to
 * load (its class hierarchy references android.app.Application).
 *
 * TODO: Replace ParameterizedRobolectricTestRunner with SandboxBuilder + FixedConfiguration
 *  from org.robolectric:simulator. This would:
 *  - Remove the android.jar classpath requirement (sandbox provides Android classes)
 *  - Give explicit control over sandbox lifecycle and classpath filtering
 *  - Allow excluding android.jar from the render classpath (Robolectric provides its own)
 *  The tradeoff is more code (manual sandbox setup vs annotation-driven) and a dependency
 *  on the simulator module's API which is less documented than the test runner.
 *
 * System properties:
 *   composeai.render.manifest  — path to previews.json
 *   composeai.render.outputDir — directory for rendered PNGs
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class RobolectricRenderTest(private val preview: RenderPreviewEntry) {

    companion object {
        private const val DEFAULT_WIDTH = 400
        private const val DEFAULT_HEIGHT = 800
        private const val DENSITY = 2.0f
        private val json = Json { ignoreUnknownKeys = true }

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun previews(): List<Array<Any>> {
            val manifestPath = System.getProperty("composeai.render.manifest")
                ?: return emptyList()
            val file = File(manifestPath)
            if (!file.exists()) return emptyList()

            val manifest = json.decodeFromString<RenderManifest>(file.readText())
            return manifest.previews.map { arrayOf<Any>(it) }
        }
    }

    @Test
    fun renderPreview() {
        val outputDir = File(System.getProperty("composeai.render.outputDir") ?: "build/compose-previews/renders")
        val widthDp = preview.params.widthDp.takeIf { it > 0 } ?: DEFAULT_WIDTH
        val heightDp = preview.params.heightDp.takeIf { it > 0 } ?: DEFAULT_HEIGHT
        val widthPx = (widthDp * DENSITY).toInt()
        val heightPx = (heightDp * DENSITY).toInt()
        val outputFile = File(outputDir, "${preview.id}.png")

        val clazz = Class.forName(preview.className)
        val composableMethod = clazz.getDeclaredComposableMethod(preview.functionName)

        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        controller.get().window.addFlags(android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        val activity = controller.create().start().resume().visible().get()

        // Configure display dimensions
        @Suppress("DEPRECATION")
        val shadowDisplay = Shadows.shadowOf(activity.windowManager.defaultDisplay)
        shadowDisplay.setWidth(widthPx)
        shadowDisplay.setHeight(heightPx)
        val config = activity.resources.configuration
        config.screenWidthDp = widthDp
        config.screenHeightDp = heightDp
        config.densityDpi = (DENSITY * 160).toInt()
        @Suppress("DEPRECATION")
        activity.resources.updateConfiguration(config, activity.resources.displayMetrics)

        activity.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
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
                // `@PreviewWrapper(Provider::class)` — instantiate the provider reflectively
                // and call its `Wrap { body() }`. Reflection keeps this renderer compatible
                // with apps on stable Compose (no `PreviewWrapperProvider` on classpath).
                val wrapperFqn = preview.params.wrapperClassName
                if (wrapperFqn != null) {
                    InvokeWrappedComposable(wrapperFqn, body)
                } else {
                    body()
                }
            }
        }

        // Advance frames until rendering is stable (bounded, not unbounded idleMainLooper).
        // Unbounded idleMainLooper() hangs on infinite animations like CircularProgressIndicator.
        val view = activity.window.decorView
        val wSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)

        var prevChecksum = 0L
        var stableCount = 0

        for (frame in 0 until 20) {
            ShadowLooper.idleMainLooper(16L, TimeUnit.MILLISECONDS)
            view.measure(wSpec, hSpec)
            view.layout(0, 0, widthPx, heightPx)
            view.invalidate()
            ShadowLooper.idleMainLooper(16L, TimeUnit.MILLISECONDS)

            val sampleBitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(sampleBitmap))
            val pixels = IntArray(widthPx * heightPx)
            sampleBitmap.getPixels(pixels, 0, widthPx, 0, 0, widthPx, heightPx)
            val step = maxOf(1, pixels.size / 64)
            var checksum = 0L
            for (i in pixels.indices step step) {
                checksum = checksum * 31 + pixels[i]
            }
            sampleBitmap.recycle()

            if (checksum == prevChecksum) stableCount++ else stableCount = 0
            prevChecksum = checksum
            if (stableCount >= 2) break
        }

        // Capture final bitmap
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))

        // Post-process round Wear previews (device=*round* + showSystemUi=true)
        // so pixels outside the inscribed circle are transparent instead of bgColor.
        val finalBitmap = if (isRoundDevice(preview.params.device) && preview.params.showSystemUi) {
            val clipped = applyCircularClip(bitmap)
            bitmap.recycle()
            clipped
        } else {
            bitmap
        }

        outputFile.parentFile?.mkdirs()
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
