package ee.schimke.composeai.daemon

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Targeted tests for [DeferredDiscoveryQueue]. Pins the queue + watchdog race semantics that matter
 * for the daemon's "discovery never precedes the render notification" invariant: each enqueue
 * starts a watchdog, but the queue is correctness-safe whether the watchdog or an external [drain]
 * caller wins. Tests use a manual scheduler so the race is deterministic.
 */
class DeferredDiscoveryQueueTest {

  /**
   * Manual scheduler: collects every (delayMs, action) pair the queue scheduled. Tests fire the
   * actions explicitly so timing is deterministic; nothing depends on real wall-clock waits.
   */
  private class ManualScheduler {
    private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()

    fun schedule(delayMs: Long, action: () -> Unit) {
      scheduled.add(delayMs to action)
    }

    fun count(): Int = scheduled.size

    fun lastDelay(): Long = scheduled.last().first

    /** Fire every queued action in scheduled order. */
    fun fireAll() {
      val snapshot = scheduled.toList()
      scheduled.clear()
      for ((_, action) in snapshot) action()
    }
  }

  private fun newQueue(
    watchdogMs: Long = 250L,
    scheduler: ManualScheduler = ManualScheduler(),
    runForPath: (String) -> Unit = {},
  ): Pair<DeferredDiscoveryQueue, ManualScheduler> {
    val q = DeferredDiscoveryQueue(watchdogMs, runForPath, scheduler::schedule)
    return q to scheduler
  }

  @Test
  fun `enqueue records the path and schedules a watchdog with the configured delay`() {
    val (q, scheduler) = newQueue(watchdogMs = 250L)
    q.enqueue("/a.kt")
    assertEquals(1, q.pendingCount())
    assertEquals(1, scheduler.count())
    assertEquals(250L, scheduler.lastDelay())
  }

  @Test
  fun `drain runs the per-path action for every queued entry then leaves the queue empty`() {
    val ran = mutableListOf<String>()
    val (q, _) = newQueue(runForPath = { ran.add(it) })
    q.enqueue("/a.kt")
    q.enqueue("/b.kt")
    q.enqueue("/c.kt")
    q.drain()
    assertEquals(listOf("/a.kt", "/b.kt", "/c.kt"), ran)
    assertEquals(0, q.pendingCount())
  }

  @Test
  fun `the watchdog drains the queue when no external drain caller arrived`() {
    val ran = mutableListOf<String>()
    val (q, scheduler) = newQueue(runForPath = { ran.add(it) })
    q.enqueue("/a.kt")
    // Simulate the watchdog firing — no render arrived, no external drain.
    scheduler.fireAll()
    assertEquals(listOf("/a.kt"), ran)
    assertEquals(0, q.pendingCount())
  }

  @Test
  fun `an external drain before the watchdog leaves the watchdog with nothing to do`() {
    val ran = mutableListOf<String>()
    val (q, scheduler) = newQueue(runForPath = { ran.add(it) })
    q.enqueue("/a.kt")
    // The render path arrives first and calls drain() directly.
    q.drain()
    assertEquals(listOf("/a.kt"), ran)
    // Now the watchdog finally fires — must see an empty queue and no-op.
    scheduler.fireAll()
    assertEquals(listOf("/a.kt"), ran)
  }

  @Test
  fun `same path enqueued twice runs twice -- identity dedup is intentionally absent`() {
    // Regression for the documented "two saves of the same file scan twice" behaviour. The queue
    // is bounded by the user's editor cadence; per-scan idempotency is the cheaper invariant.
    val ran = mutableListOf<String>()
    val (q, _) = newQueue(runForPath = { ran.add(it) })
    q.enqueue("/a.kt")
    q.enqueue("/a.kt")
    q.drain()
    assertEquals(listOf("/a.kt", "/a.kt"), ran)
  }

  @Test
  fun `enqueue schedules one watchdog per call (one chance per save to fire)`() {
    val (q, scheduler) = newQueue()
    q.enqueue("/a.kt")
    q.enqueue("/b.kt")
    q.enqueue("/c.kt")
    // Three enqueues => three independent watchdogs. Each is a safety net for its own save; any
    // surviving one drains the whole queue, which is fine.
    assertEquals(3, scheduler.count())
  }

  @Test
  fun `the runForPath thrower does not poison the rest of the drain`() {
    // Per the production scan path's outer try/catch, a single bad path logs and continues.
    // The queue itself should not swallow drains for the survivors.
    val ran = mutableListOf<String>()
    val errors = AtomicInteger(0)
    val (q, _) =
      newQueue(
        runForPath = { path ->
          try {
            if (path == "/bad.kt") error("simulated scan failure")
            ran.add(path)
          } catch (t: Throwable) {
            errors.incrementAndGet()
          }
        }
      )
    q.enqueue("/a.kt")
    q.enqueue("/bad.kt")
    q.enqueue("/c.kt")
    q.drain()
    assertEquals(listOf("/a.kt", "/c.kt"), ran)
    assertEquals(1, errors.get())
  }

  @Test
  fun `drain on an empty queue is a no-op`() {
    val ran = mutableListOf<String>()
    val (q, _) = newQueue(runForPath = { ran.add(it) })
    q.drain()
    assertTrue(ran.isEmpty())
  }

  @Test
  fun `concurrent drain callers each handle a distinct subset of the queue`() {
    // Two threads racing drain() must between them run every queued path exactly once. Mirrors
    // the production race: emitRenderFinished and the watchdog can both call drain() at once.
    val ran = java.util.concurrent.ConcurrentLinkedQueue<String>()
    val (q, _) = newQueue(runForPath = { ran.add(it) })
    val barrier = java.util.concurrent.CountDownLatch(1)
    for (i in 1..200) q.enqueue("/p$i.kt")
    val t1 = Thread {
      barrier.await()
      q.drain()
    }
    val t2 = Thread {
      barrier.await()
      q.drain()
    }
    t1.start()
    t2.start()
    barrier.countDown()
    t1.join()
    t2.join()
    val seen = ran.toList()
    assertEquals(200, seen.size)
    assertEquals(200, seen.toSet().size) // every path ran exactly once
    assertEquals(0, q.pendingCount())
  }
}
