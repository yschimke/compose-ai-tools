package ee.schimke.composeai.renderer

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Verifies [SystemBarsFrame] paints a status bar at the top and a navigation
 * pill bar at the bottom. The wrapped content fills the canvas with a single
 * solid colour; we then sample pixel rows at known y-offsets and assert that
 * the bar bands are tinted away from the body colour while the centre row is
 * untouched.
 *
 * Stays close to [PreviewWrapperTest]'s setup: Robolectric activity, manual
 * measure/layout pump, then `decor.draw(Canvas(bitmap))` to grab pixels. No
 * Roborazzi / no `captureRoboImage` — keeps the test independent of the
 * full preview pipeline so a rendering regression in [SystemBarsFrame]
 * can't be masked by an unrelated capture-path failure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SystemBarsFrameTest {

    @Test
    fun `light mode paints translucent bars over the top and bottom of the content`() {
        val width = 200
        val height = 600
        val bitmap = renderToBitmap(width, height, uiMode = 0) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Red))
        }

        val centrePixel = bitmap.getPixel(width / 2, height / 2)
        // Centre is body content — the red fill is preserved untouched.
        assertEquals(
            "centre pixel should remain a strong red, got rgb=" +
                "(${AndroidColor.red(centrePixel)},${AndroidColor.green(centrePixel)}," +
                "${AndroidColor.blue(centrePixel)})",
            true,
            AndroidColor.red(centrePixel) > 200 &&
                AndroidColor.green(centrePixel) < 50 &&
                AndroidColor.blue(centrePixel) < 50,
        )

        // Status bar lifts the red toward pink via its translucent white tint.
        // We sample a column position that's clear of the clock text and the
        // battery glyph (left edge gets the clock; right edge gets the battery
        // — middle is the bar background).
        val statusPixel = bitmap.getPixel(width / 2, 4)
        assertTrue(
            "status bar pixel should have a green/blue lift from the white tint, " +
                "got rgb=(${AndroidColor.red(statusPixel)}," +
                "${AndroidColor.green(statusPixel)},${AndroidColor.blue(statusPixel)})",
            AndroidColor.green(statusPixel) > 40 && AndroidColor.blue(statusPixel) > 40,
        )
    }

    @Test
    fun `dark mode darkens the top strip rather than lightening it`() {
        val width = 200
        val height = 600
        val bitmap = renderToBitmap(
            width = width,
            height = height,
            uiMode = Configuration.UI_MODE_NIGHT_YES,
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Red))
        }

        val statusPixel = bitmap.getPixel(width / 2, 4)
        // Translucent black tint pulls the red channel below the original 255
        // fill — same shape `cropPngTopLeft` uses to validate post-process
        // behaviour without committing to an exact alpha-blend formula.
        assertTrue(
            "dark-mode status bar pixel should be darkened by the night tint, " +
                "got red=${AndroidColor.red(statusPixel)}",
            AndroidColor.red(statusPixel) < 230,
        )
    }

    @Test
    fun `nav bar paints a band along the bottom of the content`() {
        val width = 200
        val height = 600
        val bitmap = renderToBitmap(width, height, uiMode = 0) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Red))
        }

        val navPixel = bitmap.getPixel(width / 2, height - 4)
        // Nav bar uses the same translucent white tint as the status bar in
        // light mode — assert the same lift signature so a regression that
        // forgets to draw the bottom band fails this test.
        assertTrue(
            "nav bar pixel should have a green/blue lift from the white tint, " +
                "got rgb=(${AndroidColor.red(navPixel)}," +
                "${AndroidColor.green(navPixel)},${AndroidColor.blue(navPixel)})",
            AndroidColor.green(navPixel) > 40 && AndroidColor.blue(navPixel) > 40,
        )
    }

    // --- helpers ----------------------------------------------------------

    private fun renderToBitmap(
        width: Int,
        height: Int,
        uiMode: Int,
        body: @Composable () -> Unit,
    ): Bitmap {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get()
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        val hwAccel = WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        activity.window.setFlags(hwAccel, hwAccel)
        controller.create().start().resume().visible()

        @Suppress("DEPRECATION")
        val shadowDisplay = Shadows.shadowOf(activity.windowManager.defaultDisplay)
        shadowDisplay.setWidth(width)
        shadowDisplay.setHeight(height)

        activity.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                SystemBarsFrame(uiMode = uiMode, content = body)
            }
        }

        val decor = activity.window.decorView
        findComposeView(decor)?.apply {
            layoutParams = layoutParams.apply {
                this.width = ViewGroup.LayoutParams.MATCH_PARENT
                this.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }

        val wSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        repeat(5) {
            ShadowLooper.idleMainLooper(16L, TimeUnit.MILLISECONDS)
            decor.measure(wSpec, hSpec)
            decor.layout(0, 0, width, height)
            decor.invalidate()
            ShadowLooper.idleMainLooper(16L, TimeUnit.MILLISECONDS)
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        return bitmap
    }

    private fun findComposeView(view: View): View? {
        if (view.javaClass.simpleName == "ComposeView") return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findComposeView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
}
