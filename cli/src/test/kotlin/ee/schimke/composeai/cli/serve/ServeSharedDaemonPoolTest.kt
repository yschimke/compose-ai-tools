package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServeSharedDaemonPoolTest {
  private class BlockingHost(
    private val name: String,
    private val entered: CountDownLatch,
    private val release: CountDownLatch,
  ) : ServeHost {
    override val previews: List<ServePreview> = emptyList()
    override val label: String = name
    override val daemonProcessCount: Int = 1
    var closed = false

    override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
      entered.countDown()
      assertTrue(release.await(5, TimeUnit.SECONDS), "timed out waiting to release $name")
      return RenderOutcome.Ok(name.encodeToByteArray())
    }

    override fun subscribeStream(
      previewId: String,
      overrides: PreviewOverrides,
      codec: StreamCodec?,
      maxFps: Int?,
      onUnavailable: ((String) -> Unit)?,
      onFrame: (StreamFrameParams) -> Unit,
    ): StreamHandle? = null

    override fun activeStreamCount(): Int = 0

    override fun close() {
      closed = true
    }
  }

  @Test
  fun `replicas use distinct output roots and remove them when closed`() {
    val descriptorDir = java.nio.file.Files.createTempDirectory("serve-replica-descriptor").toFile()
    val descriptor = File(descriptorDir, "daemon-launch.json").apply { writeText("unused") }
    val outputRoots = mutableListOf<File>()
    val delegates = mutableListOf<BlockingHost>()
    val entered = CountDownLatch(0)
    val release = CountDownLatch(0)

    fun openReplica(): ServeHost =
      openIsolatedSharedDaemonReplica(descriptor) { properties ->
        outputRoots += File(properties.getValue("composeai.render.outputDir")).parentFile
        BlockingHost("replica", entered, release).also(delegates::add)
      }

    val first = openReplica()
    val second = openReplica()
    assertEquals(2, outputRoots.distinct().size)
    assertTrue(outputRoots.all { it.isDirectory })

    first.close()
    second.close()
    assertTrue(delegates.all { it.closed })
    assertTrue(outputRoots.none { it.exists() })
    descriptorDir.deleteRecursively()
    assertFalse(descriptorDir.exists())
  }

  @Test
  fun `five overlapping leased renders use five shared daemon instances`() {
    val entered = CountDownLatch(5)
    val release = CountDownLatch(1)
    val opened = AtomicInteger()
    val replicas = Collections.synchronizedList(mutableListOf<BlockingHost>())
    val primary = BlockingHost("primary", entered, release)
    val pool =
      ServeSharedDaemonPool(primary = primary) {
        BlockingHost("replica-${opened.incrementAndGet()}", entered, release).also(replicas::add)
      }
    val executor = Executors.newFixedThreadPool(5)

    try {
      val results =
        (0 until 5).map { i ->
          executor.submit<RenderOutcome> { pool.render("preview-$i", PreviewOverrides()) }
        }

      assertTrue(entered.await(5, TimeUnit.SECONDS), "all five daemon renders should overlap")
      assertEquals(4, opened.get())
      assertEquals(5, 1 + pool.replicaProcessCount())
      assertEquals(DaemonPoolSnapshot("shared-replicas", 4, 4, 0), pool.snapshot())

      release.countDown()
      results.forEach { assertTrue(it.get(5, TimeUnit.SECONDS) is RenderOutcome.Ok) }
    } finally {
      release.countDown()
      executor.shutdownNow()
      pool.close()
    }

    assertEquals(4, replicas.count { it.closed })
    assertEquals(false, primary.closed, "the composite owns the primary daemon")
  }

  /**
   * The optimizer reports its batch width from this number, and the whole point is that submitting
   * five jobs does not mean five daemons ran. When the seat budget affords no replica the pool
   * queues the jobs onto a host already in circulation — five threads taking turns on one daemon,
   * which a count of jobs submitted would report as five wide. Reading the deployed box's
   * `maxBatchWidth: 5` as "batching works" was exactly that mistake.
   */
  @Test
  fun `peak in-flight counts daemons that rendered at once, not jobs submitted`() {
    // Budget fully spent, so every job must share the primary — see the affordability test below.
    val seats = LiveSeatLimiter(totalPermits = 1)
    val held = requireNotNull(seats.acquire(1))
    val opened = AtomicInteger()
    val primary = InstantHost("primary")
    val pool =
      ServeSharedDaemonPool(primary = primary, capacity = 5, liveSeats = seats) {
        opened.incrementAndGet()
        InstantHost("replica")
      }
    val executor = Executors.newFixedThreadPool(5)
    try {
      assertEquals(0, pool.takePeakInFlight(), "nothing borrowed yet")

      (1..5)
        .map { executor.submit<RenderOutcome> { pool.render("p", PreviewOverrides()) } }
        .forEach { assertTrue(it.get(10, TimeUnit.SECONDS) is RenderOutcome.Ok) }

      assertEquals(0, opened.get(), "precondition: the budget afforded no replica")
      // Five jobs, one daemon. The old job-count reading would have said 5.
      assertEquals(1, pool.takePeakInFlight(), "five jobs served by one daemon is width 1")
      assertEquals(0, pool.takePeakInFlight(), "and the mark resets, so the next batch is its own")
    } finally {
      executor.shutdownNow()
      pool.close()
      held.close()
    }
  }

  /** The other direction: when replicas ARE affordable, the peak sees them. */
  @Test
  fun `peak in-flight rises with genuinely concurrent renders`() {
    val entered = CountDownLatch(3)
    val release = CountDownLatch(1)
    val primary = BlockingHost("primary", entered, release)
    val pool =
      ServeSharedDaemonPool(primary = primary, capacity = 3) {
        BlockingHost("replica", entered, release)
      }
    val executor = Executors.newFixedThreadPool(3)
    try {
      val results =
        (1..3).map { executor.submit<RenderOutcome> { pool.render("p", PreviewOverrides()) } }
      assertTrue(entered.await(5, TimeUnit.SECONDS), "all three should be rendering at once")

      release.countDown()
      results.forEach { assertTrue(it.get(5, TimeUnit.SECONDS) is RenderOutcome.Ok) }
      assertEquals(3, pool.takePeakInFlight(), "three daemons really did render concurrently")
    } finally {
      release.countDown()
      executor.shutdownNow()
      pool.close()
    }
  }

  /** A trivially-completing host, for the tests that don't need to hold a render open. */
  private class InstantHost(private val name: String) : ServeHost {
    override val previews: List<ServePreview> = emptyList()
    override val label: String = name
    override val daemonProcessCount: Int = 1
    var closed = false

    override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome =
      RenderOutcome.Ok(name.encodeToByteArray())

    override fun subscribeStream(
      previewId: String,
      overrides: PreviewOverrides,
      codec: StreamCodec?,
      maxFps: Int?,
      onUnavailable: ((String) -> Unit)?,
      onFrame: (StreamFrameParams) -> Unit,
    ): StreamHandle? = null

    override fun activeStreamCount(): Int = 0

    override fun close() {
      closed = true
    }
  }

  /**
   * Replicas outlive the burst that opened them unless something reaps them; nothing else does,
   * because a catalog session is pinned and [ServeSessionRegistry.suspendIdle] skips those.
   */
  @Test
  fun `reaps idle replicas and never the primary`() {
    var now = 0L
    val primary = InstantHost("primary")
    val replicas = Collections.synchronizedList(mutableListOf<InstantHost>())
    val pool =
      ServeSharedDaemonPool(primary = primary, capacity = 3, clock = { now }) {
        InstantHost("replica-${replicas.size}").also { replicas.add(it) }
      }
    try {
      // Overlapping renders are what open replicas at all; a sequential batch stays on the primary.
      val executor = Executors.newFixedThreadPool(3)
      try {
        (1..3)
          .map { executor.submit<RenderOutcome> { pool.render("p", PreviewOverrides()) } }
          .forEach { it.get(5, TimeUnit.SECONDS) }
      } finally {
        executor.shutdownNow()
      }
      // Whether the race actually opened replicas is timing-dependent; the reap assertions below
      // are written against however many it opened, and the primary must survive either way.

      now = 60_000
      val reaped = pool.reapIdle(idleMillis = 30_000)
      assertEquals(replicas.size, reaped, "every idle replica is closed")
      assertTrue(replicas.all { it.closed })
      assertFalse(primary.closed, "the primary belongs to the catalog host")
      assertEquals(0, pool.replicaProcessCount())

      // The pool still works afterwards, reopening on demand.
      assertTrue(pool.render("p", PreviewOverrides()) is RenderOutcome.Ok)
    } finally {
      pool.close()
    }
  }

  @Test
  fun `does not open a replica the live-seat budget cannot afford`() {
    // Budget fully spent elsewhere — a stream, another catalog's pool. Since a leased replica is
    // charged as FOREGROUND (see the test below), "cannot afford" means literally nothing free:
    // leaving the stream reserve open would leave a replica affordable, and the test would then
    // pass or fail on whether the renders happened to overlap.
    val seats = LiveSeatLimiter(totalPermits = 1)
    val held = requireNotNull(seats.acquire(1))
    assertEquals(0, seats.availablePermits(), "precondition: nothing left to spend")

    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    // Held mid-render, so the overlapping request has nothing to borrow and MUST reach the
    // replica-launch path. With an InstantHost primary the overlap was a race, which is why this
    // used to pass locally and fail on a loaded runner.
    val primary = BlockingHost("primary", entered, release)
    val opened = AtomicInteger()
    val pool =
      ServeSharedDaemonPool(primary = primary, capacity = 3, liveSeats = seats) {
        opened.incrementAndGet()
        InstantHost("replica")
      }
    val executor = Executors.newFixedThreadPool(2)
    try {
      val first = executor.submit<RenderOutcome> { pool.render("p", PreviewOverrides()) }
      assertTrue(entered.await(5, TimeUnit.SECONDS), "the primary should be mid-render")

      val second = executor.submit<RenderOutcome> { pool.render("p", PreviewOverrides()) }
      // It must still be waiting: the only host is out and the budget affords no replica. Had one
      // been spawned this would have completed, so the timeout is the assertion.
      assertTrue(
        runCatching { second.get(1, TimeUnit.SECONDS) }.isFailure,
        "the overlapping render waits for the primary instead of spawning a replica",
      )
      assertEquals(0, opened.get(), "no replica was spawned against an exhausted budget")

      // Both still succeed — the second serialises onto the primary instead of spawning a JVM.
      release.countDown()
      assertTrue(first.get(10, TimeUnit.SECONDS) is RenderOutcome.Ok)
      assertTrue(second.get(10, TimeUnit.SECONDS) is RenderOutcome.Ok)
      assertEquals(0, opened.get(), "still no replica once the burst drains")
    } finally {
      release.countDown()
      executor.shutdownNow()
      pool.close()
      held.close()
    }
  }

  @Test
  fun `a replica whose launch throws hands its seat back`() {
    val total = 4 + LiveSeatLimiter.STREAM_RESERVE
    val seats = LiveSeatLimiter(totalPermits = total)
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    // The primary is held mid-render, so a concurrent request has nothing to borrow and must go
    // down the replica-launch path — which is the one that can strand a ticket.
    val primary = BlockingHost("primary", entered, release)
    val pool =
      ServeSharedDaemonPool(primary = primary, capacity = 3, liveSeats = seats) {
        throw IllegalStateException("daemon launch failed")
      }
    val executor = Executors.newFixedThreadPool(2)
    try {
      val holder = executor.submit<RenderOutcome> { pool.render("p", PreviewOverrides()) }
      assertTrue(entered.await(5, TimeUnit.SECONDS), "the primary should be mid-render")

      val failed = executor.submit<RenderOutcome> { pool.render("p", PreviewOverrides()) }
      val thrown = runCatching { failed.get(5, TimeUnit.SECONDS) }.exceptionOrNull()
      assertTrue(thrown != null, "the launch failure reaches the caller")

      assertEquals(total, seats.availablePermits(), "no seat is stranded on a failed launch")

      release.countDown()
      assertTrue(holder.get(5, TimeUnit.SECONDS) is RenderOutcome.Ok)
    } finally {
      release.countDown()
      executor.shutdownNow()
      pool.close()
    }
  }

  /**
   * A leased burst is a visitor waiting on the grid, so its replicas draw on the FOREGROUND budget
   * — the same class as a stream — rather than the background remainder the prefetcher leaves. The
   * per-preview pool stays on the background path, so the stream reserve still protects streams.
   */
  @Test
  fun `a leased replica may use the seats reserved against background work`() {
    // Exactly the stream reserve free: a background holder (the per-preview pool) would be refused
    // here, but a leased burst is foreground and may take it.
    //
    // `perPreviewReserve = 0` because this case is about the STREAM reserve. With the per-preview
    // slice in play the background holder would be admitted from its own permits — correct, and
    // covered by LiveSeatLimiterPerPreviewReserveTest — which would silently void the precondition
    // below and leave this asserting nothing about the interaction it was written for.
    val seats =
      LiveSeatLimiter(totalPermits = LiveSeatLimiter.STREAM_RESERVE, perPreviewReserve = 0)
    assertNull(seats.acquireBackground(1), "precondition: background cannot touch the reserve")

    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val primary = BlockingHost("primary", entered, release)
    val opened = AtomicInteger()
    val pool =
      ServeSharedDaemonPool(primary = primary, capacity = 2, liveSeats = seats) {
        opened.incrementAndGet()
        InstantHost("replica")
      }
    val executor = Executors.newFixedThreadPool(2)
    try {
      val held = executor.submit<RenderOutcome> { pool.render("p", PreviewOverrides()) }
      assertTrue(entered.await(5, TimeUnit.SECONDS), "primary is mid-render")

      // Overlapping leased render: the primary is out, so this must open a replica.
      val second = executor.submit<RenderOutcome> { pool.render("p", PreviewOverrides()) }
      assertTrue(second.get(10, TimeUnit.SECONDS) is RenderOutcome.Ok)
      assertEquals(1, opened.get(), "the burst widened onto a replica")

      release.countDown()
      assertTrue(held.get(5, TimeUnit.SECONDS) is RenderOutcome.Ok)
    } finally {
      release.countDown()
      executor.shutdownNow()
      pool.close()
    }
  }

  /**
   * Codex review on #3355. `liveSeatRefusals` is the evidence any change to the seat budget rests
   * on, so it must only count callers that actually turned someone away. A leased burst that can't
   * widen still serves its render off a host already in circulation — throttled, not refused.
   */
  @Test
  fun `replica backpressure is not counted as a live-seat refusal`() {
    val seats = LiveSeatLimiter(totalPermits = 1)
    val held = requireNotNull(seats.acquire(1))
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val primary = BlockingHost("primary", entered, release)
    val pool =
      ServeSharedDaemonPool(primary = primary, capacity = 2, liveSeats = seats) {
        InstantHost("replica")
      }
    val executor = Executors.newFixedThreadPool(2)
    try {
      val first = executor.submit<RenderOutcome> { pool.render("p", PreviewOverrides()) }
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      // Wants to widen, cannot (budget spent elsewhere), so it waits for the primary instead.
      val second = executor.submit<RenderOutcome> { pool.render("p", PreviewOverrides()) }

      release.countDown()
      assertTrue(first.get(5, TimeUnit.SECONDS) is RenderOutcome.Ok)
      assertTrue(second.get(10, TimeUnit.SECONDS) is RenderOutcome.Ok, "the render still succeeded")
      assertEquals(0L, seats.refusalCount(), "narrowing a burst is not a refusal")
      assertEquals(0L, seats.unverifiedRefusalCount())
    } finally {
      release.countDown()
      executor.shutdownNow()
      pool.close()
      held.close()
    }
  }
}
