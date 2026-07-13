package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the live-seat permit budget ([LiveSeatLimiter]) — the gate that lets a heavy
 * Android daemon cost more of the box than a cheap desktop CMP one, replacing the flat seat count
 * that let one heavy catalog starve the rest.
 */
class LiveSeatLimiterTest {

  @Test
  fun `zero budget is unbounded — every acquire is a free ticket`() {
    val limiter = LiveSeatLimiter(0)
    assertTrue(limiter.unbounded)
    // Even an absurd weight succeeds and holds no permits.
    val ticket = limiter.acquire(999)
    assertNotNull(ticket)
    assertEquals(0, ticket.permits)
    assertEquals(Int.MAX_VALUE, limiter.availablePermits())
    ticket.close()
  }

  @Test
  fun `a static (zero-weight) session takes no permit`() {
    val limiter = LiveSeatLimiter(2)
    val ticket = limiter.acquire(0)
    assertNotNull(ticket)
    assertEquals(0, ticket.permits)
    // Budget untouched — two daemon-backed sessions can still run alongside it.
    assertEquals(2, limiter.availablePermits())
  }

  @Test
  fun `desktop-weight sessions fill the budget then the next is refused`() {
    val limiter = LiveSeatLimiter(2)
    val a = limiter.acquire(1)
    val b = limiter.acquire(1)
    assertNotNull(a)
    assertNotNull(b)
    assertEquals(0, limiter.availablePermits())
    // Third desktop session over budget → refused (caller closes WS 1013).
    assertNull(limiter.acquire(1))
    // Freeing one reopens a seat.
    a!!.close()
    assertEquals(1, limiter.availablePermits())
    val c = limiter.acquire(1)
    assertNotNull(c)
  }

  @Test
  fun `an Android session costs its heavier weight and blocks a concurrent desktop one`() {
    val limiter = LiveSeatLimiter(2)
    // Weight 2 (Android) consumes the whole budget of 2.
    val android = limiter.acquire(ServeBundleDaemon.ANDROID_LIVE_SEAT_WEIGHT)
    assertNotNull(android)
    assertEquals(2, android!!.permits)
    assertEquals(0, limiter.availablePermits())
    // A cheap desktop session can't squeeze in while the heavy one holds both permits.
    assertNull(limiter.acquire(1))
    // Once the Android daemon releases, the desktop session gets a seat.
    android.close()
    assertEquals(2, limiter.availablePermits())
    assertNotNull(limiter.acquire(1))
  }

  @Test
  fun `a weight heavier than the whole budget is coerced so it can still run alone`() {
    // Budget 1, Android weight 2: without coercion the Android catalog would be permanently refused
    // (its weight exceeds the ceiling). Coerce to the budget so it runs solo instead of
    // deadlocking.
    val limiter = LiveSeatLimiter(1)
    val android = limiter.acquire(ServeBundleDaemon.ANDROID_LIVE_SEAT_WEIGHT)
    assertNotNull(android)
    assertEquals(1, android!!.permits)
    assertEquals(0, limiter.availablePermits())
    // …but it does hold the whole box: nothing else runs concurrently.
    assertNull(limiter.acquire(1))
    android.close()
    assertEquals(1, limiter.availablePermits())
  }

  @Test
  fun `releasing a ticket is idempotent — a double close returns permits only once`() {
    val limiter = LiveSeatLimiter(2)
    val a = limiter.acquire(1)
    assertNotNull(a)
    a!!.close()
    a.close() // second close is a no-op
    assertEquals(2, limiter.availablePermits())
    // Sanity: the budget didn't inflate past its total.
    assertNotNull(limiter.acquire(1))
    assertNotNull(limiter.acquire(1))
    assertNull(limiter.acquire(1))
  }

  @Test
  fun `a bounded limiter reports itself bounded`() {
    assertFalse(LiveSeatLimiter(2).unbounded)
    assertTrue(LiveSeatLimiter(0).unbounded)
    assertTrue(LiveSeatLimiter(-3).unbounded)
  }
}
