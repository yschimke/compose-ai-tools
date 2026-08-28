package ee.schimke.composeai.cli.serve

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cross-catalog admission for the theme optimizer.
 *
 * The measurement this exists to make impossible again: on the deployed box every loaded catalog
 * entered its pass within half a second of the idle gate opening, 15 of them then contended for 8
 * render permits, and the result was 64% of optimizer time spent waiting and 43.5% of the rest
 * spent re-warming daemons that were yielded before they rendered. Bounding the *renders* never
 * addressed it, because a pass holding no render permit is still holding a turn, a warm daemon and
 * a seat.
 */
class ServeBackgroundWorkOptimizerTest {

  private fun work(lanes: Int, now: () -> Long = { 0L }) =
    ServeBackgroundWork(maxConcurrentRenders = 8, clock = now, maxConcurrentOptimizers = lanes)

  @Test
  fun `only as many passes as there are lanes run at once`() {
    val bg = work(lanes = 2)
    val inside = AtomicInteger()
    val peak = AtomicInteger()
    val release = CountDownLatch(1)
    val entered = CountDownLatch(2)

    // Two holders occupy both lanes and stay there. Deterministic on purpose: letting the extra
    // threads race the holders' release tests the scheduler, not the cap.
    val holders =
      (1..2).map { n ->
        Thread {
            bg.withOptimizerSlot("holder-$n", waitMillis = 1_000) {
              val now = inside.incrementAndGet()
              peak.getAndUpdate { max -> maxOf(max, now) }
              entered.countDown()
              release.await(5, TimeUnit.SECONDS)
              inside.decrementAndGet()
              true
            }
          }
          .also(Thread::start)
      }
    assertTrue(entered.await(5, TimeUnit.SECONDS), "both lanes should be admitted immediately")
    assertEquals(2, bg.optimizerAdmissionSnapshot().running)

    // A third catalog, while both lanes are genuinely occupied.
    var thirdRan = false
    val third =
      bg.withOptimizerSlot("third", waitMillis = 20) {
        thirdRan = true
        true
      }
    assertNull(third, "a third pass must not be admitted")
    assertFalse(thirdRan)
    assertEquals(2, peak.get(), "a third pass must never be inside the door")
    assertTrue(bg.optimizerAdmissionSnapshot().refusals >= 1)

    release.countDown()
    holders.forEach { it.join(10_000) }
    assertEquals(0, bg.optimizerAdmissionSnapshot().running)
  }

  @Test
  fun `a refused pass does not run its block`() {
    val bg = work(lanes = 1)
    val held = CountDownLatch(1)
    val release = CountDownLatch(1)
    val holder = Thread {
      bg.withOptimizerSlot("holder", waitMillis = 100) {
        held.countDown()
        release.await(5, TimeUnit.SECONDS)
        true
      }
    }
      .also(Thread::start)
    assertTrue(held.await(5, TimeUnit.SECONDS))

    var ran = false
    val result =
      bg.withOptimizerSlot("refused", waitMillis = 10) {
        ran = true
        true
      }

    assertNull(result, "a refused pass reports null")
    assertFalse(ran, "a refused pass must not do the work anyway")
    release.countDown()
    holder.join(10_000)
  }

  @Test
  fun `the running systems are named, so a stuck pass can be identified`() {
    val bg = work(lanes = 2)
    val held = CountDownLatch(1)
    val release = CountDownLatch(1)
    val t = Thread {
      bg.withOptimizerSlot("m3-catalog", waitMillis = 100) {
        held.countDown()
        release.await(5, TimeUnit.SECONDS)
        true
      }
    }
      .also(Thread::start)
    assertTrue(held.await(5, TimeUnit.SECONDS))

    assertEquals(listOf("m3-catalog"), bg.optimizerAdmissionSnapshot().runningSystems)
    release.countDown()
    t.join(10_000)
    assertEquals(emptyList(), bg.optimizerAdmissionSnapshot().runningSystems)
  }

  @Test
  fun `a slot is released even when the pass throws`() {
    val bg = work(lanes = 1)
    runCatching { bg.withOptimizerSlot("boom", waitMillis = 100) { error("pass blew up") } }
    assertEquals(0, bg.optimizerAdmissionSnapshot().running)
    assertNotNull(
      bg.withOptimizerSlot("next", waitMillis = 100) { true },
      "a thrown pass must not leak its lane",
    )
  }

