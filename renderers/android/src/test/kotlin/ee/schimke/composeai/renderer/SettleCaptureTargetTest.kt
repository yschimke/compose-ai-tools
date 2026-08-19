package ee.schimke.composeai.renderer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the paused-clock coordinate a `@SettledPreview` capture lands on (issue #4202).
 *
 * The rule is shared with the daemon's `RenderEngine`, so it lives in one function rather than
 * being spelled twice — a live `compose-preview serve` frame that disagreed with the published PNG
 * is the exact class of bug the annotation exists to remove.
 */
class SettleCaptureTargetTest {

  private val captureAdvanceMs = 32L

  @Test
  fun `exact mode captures at the coordinate the annotation names`() {
    // `@SettledPreview(afterMs = 350)` means 350, not 350 + the default advance. The desktop
    // renderer walks to exactly 350, so adding the default here would read a 300ms tween at ~61%
    // on Android against 50% on desktop for identical source.
    assertEquals(350L, settleCaptureTargetMs(afterMs = 350, maxMs = 1000, captureAdvanceMs))
    assertEquals(600L, settleCaptureTargetMs(afterMs = 600, maxMs = 1000, captureAdvanceMs))
  }

  @Test
  fun `auto mode keeps the default advance underneath its bound`() {
    // Auto names no coordinate, only a bound to walk, so the ordinary two-frame advance still
    // applies beneath it.
    assertEquals(1032L, settleCaptureTargetMs(afterMs = 0, maxMs = 1000, captureAdvanceMs))
    assertEquals(832L, settleCaptureTargetMs(afterMs = 0, maxMs = 800, captureAdvanceMs))
  }

  @Test
  fun `an exact window shorter than the default advance is still honoured`() {
    // Discovery floors `maxMs` at a frame but lets a small `afterMs` through, and "exact" has to
    // mean exact in that direction too — otherwise the knob would silently round up.
    assertEquals(16L, settleCaptureTargetMs(afterMs = 16, maxMs = 1000, captureAdvanceMs))
  }
}
