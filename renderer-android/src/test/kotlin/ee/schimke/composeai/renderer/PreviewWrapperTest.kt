package ee.schimke.composeai.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * Verifies that `@PreviewWrapper` wrappers flow through the renderer end-to-end:
 * [resolveWrapper] looks up the provider class + its `Wrap(content)` method, and
 * calling it paints the wrapper's composition around the preview body.
 *
 * Test wrappers are plain classes with a `@Composable fun Wrap(content)` method —
 * matching the shape of `PreviewWrapperProvider` without depending on the real
 * interface (which lives in Compose 1.11+, not in the test classpath).
 */
@RunWith(RobolectricTestRunner::class)
class PreviewWrapperTest {

    /**
     * Paints an opaque green border around the wrapped content, so we can verify
     * from the output bitmap that the wrapper actually ran.
     */
    class GreenBorderWrapper {
        @Composable
        fun Wrap(content: @Composable () -> Unit) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Green)) {
                Box(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                    content()
                }
            }
        }
    }

    @Test
    fun `resolveWrapper locates Wrap method and instantiates provider`() {
        val (method, instance) = resolveWrapper(GreenBorderWrapper::class.java.name)

        assertSame(GreenBorderWrapper::class.java, instance.javaClass)
        // ComposableMethod wraps a java.lang.reflect.Method — sanity-check the
        // underlying method exists with the `Wrap` name.
        assertEquals("Wrap", method.asMethod().name)
    }

    @Test
    fun `wrapper composition paints around the preview body`() {
        val size = 200
        val bitmap = renderWrappedToBitmap(size, size, GreenBorderWrapper::class.java.name) {
            // The body fills the inner area with red.
            Box(modifier = Modifier.fillMaxSize().background(Color.Red))
        }

        // A pixel on the outer edge (inside the 20dp-padded border) is painted green
        // by the wrapper.
        val edge = bitmap.getPixel(5, size / 2)
        assertEquals(
            "edge pixel should be mostly green: alpha=${AndroidColor.alpha(edge)} " +
                "r=${AndroidColor.red(edge)} g=${AndroidColor.green(edge)} b=${AndroidColor.blue(edge)}",
            true,
            AndroidColor.green(edge) > AndroidColor.red(edge) &&
                AndroidColor.green(edge) > AndroidColor.blue(edge),
        )

        // Centre sits inside the body, which is red.
        val centre = bitmap.getPixel(size / 2, size / 2)
        assertEquals(
            "centre pixel should be mostly red: alpha=${AndroidColor.alpha(centre)} " +
                "r=${AndroidColor.red(centre)} g=${AndroidColor.green(centre)} b=${AndroidColor.blue(centre)}",
            true,
            AndroidColor.red(centre) > AndroidColor.green(centre) &&
                AndroidColor.red(centre) > AndroidColor.blue(centre),
        )
    }

    @Test
    fun `nested test wrapper also runs through resolveWrapper`() {
        // Regression guard: nested classes resolve via the same `$`-separated FQN
        // that ClassGraph + Kotlin reflection emit.
        val fqn = GreenBorderWrapper::class.java.name
        val (_, instance) = resolveWrapper(fqn)
        assertSame(GreenBorderWrapper::class.java, instance.javaClass)
    }

    // --- helpers ----------------------------------------------------------

    private fun renderWrappedToBitmap(
        widthPx: Int,
        heightPx: Int,
        wrapperFqn: String,
        body: @Composable () -> Unit,
    ): Bitmap {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        controller.get().window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        val activity = controller.create().start().resume().visible().get()

        @Suppress("DEPRECATION")
        val shadowDisplay = Shadows.shadowOf(activity.windowManager.defaultDisplay)
        shadowDisplay.setWidth(widthPx)
        shadowDisplay.setHeight(heightPx)

        activity.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                InvokeThroughWrapper(wrapperFqn, body)
            }
        }

        val view = activity.window.decorView
        val wSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)

        repeat(5) {
            ShadowLooper.idleMainLooper(16L, TimeUnit.MILLISECONDS)
            view.measure(wSpec, hSpec)
            view.layout(0, 0, widthPx, heightPx)
            view.invalidate()
            ShadowLooper.idleMainLooper(16L, TimeUnit.MILLISECONDS)
        }

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    @Composable
    private fun InvokeThroughWrapper(
        wrapperFqn: String,
        body: @Composable () -> Unit,
    ) {
        val resolved = remember(wrapperFqn) { resolveWrapper(wrapperFqn) }
        resolved.first.invoke(currentComposer, resolved.second, body)
    }
}
