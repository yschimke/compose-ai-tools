package ee.schimke.composeai.renderer

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Verifies round-device detection and circular clipping for Wear OS
 * `@Preview(device = "id:wearos_*_round", showSystemUi = true)` rendering.
 *
 * The renderer post-processes the captured bitmap: when the preview's device
 * string is round and `showSystemUi = true`, pixels outside the inscribed
 * circle are made fully transparent so the saved PNG matches the physical
 * round device surface.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CircularClipTest {

    @Test
    fun `isRoundDevice detects wear round device ids`() {
        assertTrue(isRoundDevice("id:wearos_small_round"))
        assertTrue(isRoundDevice("id:wearos_large_round"))
    }

    @Test
    fun `isRoundDevice detects custom round device spec`() {
        assertTrue(isRoundDevice("spec:width=200dp,height=200dp,isRound=true"))
        assertTrue(isRoundDevice("spec:shape=Round,width=200dp,height=200dp"))
    }

    @Test
    fun `isRoundDevice treats null blank and rectangular devices as non-round`() {
        assertFalse(isRoundDevice(null))
        assertFalse(isRoundDevice(""))
        assertFalse(isRoundDevice("   "))
        assertFalse(isRoundDevice("id:wearos_square"))
        assertFalse(isRoundDevice("id:wearos_rect"))
        assertFalse(isRoundDevice("id:pixel_5"))
        assertFalse(isRoundDevice("spec:width=200dp,height=200dp"))
    }

    @Test
    fun `applyCircularClip makes corners transparent and keeps centre opaque`() {
        val src = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.RED)

        val clipped = applyCircularClip(src)

        // All four corners lie well outside the inscribed circle — fully transparent.
        assertEquals(0, Color.alpha(clipped.getPixel(0, 0)))
        assertEquals(0, Color.alpha(clipped.getPixel(99, 0)))
        assertEquals(0, Color.alpha(clipped.getPixel(0, 99)))
        assertEquals(0, Color.alpha(clipped.getPixel(99, 99)))

        // Centre is well inside — fully opaque, colour preserved.
        val centre = clipped.getPixel(50, 50)
        assertEquals(255, Color.alpha(centre))
        assertEquals(255, Color.red(centre))
        assertEquals(0, Color.green(centre))
        assertEquals(0, Color.blue(centre))
    }

    @Test
    fun `applyCircularClip does not mutate the source bitmap`() {
        val src = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.RED)

        applyCircularClip(src)

        // Source corners remain fully opaque — clipping returned a new bitmap.
        assertEquals(255, Color.alpha(src.getPixel(0, 0)))
        assertEquals(255, Color.alpha(src.getPixel(99, 99)))
    }

    @Test
    fun `applyCircularClip produces circular alpha profile on a non-square bitmap`() {
        // Tall bitmap — inscribed circle radius = min(w, h) / 2 = 50.
        val src = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.RED)

        val clipped = applyCircularClip(src)

        // Centre of the bitmap is inside the circle.
        assertEquals(255, Color.alpha(clipped.getPixel(50, 100)))
        // Outside the circle vertically — top and bottom strips are transparent.
        assertEquals(0, Color.alpha(clipped.getPixel(50, 10)))
        assertEquals(0, Color.alpha(clipped.getPixel(50, 190)))
    }
}
