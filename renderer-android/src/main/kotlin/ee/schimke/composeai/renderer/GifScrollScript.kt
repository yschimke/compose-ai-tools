package ee.schimke.composeai.renderer

/**
 * One planned frame in the scripted `ScrollMode.GIF` walk.
 *
 * [scrollPx] is the delta to scroll before capturing — `0f` means "no
 * scroll, just capture the current state" (used for inter-fling dwells
 * inside the plan). [delayMs] is the per-frame GIF delay this capture
 * should render with.
 *
 * Hold-start and hold-end frames are synthesised by the runtime around
 * the scripted sequence (see `handleGifCapture`) rather than appearing
 * here, so the script is purely the in-motion portion.
 */
internal data class GifScrollStep(val scrollPx: Float, val delayMs: Int)

/**
 * Shapes a "realistic user" scroll for `ScrollMode.GIF`: the playback is
 * a brief finger-drag ramp, then one or more fling bursts with geometric
 * velocity decay, with short dwells between flings when the content runs
 * long enough to warrant a second swipe.
 *
 * The returned steps cover only the in-motion portion of the GIF. Callers
 * prepend a hold-start frame (long dwell at position 0) and append a
 * hold-end frame (long dwell on the settled final state) around the
 * walk so the viewer has time to read the top and bottom.
 *
 * Per-frame step sizes are calibrated against GIF *playback* time rather
 * than virtual time: at the default [frameIntervalMs] of 80 ms, a peak
 * fling step of `120 dp` displays as ~1500 dp/s — roughly the velocity
 * of an intentional user swipe on Android. Slower than a blur, faster
 * than a crawl.
 *
 * [contentExtentPxHint] is a best-effort upfront estimate; the runtime
 * still clips each step to the live remaining extent so a LazyList that
 * materialises more items mid-walk won't over-scroll. Tiny content
 * (<= one slow-ramp's worth) collapses the plan to a few gentle steps
 * with no fling.
 */
@Suppress("LongParameterList")
internal fun buildGifScrollScript(
    contentExtentPxHint: Float,
    viewportPx: Float,
    density: Float,
    frameIntervalMs: Int,
    slowRampStepDp: Float = SLOW_RAMP_STEP_DP,
    slowRampFrames: Int = SLOW_RAMP_FRAMES,
    flingPeakDpPerFrame: Float = FLING_PEAK_DP_PER_FRAME,
    flingDecay: Float = FLING_DECAY,
    flingMinStepDp: Float = FLING_MIN_STEP_DP,
    flingMaxDistanceViewports: Float = FLING_MAX_DISTANCE_VIEWPORTS,
    interFlingHoldMs: Int = INTER_FLING_HOLD_MS,
): List<GifScrollStep> {
    if (contentExtentPxHint <= 0f || viewportPx <= 0f || density <= 0f) {
        return emptyList()
    }

    val slowStepPx = slowRampStepDp * density
    val flingPeakPx = flingPeakDpPerFrame * density
    val flingMinPx = flingMinStepDp * density
    val flingCapPx = flingMaxDistanceViewports * viewportPx

    val steps = mutableListOf<GifScrollStep>()
    var scrolled = 0f

    // Slow ramp: mimics a user's finger touching and starting to drag
    // before the fling. At 30 dp/frame × 80 ms cadence that's ~375 dp/s —
    // visibly slower than the fling peak so the acceleration reads as
    // deliberate motion rather than a uniform slide.
    repeat(slowRampFrames) {
        if (scrolled >= contentExtentPxHint) return@repeat
        val step = minOf(slowStepPx, contentExtentPxHint - scrolled)
        if (step <= 0f) return@repeat
        steps += GifScrollStep(step, frameIntervalMs)
        scrolled += step
    }

    // Fling bursts. Each fling decays geometrically from peak down to min
    // step; a per-fling distance cap (`flingMaxDistanceViewports`) cuts
    // long flings off mid-decay so tall content shows as a sequence of
    // swipes rather than one endless deceleration. Stops when content
    // runs out.
    while (scrolled < contentExtentPxHint) {
        var step = flingPeakPx
        var distanceInFling = 0f
        while (step >= flingMinPx &&
            scrolled < contentExtentPxHint &&
            distanceInFling < flingCapPx
        ) {
            val remainingContent = contentExtentPxHint - scrolled
            val remainingCap = flingCapPx - distanceInFling
            val emit = minOf(step, remainingContent, remainingCap)
            if (emit <= 0f) break
            steps += GifScrollStep(emit, frameIntervalMs)
            scrolled += emit
            distanceInFling += emit
            step *= flingDecay
        }
        if (scrolled < contentExtentPxHint) {
            // Short dwell between flings — reads as "user released, about
            // to swipe again" rather than a continuous glide.
            steps += GifScrollStep(0f, interFlingHoldMs)
        }
    }

    return steps
}

// -----------------------------------------------------------------------------
// Calibration constants. Chosen for "feels like a typical user" at the default
// 80 ms GIF frame interval; all tunable in one place. Values in dp so they
// stay consistent across densities.
// -----------------------------------------------------------------------------

internal const val HOLD_START_MS: Int = 1000
internal const val HOLD_END_MS: Int = 1000

internal const val SLOW_RAMP_STEP_DP: Float = 30f
internal const val SLOW_RAMP_FRAMES: Int = 4

// 120 dp/frame at 80 ms cadence ≈ 1500 dp/s peak fling velocity — the
// high end of a deliberate Android swipe.
internal const val FLING_PEAK_DP_PER_FRAME: Float = 120f
internal const val FLING_DECAY: Float = 0.85f
internal const val FLING_MIN_STEP_DP: Float = 12f
internal const val FLING_MAX_DISTANCE_VIEWPORTS: Float = 1.5f

internal const val INTER_FLING_HOLD_MS: Int = 240
