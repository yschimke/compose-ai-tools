package ee.schimke.composeai.cli.serve

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ServeBackgroundWork] is the admission gate the catalogs' idle theme optimizer reads. Both halves
 * exist because of the deployed public server: the *loading* gate keeps the optimizer from
 * competing with catalog startup (which is when a live bundle's daemon is stood up, and a starved
 * daemon start degrades that catalog to baked PNGs for the life of the process), and the render
 * permit keeps every catalog's optimizer from becoming runnable at the same instant once loading
 * ends.
 */
class ServeBackgroundWorkTest {

  @Test
  fun `a server is loading from the moment a startup pass is expected until it finishes`() {
    val work = ServeBackgroundWork()
    assertFalse(work.catalogsLoading, "a server with no catalogs is never loading")

    work.expectInitialCatalogLoad()
    assertTrue(work.catalogsLoading)

    work.initialCatalogLoadFinished()
    assertFalse(work.catalogsLoading)
  }

  @Test
  fun `a refresh or admin registration counts as loading for its duration`() {
    val work = ServeBackgroundWork()
    work.initialCatalogLoadFinished()

    val seenInside = work.whileLoadingCatalog { work.catalogsLoading }

    assertTrue(seenInside, "the load itself must read as busy")
    assertFalse(work.catalogsLoading, "and stop reading as busy once it returns")
  }

  @Test
  fun `a load that throws still releases the gate`() {
    val work = ServeBackgroundWork()
    runCatching { work.whileLoadingCatalog { error("branch fetch failed") } }
    assertFalse(work.catalogsLoading)
  }

  @Test
  fun `the idle clock starts fresh when catalog loading finishes`() {
    var now = 1_000L
    val work = ServeBackgroundWork(clock = { now })
    val clock = work.idleClock { 90_000L }

    work.expectInitialCatalogLoad()
    // Null is how the optimizer already spells "traffic is active" — startup borrows it, because
    // the registry's own clock counts request traffic and sees a booting server as perfectly idle.
    assertNull(clock())

    work.initialCatalogLoadFinished()
    assertEquals(0L, clock())
    now += 59_999
    assertEquals(59_999L, clock())
    now++
    assertEquals(60_000L, clock())
  }

  @Test
  fun `a catalog refresh resets the idle clock after it returns`() {
    var now = 100_000L
    val work = ServeBackgroundWork(clock = { now })
    val clock = work.idleClock { 90_000L }

    work.whileLoadingCatalog { now += 5_000 }

    assertEquals(0L, clock())
    now += 60_000
    assertEquals(60_000L, clock())
  }

  /**
   * The cap is what matters, not any particular value — so this asserts the permit admits exactly
   * as many as it was built for, at 1 (the old default, still the right shape for a small box) and
   * at a wider setting. The default itself moved from 1 to 3 because one permit shared by every
   * catalog was measured as 74.3% of the prefetcher's active time on the deployed server.
   */
  @Test
  fun `the render permit admits exactly as many background renders as it was built for`() {
    for (cap in listOf(1, 3)) {
      val work = ServeBackgroundWork(maxConcurrentRenders = cap)
      val inFlight = AtomicInteger()
      val peak = AtomicInteger()
      val started = CountDownLatch(cap)
      val done = CountDownLatch(6)
      val pool = Executors.newFixedThreadPool(6)
      try {
        repeat(6) {
          pool.execute {
            work.withRenderPermit {
              peak.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
              started.countDown()
              Thread.sleep(25)
              inFlight.decrementAndGet()
            }
            done.countDown()
          }
        }
        // Reaching the cap is asserted, not just staying under it: a permit stuck at 1 would keep
        // `peak` low and pass a ceiling-only check while starving exactly as it did in production.
        assertTrue(started.await(10, TimeUnit.SECONDS), "cap $cap was never reached")
        assertTrue(done.await(10, TimeUnit.SECONDS), "background renders did not drain")
      } finally {
        pool.shutdownNow()
      }

      assertEquals(cap, peak.get(), "cap $cap admitted the wrong number")
    }
  }

  @Test
  fun `overlapping generations of one system are both counted as running`() {
    val work = ServeBackgroundWork(maxConcurrentOptimizers = 2)
    val entered = CountDownLatch(2)
    val release = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(2)
    try {
      repeat(2) {
        pool.execute {
          work.withOptimizerSlot("catalog", 5_000) {
            entered.countDown()
            release.await()
          }
        }
      }
      assertTrue(entered.await(5, TimeUnit.SECONDS))

      val snapshot = work.optimizerAdmissionSnapshot()
      assertEquals(2, snapshot.running)
      assertEquals(listOf("catalog"), snapshot.runningSystems)
    } finally {
      release.countDown()
      pool.shutdownNow()
    }
  }

