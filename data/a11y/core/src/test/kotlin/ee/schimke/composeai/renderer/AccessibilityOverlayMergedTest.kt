package ee.schimke.composeai.renderer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * End-to-end coverage for the merged-vs-unmerged visual treatment in
 * [AccessibilityOverlay]. Uses the same Robolectric + native graphics
 * setup as [CircularClipTest] so `Canvas.drawRect` with a
 * `DashPathEffect` actually rasterises (the JVM-only path skips effects).
 *
 * Strategy: render two overlays against a **black** source bitmap. With a
 * black background the border treatment maps to easy-to-distinguish pixel
 * intensities — the alpha-200 solid border stroke for merged nodes blends
 * to a clearly bright pastel, while the dotted unmerged border leaves
 * regular gaps that stay black (no fill underneath either, since
 * unmerged regions are line-only). Counting "bright" pixels along the
 * bounds rectangle's top edge separates solid from dotted cleanly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessibilityOverlayMergedTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun `unmerged border is dashed - fewer bright stroke pixels than merged solid`() {
        val sourceFile = blackSourcePng(width = 400, height = 200)
        val bounds = "100,40,300,140"

        val mergedBitmap = renderOverlay(sourceFile, bounds, merged = true)
        val unmergedBitmap = renderOverlay(sourceFile, bounds, merged = false)

        val mergedHits = countBrightStrokePixelsAtTopEdge(mergedBitmap, bounds)
        val unmergedHits = countBrightStrokePixelsAtTopEdge(unmergedBitmap, bounds)

        assertTrue(
            "merged border should paint many bright stroke pixels along its top edge: " +
                "hits=$mergedHits",
            mergedHits >= 100,
        )
        assertTrue(
            "unmerged border should still paint *some* bright stroke pixels (dashes, " +
                "not absence): hits=$unmergedHits",
            unmergedHits >= 20,
        )
        assertTrue(
            "unmerged border should paint visibly fewer bright stroke pixels than merged " +
                "(dashed pattern leaves gaps): merged=$mergedHits, unmerged=$unmergedHits",
            unmergedHits < mergedHits - 30,
        )
    }

    @Test
    fun `merged and unmerged overlays differ pixel-wise`() {
        // Belt-and-braces sanity: merged vs unmerged must produce
        // distinguishable PNGs even before we measure stroke counts. If
        // this regresses, something has decoupled `merged` from the
        // overlay code path entirely.
        val sourceFile = blackSourcePng(width = 400, height = 200)
        val bounds = "100,40,300,140"

        val mergedBitmap = renderOverlay(sourceFile, bounds, merged = true)
        val unmergedBitmap = renderOverlay(sourceFile, bounds, merged = false)

        var differing = 0
        for (y in 0 until mergedBitmap.height) {
            for (x in 0 until mergedBitmap.width) {
                if (mergedBitmap.getPixel(x, y) != unmergedBitmap.getPixel(x, y)) differing++
            }
        }
        assertNotEquals(
            "merged vs unmerged overlays must differ pixel-wise: differing=$differing",
            0,
            differing,
        )
    }

    @Test
    fun `merged parent and its children render as a single grouped row`() {
        // A Wear-shaped card (clickable parent + two Text children whose
        // bounds sit inside it) is the canonical pattern for inline-child
        // rendering. Verify the legend produces a single grouped row by
        // checking the canvas height stays well below what three separate
        // rows (one per node) would consume.
        val sourceFile = blackSourcePng(width = 400, height = 400)
        val parent = AccessibilityNode(
            label = "",
            role = "ViewGroup",
            states = listOf("clickable"),
            merged = true,
            boundsInScreen = "40,80,360,200",
        )
        val child1 = AccessibilityNode(
            label = "Morning run",
            role = "TextView",
            states = emptyList(),
            merged = false,
            boundsInScreen = "60,100,340,140",
        )
        val child2 = AccessibilityNode(
            label = "5.2 km · 28 min",
            role = "TextView",
            states = emptyList(),
            merged = false,
            boundsInScreen = "60,150,340,190",
        )
        val written = AccessibilityOverlay.generate(
            sourceFile, emptyList(), listOf(parent, child1, child2),
        )
        assertNotNull("overlay should be written", written)
        val bm = BitmapFactory.decodeFile(written!!.absolutePath)
        assertNotNull("overlay PNG should decode", bm)
        // Side-by-side layout means width = screenshot.width + LEGEND_WIDTH.
        assertEquals("expected side-by-side composition", 400 + 540, bm.width)
        // Three separate rows would push canvas height past ~360. A single
        // grouped row (parent label + subtitle + one inline-child line)
        // lands well under that, so the screenshot height (400) ends up
        // dominating canvas height.
        assertEquals("grouped row should leave screenshot taller than legend", 400, bm.height)
    }

    @Test
    fun `older accessibility json without merged field deserializes as merged`() {
        // Backward-compat guard for accessibility.json files written before
        // the merged flag was added: missing field must round-trip as
        // merged=true so historical reports keep rendering with solid
        // borders + no `↳` prefix.
        val legacy = """
            {
              "label": "Hello",
              "role": "TextView",
              "boundsInScreen": "0,0,10,10"
            }
        """.trimIndent()
        val node = kotlinx.serialization.json.Json.decodeFromString(
            AccessibilityNode.serializer(),
            legacy,
        )
        assertEquals(true, node.merged)
    }

    private fun blackSourcePng(width: Int, height: Int): File {
        val src = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
        }
        val out = tempDir.newFile()
        out.outputStream().use { src.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return out
    }

    private fun renderOverlay(sourceFile: File, bounds: String, merged: Boolean): Bitmap {
        val node = AccessibilityNode(
            label = "Save",
            role = "Button",
            states = listOf("clickable"),
            merged = merged,
            boundsInScreen = bounds,
        )
        val written = AccessibilityOverlay.generate(sourceFile, emptyList(), listOf(node))
        assertNotNull("overlay should be written", written)
        val bm = BitmapFactory.decodeFile(written!!.absolutePath)
        assertNotNull("overlay PNG should decode", bm)
        return bm
    }

    /**
     * Counts pixels along the node's top-border row that look like a
     * stroke pixel — bright pastel against black, distinguishable from
     * the alpha-40/80 fill underneath. Locates the screenshot offset by
     * finding the topmost row containing a black pixel run wide enough
     * to be the screenshot bitmap (legend / header rows are white or
     * pale grey, never solid black).
     */
    private fun countBrightStrokePixelsAtTopEdge(composite: Bitmap, bounds: String): Int {
        val parts = bounds.split(",").map { it.toInt() }
        val left = parts[0]
        val top = parts[1]
        val right = parts[2]

        // Find the screenshot offset by locating the topmost row with a
        // long run of pure black — that's the source bitmap, which only
        // appears inside the screenshot band.
        var imageY = -1
        var imageX = -1
        outer@ for (y in 0 until composite.height) {
            var runStart = -1
            for (x in 0 until composite.width) {
                if (composite.getPixel(x, y) == Color.BLACK) {
                    if (runStart == -1) runStart = x
                    if (x - runStart >= 50) {
                        imageY = y
                        imageX = runStart
                        break@outer
                    }
                } else {
                    runStart = -1
                }
            }
        }
        check(imageY >= 0 && imageX >= 0) {
            "could not locate screenshot band in composite ${composite.width}x${composite.height}"
        }

        // Border stroke top edge is at y = imageY + top (the drawRect
        // call insets by 0.5px so AA spreads across two rows; this row
        // catches the densest run).
        val edgeRow = imageY + top
        val span = (imageX + left)..(imageX + right - 1)

        var hits = 0
        for (x in span) {
            if (x !in 0 until composite.width) continue
            if (isBrightStrokePixel(composite.getPixel(x, edgeRow))) hits++
        }
        return hits
    }

    /**
     * `true` when the pixel reads as a stroke (alpha-200 pastel border on
     * black) rather than a fill-only zone (alpha-40/80 pastel on black).
     * Empirical threshold from manual sampling — the bright stroke channel
     * peaks at ~195 (`0xF8 * 200/255`) while alpha-80 fill caps at ~78
     * (`0xF8 * 80/255`). Any single channel above 120 is firmly in stroke
     * territory and well clear of even the alpha-80 fill ceiling.
     */
    private fun isBrightStrokePixel(px: Int): Boolean =
        Color.red(px) > 120 || Color.green(px) > 120 || Color.blue(px) > 120
}
