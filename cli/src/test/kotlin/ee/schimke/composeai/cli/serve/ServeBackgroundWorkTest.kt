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

  @Test
  fun `the render permit admits one background render at a time`() {
    val work = ServeBackgroundWork()
    val inFlight = AtomicInteger()
    val peak = AtomicInteger()
    val done = CountDownLatch(4)
    val pool = Executors.newFixedThreadPool(4)
    try {
      repeat(4) {
        pool.execute {
          work.withRenderPermit {
            peak.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
            Thread.sleep(25)
            inFlight.decrementAndGet()
          }
          done.countDown()
        }
      }
      assertTrue(done.await(10, TimeUnit.SECONDS), "background renders did not drain")
    } finally {
      pool.shutdownNow()
    }

    assertEquals(1, peak.get())
  }

  @Test
  fun `an interrupted wait for the permit reports stop rather than rendering anyway`() {
    val work = ServeBackgroundWork()
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
}
