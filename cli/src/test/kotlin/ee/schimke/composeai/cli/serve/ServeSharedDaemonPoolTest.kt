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
    val seats = LiveSeatLimiter(totalPermits = 1)
    // Spend the whole budget elsewhere — a stream, another catalog's pool, anything.
    val held = requireNotNull(seats.acquire(1))
    val primary = InstantHost("primary")
    val opened = AtomicInteger()
    val pool =
      ServeSharedDaemonPool(primary = primary, capacity = 3, liveSeats = seats) {
        opened.incrementAndGet()
        InstantHost("replica")
      }
    try {
      val executor = Executors.newFixedThreadPool(3)
      try {
        val results =
          (1..3).map { executor.submit<RenderOutcome> { pool.render("p", PreviewOverrides()) } }
        // Every render still succeeds — they serialise onto the primary instead of spawning JVMs.
        results.forEach { assertTrue(it.get(10, TimeUnit.SECONDS) is RenderOutcome.Ok) }
      } finally {
        executor.shutdownNow()
      }
      assertEquals(0, opened.get(), "no replica was spawned against an exhausted budget")
    } finally {
      pool.close()
      held.close()
    }
  }
}
