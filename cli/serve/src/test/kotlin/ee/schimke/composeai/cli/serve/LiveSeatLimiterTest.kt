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
  // Several cases below pass `perPreviewReserve = 0` deliberately. They pin the GENERAL lane's
  // arithmetic against the whole budget, which is what they were written to describe; the slice
  // carved out of that budget by default is covered by LiveSeatLimiterPerPreviewReserveTest.

  @Test
  fun `refusals are counted so seat pressure is visible after the fact`() {
    val limiter = LiveSeatLimiter(totalPermits = 2)
    assertEquals(0L, limiter.refusalCount())

    val held = assertNotNull(limiter.acquire(2))
    // Two refusals while the budget is fully held…
    assertNull(limiter.acquire(1))
    assertNull(limiter.acquire(1))
    assertEquals(2L, limiter.refusalCount())

    // …and the count is monotonic: releasing does not rewind history, which is the point of a
    // counter over the availablePermits gauge beside it.
    held.close()
    assertNotNull(limiter.acquire(1)).close()
    assertEquals(2L, limiter.refusalCount())
  }

  @Test
  fun `an attempt for an unknown session is refused without counting`() {
    val limiter = LiveSeatLimiter(totalPermits = 1)
    val held = assertNotNull(limiter.acquire(1))

    // Seats are reserved before the session is leased, so a request naming a session this box does
    // not have still reaches the budget. It is refused like any other — but it must not move the
    // counter, or anyone could manufacture the evidence a capacity decision rests on.
    assertNull(limiter.acquire(1, verified = false))
    assertEquals(0L, limiter.refusalCount())
    // Not dropped, though: a `--revisions` session is legitimately unknown until its first lease
    // builds it, so the number is kept in its own bucket rather than lost.
    assertEquals(1L, limiter.unverifiedRefusalCount())

    // A real one still counts as demand.
    assertNull(limiter.acquire(1))
    assertEquals(1L, limiter.refusalCount())
    assertEquals(1L, limiter.unverifiedRefusalCount())
    held.close()
  }

  @Test
  fun `an unbounded limiter never refuses and so never counts`() {
    val limiter = LiveSeatLimiter(totalPermits = 0)
    repeat(5) { assertNotNull(limiter.acquire(2)) }
    assertEquals(0L, limiter.refusalCount())
  }

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
    val limiter = LiveSeatLimiter(2, perPreviewReserve = 0)
    val a = assertNotNull(limiter.acquire(1))
    assertNotNull(limiter.acquire(1))
    assertEquals(0, limiter.availablePermits())
    // Third desktop session over budget → refused (caller closes WS 1013).
    assertNull(limiter.acquire(1))
    // Freeing one reopens a seat.
    a.close()
    assertEquals(1, limiter.availablePermits())
    val c = limiter.acquire(1)
    assertNotNull(c)
  }

  @Test
  fun `an Android session costs its heavier weight and blocks a concurrent desktop one`() {
    val limiter = LiveSeatLimiter(2, perPreviewReserve = 0)
    // Weight 2 (Android) consumes the whole budget of 2.
    val android = assertNotNull(limiter.acquire(ServeBundleDaemon.ANDROID_LIVE_SEAT_WEIGHT))
    assertEquals(2, android.permits)
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
    val android = assertNotNull(limiter.acquire(ServeBundleDaemon.ANDROID_LIVE_SEAT_WEIGHT))
    assertEquals(1, android.permits)
    assertEquals(0, limiter.availablePermits())
    // …but it does hold the whole box: nothing else runs concurrently.
    assertNull(limiter.acquire(1))
    android.close()
    assertEquals(1, limiter.availablePermits())
  }

  @Test
  fun `releasing a ticket is idempotent — a double close returns permits only once`() {
    val limiter = LiveSeatLimiter(2, perPreviewReserve = 0)
    val a = assertNotNull(limiter.acquire(1))
    a.close()
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
