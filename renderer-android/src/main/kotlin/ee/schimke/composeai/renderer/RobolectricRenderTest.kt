package ee.schimke.composeai.renderer

import android.app.Activity
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
import androidx.compose.runtime.reflect.getDeclaredComposableMethod
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RobolectricRenderTest {

    @Test
    fun renderPreview() {
        val className = System.getProperty("composeai.render.className")!!
        val functionName = System.getProperty("composeai.render.functionName")!!
        val widthPx = System.getProperty("composeai.render.widthPx")!!.toInt()
        val heightPx = System.getProperty("composeai.render.heightPx")!!.toInt()
        val density = System.getProperty("composeai.render.density")!!.toFloat()
        val showBackground = System.getProperty("composeai.render.showBackground")!!.toBoolean()
        val backgroundColor = System.getProperty("composeai.render.backgroundColor")!!.toLong()
        val outputFile = File(System.getProperty("composeai.render.outputFile")!!)

        val clazz = Class.forName(className)
        val composableMethod = clazz.getDeclaredComposableMethod(functionName)

        // Create and lifecycle the activity
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        controller.create().start().resume()
        val activity = controller.get()

        // Configure display dimensions
        val shadowDisplay = Shadows.shadowOf(activity.windowManager.defaultDisplay)
        val widthDp = (widthPx / density).toInt()
        val heightDp = (heightPx / density).toInt()
        shadowDisplay.setWidth(widthPx)
        shadowDisplay.setHeight(heightPx)
        val config = activity.resources.configuration
        config.screenWidthDp = widthDp
        config.screenHeightDp = heightDp
        config.densityDpi = (density * 160).toInt()
        activity.resources.updateConfiguration(config, activity.resources.displayMetrics)

        // Set composable content
        activity.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                val bgColor = when {
                    backgroundColor != 0L -> Color(backgroundColor.toInt())
                    showBackground -> Color.White
                    else -> Color.Transparent
                }
                Box(modifier = Modifier.fillMaxSize().background(bgColor)) {
                    InvokeComposable(composableMethod, null)
                }
            }
        }

        // Advance frames until rendering is stable
        val view = activity.window.decorView
        val wSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)

        var prevChecksum = 0L
        var stableCount = 0
        val maxFrames = 20

        for (frame in 0 until maxFrames) {
            ShadowLooper.idleMainLooper(16L, TimeUnit.MILLISECONDS)
            view.measure(wSpec, hSpec)
            view.layout(0, 0, widthPx, heightPx)
            view.invalidate()
            ShadowLooper.idleMainLooper(16L, TimeUnit.MILLISECONDS)

            // Sample pixels for stability check
            val sampleBitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val sampleCanvas = Canvas(sampleBitmap)
            view.draw(sampleCanvas)
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
        val canvas = Canvas(bitmap)
        view.draw(canvas)

        // Encode to PNG
        outputFile.parentFile?.mkdirs()
        FileOutputStream(outputFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        bitmap.recycle()
    }
}

@Composable
private fun InvokeComposable(
    composableMethod: androidx.compose.runtime.reflect.ComposableMethod,
    instance: Any?,
) {
    composableMethod.invoke(currentComposer, instance)
}
