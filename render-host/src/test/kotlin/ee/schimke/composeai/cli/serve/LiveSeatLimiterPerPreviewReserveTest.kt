package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The per-preview lane's guaranteed slice of the seat budget.
 *
 * Observed on preview.coo.ee before it existed: eight resident catalog daemons held all 8 permits
 * with `activeStreams: 0`, so every `acquireBackground` was refused,
 * [ServePerPreviewDaemonPool.get] returned null, and `ServeCatalogLiveHost.renderInternal` turned
 * that `NotFound` into `Busy`. All 21 of meshcore-mobile's supplement-module previews answered `503
 * render busy; retry shortly` on 39 of 39 attempts, and the catalog's theme prefetch pinned at
 * 288/372 for the life of the server. `liveSeatsAvailable: 0` and `liveSeatRefusals: 0` were the
 * only symptoms visible from outside.
 *
 * A burst replica can narrow onto the primary when it is refused; a preview whose only live lane is
 * its own per-preview bundle has no such fallback, which is why this lane — and only this lane —
 * gets a floor.
 */
class LiveSeatLimiterPerPreviewReserveTest {

  @Test
  fun `a saturated general lane cannot starve the per-preview slice`() {
    val limiter = LiveSeatLimiter(totalPermits = 8, perPreviewReserve = 1)
    // Drain the general lane exactly as the resident catalog daemons did.
    val held = (1..7).mapNotNull { limiter.acquire(1) }
    assertEquals(7, held.size, "the general lane is the budget minus the slice")
    assertNull(limiter.acquire(1), "and is now full")

    assertNotNull(
      limiter.acquireBackground(1),
      "the per-preview lane still opens — this is the whole point",
    )
  }

  @Test
  fun `the slice is not available to the general lane`() {
    val limiter = LiveSeatLimiter(totalPermits = 4, perPreviewReserve = 1)
    val held = (1..3).mapNotNull { limiter.acquire(1) }
    assertEquals(3, held.size)
    assertNull(limiter.acquire(1), "the reserved permit is never handed to a stream or replica")
  }

  @Test
  fun `a reserved permit returns to the reserve, not the general pool`() {
    // Otherwise the slice leaks into the general lane on the first eviction and the starvation
    // reappears — silently, and only after the box has been up a while.
    val limiter = LiveSeatLimiter(totalPermits = 4, perPreviewReserve = 1)
    (1..3).mapNotNull { limiter.acquire(1) }
    val background = assertNotNull(limiter.acquireBackground(1))

    background.close()

    assertNull(limiter.acquire(1), "the returned permit did not become general capacity")
    assertNotNull(limiter.acquireBackground(1), "it went back to the lane it came from")
  }

  @Test
  fun `background falls back to the general lane while it has stream headroom`() {
    // The slice is a floor, not a ceiling: a second concurrent per-preview daemon competes for the
    // general pool exactly as before, and still leaves room for an interactive stream.
    val limiter = LiveSeatLimiter(totalPermits = 8, perPreviewReserve = 1)
    val first = assertNotNull(limiter.acquireBackground(1))
    val second = assertNotNull(limiter.acquireBackground(1), "general lane has plenty free")
    assertTrue(first.permits == 1 && second.permits == 1)
  }

  @Test
  fun `a heavy backend is coerced to the general lane, not the whole budget`() {
    // Coercing to totalPermits would ask for more than the general semaphore can ever hold and
    // refuse the backend forever — the exact deadlock the coercion exists to prevent.
    val limiter = LiveSeatLimiter(totalPermits = 4, perPreviewReserve = 1)
    assertNotNull(limiter.acquire(99), "a backend heavier than the budget still runs alone")
  }

  @Test
  fun `availablePermits sums both lanes so status stays comparable to the total`() {
    val limiter = LiveSeatLimiter(totalPermits = 4, perPreviewReserve = 1)
    assertEquals(4, limiter.availablePermits())
    assertEquals(1, limiter.perPreviewPermitsAvailable())

    (1..3).mapNotNull { limiter.acquire(1) }

    assertEquals(1, limiter.availablePermits(), "the free slice is not reported as capacity gone")
    assertEquals(1, limiter.perPreviewPermitsAvailable())
  }

  @Test
  fun `a budget too small to afford the slice gets none, and cannot over-admit`() {
    // The `--live-seats 2` case. A general lane of 1 would coerce an Android stream (weight 2) down
    // to a single permit, and a per-preview daemon could then take the reserved one — three
    // permits' worth of processes on a box budgeted for two, defeating the OOM protection that is
    // the entire reason the budget is weighted. So a box this small keeps its old behaviour.
    val limiter = LiveSeatLimiter(totalPermits = 2, perPreviewReserve = 1)
    assertEquals(0, limiter.perPreviewPermits, "no slice is carved out of a budget this small")

    val android = assertNotNull(limiter.acquire(ServeBundleDaemon.ANDROID_LIVE_SEAT_WEIGHT))
    assertEquals(2, android.permits, "the heavy backend is charged its true weight, not clamped")
    assertEquals(0, limiter.availablePermits(), "and it holds the whole box")
    assertNull(limiter.acquireBackground(1), "nothing else may run alongside it")
  }

  @Test
  fun `the slice never eats the headroom a heaviest-backend stream needs`() {
    // The invariant that makes the clamp above safe: whenever a slice exists, the general lane can
    // still hold ANDROID_LIVE_SEAT_WEIGHT at full weight, so `acquire` never under-charges.
    for (total in 1..12) {
      val limiter = LiveSeatLimiter(totalPermits = total, perPreviewReserve = 4)
      val general = total - limiter.perPreviewPermits
      if (limiter.perPreviewPermits > 0) {
        assertTrue(
          general >= ServeBundleDaemon.ANDROID_LIVE_SEAT_WEIGHT,
          "budget $total left a general lane of $general, too small for the heaviest backend",
        )
      }
      assertTrue(limiter.perPreviewPermits >= 0 && general >= 0)
    }
  }

  @Test
  fun `an oversized reserve is clamped, and status reports what is actually held`() {
    // `/status` must publish the EFFECTIVE slice. Reporting the requested value would state a
    // reserve the limiter does not hold — and a diagnostic added to make starvation visible must
    // not itself be able to lie.
    val limiter = LiveSeatLimiter(totalPermits = 8, perPreviewReserve = 99)
    assertEquals(6, limiter.perPreviewPermits, "clamped to the budget minus the stream reserve")
    assertEquals(99, limiter.perPreviewReserve, "the requested value is kept, but is not the truth")
    assertEquals(6, limiter.perPreviewPermitsAvailable())
    assertEquals(8, limiter.availablePermits())
  }

  @Test
  fun `an unbounded limiter is unaffected`() {
    val limiter = LiveSeatLimiter(totalPermits = 0, perPreviewReserve = 1)
    assertTrue(limiter.unbounded)
    assertNotNull(limiter.acquireBackground(1))
    assertNotNull(limiter.acquire(99))
  }
}
