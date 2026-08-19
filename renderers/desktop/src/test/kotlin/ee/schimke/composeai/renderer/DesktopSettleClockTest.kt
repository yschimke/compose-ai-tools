package ee.schimke.composeai.renderer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-level pins for the virtual clock a `@SettledPreview` desktop still is captured on. The
 * render-level behaviour is covered by `SettledPreviewRenderTest`; this covers the queue mechanics
 * that decide when the auto walk is allowed to stop early.
 */
class DesktopSettleClockTest {

  @Test
  fun `a pending delay keeps the clock non-quiescent until its deadline`() {
    val clock = DesktopSettleClock()
    val scope = CoroutineScope(clock)
    scope.launch { delay(100) }
    clock.drain()

    assertTrue("a delay that has not come due is still outstanding", clock.hasScheduledWork())
    clock.advanceTo(99)
    assertTrue(clock.hasScheduledWork())
    clock.advanceTo(100)
    assertFalse("the delay came due, so nothing is scheduled", clock.hasScheduledWork())
  }

  @Test
  fun `chained delays each resume at their own deadline`() {
    val clock = DesktopSettleClock()
    val seen = mutableListOf<Long>()
    val scope = CoroutineScope(clock)
    scope.launch {
      delay(50)
      seen += clock.nowMs
      delay(50)
      seen += clock.nowMs
    }
    clock.drain()
    clock.advanceTo(100)

    // A `delay(50)` chained after a `delay(50)` must observe 100ms, not 50.
    assertEquals(listOf(50L, 100L), seen)
  }

  @Test
  fun `a cancelled delay leaves no corpse in the queue`() {
    // A `LaunchedEffect { delay(…) }` whose key changes, or whose composable leaves the
    // composition, cancels the continuation. Without a cancellation handler the entry sat in the
    // queue until its original deadline, so `hasScheduledWork` reported live work that no longer
    // existed and the auto walk paid the full `maxMs` bound instead of stopping when the
    // composition actually went quiet (issue #4202 review).
    val clock = DesktopSettleClock()
    val scope = CoroutineScope(clock)
    val job = scope.launch { delay(5_000) }
    clock.drain()
    assertTrue(clock.hasScheduledWork())

    job.cancel()
    clock.drain()

    assertFalse("a cancelled delay must not hold the settle open", clock.hasScheduledWork())
  }

  @Test
  fun `work dispatched but not yet run counts against quiescence`() {
    // `scene.render(...)` can dispatch a newly-introduced `LaunchedEffect` after the preceding
    // advance already drained. Nothing is scheduled and nothing is invalidated at that instant, so
    // a check that only consulted `hasScheduledWork` would stop and capture the pre-reveal frame.
    val clock = DesktopSettleClock()
    val scope = CoroutineScope(clock)
    scope.launch { /* resumes immediately, but only once drained */ }

    assertFalse("no delay is outstanding", clock.hasScheduledWork())
    assertTrue("but a runnable is still queued", clock.hasPendingWork())

    clock.drain()
    assertFalse("drained, so nothing is owed", clock.hasPendingWork())
  }
}