  @Test
  fun `a pause refuses admission until it lifts`() {
    var now = 1_000L
    val bg = work(lanes = 2, now = { now })

    val until = bg.pauseOptimizers(millis = 60_000, reason = "traffic spike")
    assertEquals(61_000L, until)
    assertTrue(bg.optimizersPaused())
    assertNull(
      bg.withOptimizerSlot("cat", waitMillis = 100) { true },
      "no pass is admitted while paused",
    )

    val snap = bg.optimizerAdmissionSnapshot()
    assertTrue(snap.paused)
    assertEquals(61_000L, snap.pausedUntilEpochMillis)
    assertEquals("traffic spike", snap.pauseReason, "a quiet server should explain itself")

    // It lifts on its own — a pause is a deferral, not a disable.
    now = 61_001L
    assertFalse(bg.optimizersPaused())
    assertNotNull(bg.withOptimizerSlot("cat", waitMillis = 100) { true })
    assertNull(bg.optimizerAdmissionSnapshot().pausedUntilEpochMillis)
  }

  @Test
  fun `resume lifts a pause early`() {
    var now = 0L
    val bg = work(lanes = 1, now = { now })
    bg.pauseOptimizers(millis = 10 * 60_000, reason = "deploy")
    assertTrue(bg.optimizersPaused())

    bg.resumeOptimizers()

    assertFalse(bg.optimizersPaused())
    now = 1L
    assertNotNull(bg.withOptimizerSlot("cat", waitMillis = 100) { true })
    val snap = bg.optimizerAdmissionSnapshot()
    assertFalse(snap.paused)
    assertNull(snap.pauseReason)
  }

  /**
   * Park [system] at the admission door and return once it is genuinely queued.
   *
   * The polling matters: starting the thread proves nothing about whether it has reached the lock,
   * and asserting on priority order before both waiters are registered would test the scheduler
   * rather than the priority rule.
   */
  private fun queueWaiter(
    bg: ServeBackgroundWork,
    system: String,
    expectWaiting: Int,
    ran: MutableList<String>,
    release: CountDownLatch,
  ): Thread {
    val t = Thread {
      bg.withOptimizerSlot(system, waitMillis = 10_000) {
        synchronized(ran) { ran += system }
        release.await(5, TimeUnit.SECONDS)
        true
      }
    }
      .also(Thread::start)
    // Polled on the queue itself, not the `waiting` counter: the two are updated together under the
    // same lock, but only the queue is what the priority assertions below actually read.
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (bg.optimizerAdmissionSnapshot().waitingSystems.size < expectWaiting) {
      check(System.nanoTime() < deadline) { "$system never reached the door" }
      Thread.sleep(5)
    }
    return t
  }

  @Test
  fun `the lane goes to whoever has gone longest without one, not whoever asked first`() {
    // The starvation this exists to prevent: a fair semaphore only orders the callers blocked on it
    // right now, so a catalog refused twenty times arrived at attempt twenty-one with no advantage
    // over one that had just run. On the deployed box that read as `admissions 5, refusals 20` with
    // m3-catalog — the largest queue on the box — never admitted once.
    var now = 0L
    val bg = work(lanes = 1, now = { now })
    val ran = mutableListOf<String>()

    // `recent` runs first and therefore most recently; `stale` ran long ago.
    now = 100
    bg.withOptimizerSlot("stale", waitMillis = 1_000) { true }
    now = 200
    bg.withOptimizerSlot("recent", waitMillis = 1_000) { true }

    now = 300
    val holderRelease = CountDownLatch(1)
    val holding = CountDownLatch(1)
    val holder = Thread {
      bg.withOptimizerSlot("holder", waitMillis = 1_000) {
        holding.countDown()
        holderRelease.await(5, TimeUnit.SECONDS)
        true
      }
    }
      .also(Thread::start)
    assertTrue(holding.await(5, TimeUnit.SECONDS))

    // `recent` queues FIRST, `stale` second — so arrival order and priority order disagree.
    val release = CountDownLatch(1)
    val tRecent = queueWaiter(bg, "recent", expectWaiting = 1, ran = ran, release = release)
    val tStale = queueWaiter(bg, "stale", expectWaiting = 2, ran = ran, release = release)

    assertEquals(
      listOf("stale", "recent"),
      bg.optimizerAdmissionSnapshot().waitingSystems,
      "the door should report who is next, in the order they will be let in",
    )

    holderRelease.countDown()
    holder.join(10_000)
    // One lane, so the first entrant is decided before the second can start.
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (synchronized(ran) { ran.isEmpty() }) {
      check(System.nanoTime() < deadline) { "nobody was admitted" }
      Thread.sleep(5)
    }
    assertEquals(
      "stale",
      synchronized(ran) { ran.first() },
      "the longest-waiting system goes first",
    )

    release.countDown()
    tRecent.join(10_000)
    tStale.join(10_000)
    // `waiting` is a gauge, not a tally. It read as a tally once — the decrement was dropped when
    // admission moved off the semaphore — and a door that reports a permanent queue is worse than
    // one that reports none, because it looks exactly like the starvation this change fixes.
    assertEquals(0, bg.optimizerAdmissionSnapshot().waiting)
    assertEquals(emptyList(), bg.optimizerAdmissionSnapshot().waitingSystems)
  }