  /**
   * Codex review on #3399. Widening the lane is only safe because the seat budget refuses the
   * daemons it licenses: a lane of 3, each catalog submitting up to five parallel renders, is up to
   * fifteen concurrent daemons. On `--live-seats 0` — the CLI default for a local box — nothing
   * refuses them, so the widening that helps a public server would spawn fifteen JVMs on a laptop.
   */
  @Test
  fun `the render lane widens only where a seat budget can refuse the daemons it licenses`() {
    assertEquals(
      1,
      ServeBackgroundWork.renderLaneFor(LiveSeatLimiter(totalPermits = 0)),
      "an unbounded budget bounds nothing, so the lane must stay conservative",
    )
    assertEquals(1, ServeBackgroundWork.renderLaneFor(null), "no budget at all is the same case")

    // The deployed box: 8 permits, Android weight 2, stream reserve held back.
    assertEquals(3, ServeBackgroundWork.renderLaneFor(LiveSeatLimiter(totalPermits = 8)))
    // A budget that can only afford one heavy daemon beyond the reserve gets one lane, not three.
    assertEquals(1, ServeBackgroundWork.renderLaneFor(LiveSeatLimiter(totalPermits = 4)))
    // And the derivation is capped rather than growing without limit on a large box.
    assertEquals(
      ServeBackgroundWork.MAX_DERIVED_CONCURRENT_RENDERS,
      ServeBackgroundWork.renderLaneFor(LiveSeatLimiter(totalPermits = 64)),
    )
    // **The cap is reached at eight permits**, which is why `--background-renders` exists. Every
    // budget from here up derives the same lane, so on a box with more seats than that this lane
    // stops widening while everything else scales with the budget. Measured on preview.coo.ee,
    // whose container is allowed 24 GB: the seat budget went 8 -> 12 and the lane stayed 3.
    assertEquals(
      ServeBackgroundWork.renderLaneFor(LiveSeatLimiter(totalPermits = 8)),
      ServeBackgroundWork.renderLaneFor(LiveSeatLimiter(totalPermits = 12)),
      "8 seats already saturates the derivation, so 12 buys nothing without an explicit override",
    )
  }

  @Test
  fun `an interrupted wait for the permit reports stop rather than rendering anyway`() {
    // Cap of 1 so the second caller is guaranteed to be *waiting* when it is interrupted — that is
    // this test's subject. With the wider default it would sail through without ever blocking.
    val work = ServeBackgroundWork(maxConcurrentRenders = 1)
    val held = CountDownLatch(1)
    val release = CountDownLatch(1)
    val holder = Thread {
      work.withRenderPermit {
        held.countDown()
        release.await()
      }
    }
    holder.start()
    assertTrue(held.await(5, TimeUnit.SECONDS))

    var rendered = false
    var outcome: String? = "unset"
    var interrupted = false
    val waiter = Thread {
      outcome = work.withRenderPermit { rendered = true }?.let { "ran" }
      interrupted = Thread.currentThread().isInterrupted
    }
    waiter.start()
    // Give the waiter a moment to actually block on the permit, then interrupt it the way shutdown
    // does.
    Thread.sleep(100)
    waiter.interrupt()
    waiter.join(5_000)

    assertFalse(rendered, "an interrupted optimizer must not start another render")
    assertNull(outcome)
    assertTrue(interrupted, "the interrupt is left set so the caller's loop also exits")

    release.countDown()
    holder.join(5_000)
  }

  /**
   * The gate's *input*, published so a closed gate is diagnosable from `/status.json`.
   *
   * A box whose optimizer never ran reported the same all-zero row as one with nothing left to do,
   * because every counter described what a pass did after it was granted a turn and none described
   * whether a turn was ever available. These three fields are that missing read.
   */
  @Test
  fun `the admission snapshot publishes the idle clock the quiet gate reads`() {
    var now = 100_000L
    var requestIdle: Long? = 90_000L
    val work = ServeBackgroundWork(clock = { now })
    work.initialCatalogLoadFinished()
    work.idleClock { requestIdle }

    val running = work.optimizerAdmissionSnapshot()
    assertEquals(0L, running.serverIdleMillis, "the load just finished, so the catalog clock is 0")
    assertNull(running.idleBlockedBy)
    assertEquals(
      ServeCatalogLiveHost.themeOptimizationIdleMillisDefault(),
      running.idleThresholdMillis,
      "the threshold travels with the reading, so `closed` names what it was compared against",
    )

    now += 120_000
    assertEquals(90_000L, work.optimizerAdmissionSnapshot().serverIdleMillis)
  }

  @Test
  fun `a held session lease is named as the reason the idle clock reads busy`() {
    val work = ServeBackgroundWork(clock = { 100_000L })
    work.initialCatalogLoadFinished()
    // Null from the registry means "a session holds an open lease" — the state that stands the
    // optimizer down for the life of the process when the lease is leaked.
    work.idleClock { null }

    val snapshot = work.optimizerAdmissionSnapshot()

    assertNull(snapshot.serverIdleMillis)
    assertEquals(ServeBackgroundWork.IDLE_BLOCKED_BY_SESSION_LEASE, snapshot.idleBlockedBy)
  }

  @Test
  fun `catalog loading is distinguished from a lease as the reason for a busy clock`() {
    val work = ServeBackgroundWork(clock = { 100_000L })
    work.idleClock { 90_000L }
    work.expectInitialCatalogLoad()

    val loading = work.optimizerAdmissionSnapshot()
    assertNull(loading.serverIdleMillis)
    assertEquals(
      ServeBackgroundWork.IDLE_BLOCKED_BY_CATALOG_LOAD,
      loading.idleBlockedBy,
      "the two have opposite fixes: one is a bug, the other is the gate working",
    )

    work.initialCatalogLoadFinished()
    assertNull(work.optimizerAdmissionSnapshot().idleBlockedBy)
  }

  @Test
  fun `a server whose hosts never asked for a clock reports no idle reading at all`() {
    val work = ServeBackgroundWork(clock = { 100_000L })

    val snapshot = work.optimizerAdmissionSnapshot()

    assertNull(snapshot.serverIdleMillis)
    assertNull(snapshot.idleBlockedBy, "no clock is not the same as a clock reading busy")
  }
}
