package ee.schimke.composeai.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the shape of the scripted `ScrollMode.GIF` plan produced by
 * [buildGifScrollScript]. These assertions aren't pixel-exact — they
 * cover the invariants the runtime relies on:
 *
 *  - slow ramp frames appear first, at the calibrated slow step size;
 *  - fling frames decay geometrically from peak down to min;
 *  - the plan respects the per-fling distance cap and emits an
 *    inter-fling hold between swipes on tall content;
 *  - total scrolled distance never exceeds the content extent hint.
 *
 * Values are chosen so `density = 1f` and inputs are dp-equivalent,
 * which keeps the arithmetic easy to cross-check by hand.
 */
class GifScrollScriptTest {

    private val frameIntervalMs = 80
    private val viewportPx = 400f
    private val density = 1f

    @Test
    fun `empty content emits no steps`() {
        val script = buildGifScrollScript(
            contentExtentPxHint = 0f,
            viewportPx = viewportPx,
            density = density,
            frameIntervalMs = frameIntervalMs,
        )
        assertTrue("expected no steps, got $script", script.isEmpty())
    }

    @Test
    fun `short content uses slow ramp only`() {
        // 50 dp of scrollable content — fits inside the 4-frame × 30 dp
        // slow ramp (120 dp) with room to spare, so no fling should be
        // emitted at all.
        val script = buildGifScrollScript(
            contentExtentPxHint = 50f,
            viewportPx = viewportPx,
            density = density,
            frameIntervalMs = frameIntervalMs,
        )
        // No inter-fling holds (all steps have stepPx > 0).
        assertTrue("expected no holds in $script", script.all { it.scrollPx > 0f })
        // All scrolls are slow-ramp-sized (<= 30 dp/frame).
        assertTrue(
            "some step exceeded slow ramp limit: $script",
            script.all { it.scrollPx <= SLOW_RAMP_STEP_DP * density + 0.01f },
        )
        assertEquals(50f, script.sumOf { it.scrollPx.toDouble() }.toFloat(), 0.01f)
    }

    @Test
    fun `medium content ramps then flings once`() {
        // 600 dp content ≈ 1.5 viewports. Enough to trigger a fling after
        // the ramp but well under the `FLING_MAX_DISTANCE_VIEWPORTS` cap
        // (1.5 × 400 dp = 600 dp) — so no inter-fling hold, just one
        // continuous decay.
        val script = buildGifScrollScript(
            contentExtentPxHint = 600f,
            viewportPx = viewportPx,
            density = density,
            frameIntervalMs = frameIntervalMs,
        )

        val slowStepPx = SLOW_RAMP_STEP_DP * density
        val rampSteps = script.takeWhile { it.scrollPx <= slowStepPx + 0.01f && it.scrollPx > 0f }
        assertEquals(SLOW_RAMP_FRAMES, rampSteps.size)

        val flingSteps = script.drop(rampSteps.size)
        assertFalse("unexpected hold in single-fling plan: $flingSteps", flingSteps.any { it.scrollPx == 0f })
        assertTrue(
            "fling peak exceeded expected: ${flingSteps.first()}",
            flingSteps.first().scrollPx <= FLING_PEAK_DP_PER_FRAME * density + 0.01f,
        )
        val total = script.sumOf { it.scrollPx.toDouble() }.toFloat()
        assertEquals(600f, total, 0.1f)
    }

    @Test
    fun `tall content emits multiple flings with holds between`() {
        // 2000 dp content ≫ 1 × `flingCap` (600 dp) — should produce
        // several fling bursts with inter-fling holds (scrollPx == 0).
        val script = buildGifScrollScript(
            contentExtentPxHint = 2000f,
            viewportPx = viewportPx,
            density = density,
            frameIntervalMs = frameIntervalMs,
        )

        val holds = script.filter { it.scrollPx == 0f }
        assertTrue("expected at least one inter-fling hold, got $script", holds.isNotEmpty())
        assertTrue(
            "hold delay should be INTER_FLING_HOLD_MS: $holds",
            holds.all { it.delayMs == INTER_FLING_HOLD_MS },
        )
        // With 2000 dp at a 600 dp cap per fling, expect at least 2 holds
        // (between 3 flings' worth of travel), though the tail fling
        // covers the remainder without reaching the cap.
        assertTrue("expected at least 2 holds, got ${holds.size}", holds.size >= 2)

        val totalScrolled = script.sumOf { it.scrollPx.toDouble() }.toFloat()
        assertTrue("overshot extent: $totalScrolled", totalScrolled <= 2000f + 0.1f)
        assertEquals(2000f, totalScrolled, 0.1f)
    }

    @Test
    fun `scroll frames carry the supplied frame interval`() {
        val script = buildGifScrollScript(
            contentExtentPxHint = 800f,
            viewportPx = viewportPx,
            density = density,
            frameIntervalMs = 120,
        )
        val scrolling = script.filter { it.scrollPx > 0f }
        assertTrue("no scrolling frames emitted", scrolling.isNotEmpty())
        assertTrue(
            "scroll frames should use supplied cadence: $scrolling",
            scrolling.all { it.delayMs == 120 },
        )
    }

    @Test
    fun `density scales step sizes`() {
        val hi = buildGifScrollScript(
            contentExtentPxHint = 600f,
            viewportPx = viewportPx,
            density = 2f,
            frameIntervalMs = frameIntervalMs,
        )
        // First ramp frame should be ~60 px (30 dp × 2), not 30 px.
        val firstRamp = hi.first { it.scrollPx > 0f }
        assertEquals(60f, firstRamp.scrollPx, 0.01f)
    }
}