  @Test
  fun `a system that has never run outranks every system that has`() {
    // The anti-starvation guarantee stated positively: every catalog gets a lane before any catalog
    // gets a second one. That holds without knowing how much work each has left, which is why there
    // is no size heuristic here.
    var now = 0L
    val bg = work(lanes = 1, now = { now })
    val ran = mutableListOf<String>()

    now = 100
    bg.withOptimizerSlot("veteran", waitMillis = 1_000) { true }

    now = 200
    val holderRelease = CountDownLatch(1)
    val holding = CountDownLatch(1)
    val holder = Thread {
      bg.withOptimizerSlot("holder", waitMillis = 1_000) {
        holding.countDown()
        holderRelease.await(5, TimeUnit.SECONDS)
        true
      }
    }
      .also(Thread::start)
    assertTrue(holding.await(5, TimeUnit.SECONDS))

    val release = CountDownLatch(1)
    val tVeteran = queueWaiter(bg, "veteran", expectWaiting = 1, ran = ran, release = release)
    val tNewcomer = queueWaiter(bg, "newcomer", expectWaiting = 2, ran = ran, release = release)

    assertEquals(listOf("newcomer", "veteran"), bg.optimizerAdmissionSnapshot().waitingSystems)

    holderRelease.countDown()
    holder.join(10_000)
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (synchronized(ran) { ran.isEmpty() }) {
      check(System.nanoTime() < deadline) { "nobody was admitted" }
      Thread.sleep(5)
    }
    assertEquals("newcomer", synchronized(ran) { ran.first() })

    release.countDown()
    tVeteran.join(10_000)
    tNewcomer.join(10_000)
  }

  @Test
  fun `priority admission still never exceeds the lane count`() {
    // Rotation must not be bought by widening the door — the cap is the reason the box stopped
    // thrashing, and a priority queue that admits an extra pass would undo it.
    val bg = work(lanes = 2)
    val inside = AtomicInteger()
    val peak = AtomicInteger()
    val done = CountDownLatch(6)
    val threads =
      (1..6).map { n ->
        Thread {
            bg.withOptimizerSlot("cat-$n", waitMillis = 5_000) {
              val now = inside.incrementAndGet()
              peak.getAndUpdate { max -> maxOf(max, now) }
              Thread.sleep(20)
              inside.decrementAndGet()
              true
            }
            done.countDown()
          }
          .also(Thread::start)
      }
    assertTrue(done.await(20, TimeUnit.SECONDS))
    threads.forEach { it.join(10_000) }
    assertTrue(peak.get() <= 2, "never more than ${2} inside the door, saw ${peak.get()}")
    assertEquals(0, bg.optimizerAdmissionSnapshot().running)
  }

  @Test
  fun `admission counters distinguish work done from work refused`() {
    // The pair that tells an operator the cap is working rather than wedged: refusals climbing
    // while admissions also climb is throughput; refusals climbing alone is starvation.
    val bg = work(lanes = 1)
    assertNotNull(bg.withOptimizerSlot("a", waitMillis = 100) { true })
    assertNotNull(bg.withOptimizerSlot("b", waitMillis = 100) { true })
    bg.pauseOptimizers(millis = 60_000, reason = "x")
    assertNull(bg.withOptimizerSlot("c", waitMillis = 10) { true })

    val snap = bg.optimizerAdmissionSnapshot()
    assertEquals(2, snap.admissions)
    assertEquals(1, snap.refusals)
    assertEquals(1, snap.lanes)
  }
}
