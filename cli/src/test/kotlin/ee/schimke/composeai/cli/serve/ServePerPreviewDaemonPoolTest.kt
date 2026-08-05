package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [ServePerPreviewDaemonPool] opens a per-preview daemon lazily on the first request for its id,
 * reuses it, evicts + closes the least-recently-used one over the cap, and never caches a failed
 * (null) open. It's the idle-bounded fleet behind [ServePerPreviewLiveHost].
 */
class ServePerPreviewDaemonPoolTest {

  private class FakeHost(private val streams: Int = 0) : ServeHost {
    override val previews: List<ServePreview> = emptyList()
    override val label: String = "fake"
    var closed = false

    override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome =
      RenderOutcome.Ok(ByteArray(0))

    override fun subscribeStream(
      previewId: String,
      overrides: PreviewOverrides,
      codec: StreamCodec?,
      maxFps: Int?,
      onUnavailable: ((String) -> Unit)?,
      onFrame: (StreamFrameParams) -> Unit,
    ): StreamHandle? = null

    override fun activeStreamCount(): Int = streams

    override fun close() {
      closed = true
    }
  }

  @Test
  fun `opens on a miss, then reuses the same daemon`() {
    val opens = mutableListOf<String>()
    val pool = ServePerPreviewDaemonPool { id -> FakeHost().also { opens.add(id) } }
    val first = pool.get("A")
    val second = pool.get("A")
    assertSame(first, second, "the same id reuses the pooled daemon")
    assertEquals(listOf("A"), opens, "opened exactly once")
    assertEquals(1, pool.openCount())
  }

  @Test
  fun `a failed (null) open is not cached and retries`() {
    var fail = true
    val opens = mutableListOf<String>()
    val pool = ServePerPreviewDaemonPool { id ->
      opens.add(id)
      if (fail) null else FakeHost()
    }
    assertNull(pool.get("A"), "no daemon when the open fails")
    assertEquals(0, pool.openCount(), "a null open is not cached")
    fail = false
    assertTrue(pool.get("A") != null, "a later request recovers")
    assertEquals(
      listOf("A", "A"),
      opens,
      "it re-attempted the open rather than caching the failure",
    )
  }

  @Test
  fun `evicts and closes the least-recently-used daemon over the cap`() {
    val hosts = mutableMapOf<String, FakeHost>()
    val pool = ServePerPreviewDaemonPool(maxOpen = 2) { id -> FakeHost().also { hosts[id] = it } }
    pool.get("A")
    pool.get("B")
    // Touch A so B is now the least-recently-used.
    pool.get("A")
    // Opening C evicts B (LRU), closing its daemon.
    pool.get("C")
    assertEquals(2, pool.openCount(), "capped at maxOpen")
    assertTrue(hosts.getValue("B").closed, "the LRU daemon was torn down")
    assertEquals(false, hosts.getValue("A").closed, "the recently-used daemon stays open")
    assertEquals(false, hosts.getValue("C").closed)
    // A is still pooled (reused, not reopened); C is pooled.
    val opensAfter = mutableListOf<String>()
    val probe =
      ServePerPreviewDaemonPool(maxOpen = 2) { id -> FakeHost().also { opensAfter.add(id) } }
    probe.get("A")
    assertEquals(listOf("A"), opensAfter)
  }

  @Test
  fun `eviction retains a streaming daemon and closes an idle one instead`() {
    // A is the eldest but is backing a live stream; B is idle. Opening C over the cap must NOT
    // close A out from under its client — it evicts the idle B (older-but-streaming A is retained
    // even though it's the LRU entry, since the pool is only touched once at subscribe).
    val hosts = mutableMapOf<String, FakeHost>()
    var nextStreams = 1 // A streams; B and C don't
    val pool =
      ServePerPreviewDaemonPool(maxOpen = 2) { id ->
        FakeHost(streams = nextStreams).also {
          hosts[id] = it
          nextStreams = 0
        }
      }
    pool.get("A") // streaming
    pool.get("B") // idle
    pool.get("C") // over cap → evict an idle LRU host, not the streaming A
    assertEquals(false, hosts.getValue("A").closed, "the streaming daemon is retained past the cap")
    assertTrue(hosts.getValue("B").closed, "the idle LRU daemon was evicted instead")
    assertEquals(false, hosts.getValue("C").closed)
  }

  @Test
  fun `eviction holds over the cap when every daemon is streaming`() {
    // All held daemons back a live stream, so none is evictable; the pool holds over the cap rather
    // than close a connected client's daemon. The soft cap is bounded by the live-seat cap
    // upstream.
    val hosts = mutableListOf<FakeHost>()
    val pool =
      ServePerPreviewDaemonPool(maxOpen = 1) { _ -> FakeHost(streams = 1).also { hosts.add(it) } }
    pool.get("A")
    pool.get("B")
    assertEquals(2, pool.openCount(), "no streaming daemon is evicted even over the cap")
    assertTrue(hosts.none { it.closed })
  }

  @Test
  fun `activeStreamCount sums the pooled daemons and close tears them all down`() {
    val hosts = mutableListOf<FakeHost>()
    val pool =
      ServePerPreviewDaemonPool(maxOpen = 8) { _ -> FakeHost(streams = 1).also { hosts.add(it) } }
    pool.get("A")
    pool.get("B")
    assertEquals(2, pool.activeStreamCount(), "one stream per pooled daemon")
    pool.close()
    assertEquals(0, pool.openCount())
    assertTrue(hosts.all { it.closed }, "close tears down every held daemon")
    assertNull(pool.get("A"), "a closed pool opens nothing")
  }

  @Test
  fun `snapshot reports resident occupancy without opening another daemon`() {
    val opens = mutableListOf<String>()
    val pool =
      ServePerPreviewDaemonPool(maxOpen = 4) { id ->
        opens += id
        FakeHost(streams = if (id == "A") 1 else 0)
      }
    pool.get("A")
    pool.get("B")

    assertEquals(
      DaemonPoolSnapshot("per-preview", open = 2, maxOpen = 4, activeStreams = 1),
      pool.snapshot(),
    )
    assertEquals(listOf("A", "B"), opens, "snapshot is read-only")
  }

  @Test
  fun `reaps daemons idle past the window, keeping streaming and recently-used ones`() {
    var now = 0L
    val hosts = mutableMapOf<String, FakeHost>()
    val streaming = FakeHost(streams = 1)
    val pool =
      ServePerPreviewDaemonPool(maxOpen = 8, clock = { now }) { id ->
        (if (id == "STREAM") streaming else FakeHost()).also { hosts[id] = it }
      }
    pool.get("OLD")
    pool.get("STREAM")
    now = 5_000
    pool.get("FRESH")

    now = 10_000
    assertEquals(1, pool.reapIdle(idleMillis = 8_000), "only OLD is past the window")

    assertTrue(hosts.getValue("OLD").closed, "the idle daemon's subprocess is torn down")
    assertEquals(false, streaming.closed, "a streaming daemon is never reaped")
    assertEquals(false, hosts.getValue("FRESH").closed, "a recently-used daemon stays")
    assertEquals(2, pool.openCount())
  }

  @Test
  fun `reapIdle is inert when disabled`() {
    var now = 0L
    val host = FakeHost()
    val pool = ServePerPreviewDaemonPool(clock = { now }) { _ -> host }
    pool.get("A")
    now = 1_000_000
    assertEquals(0, pool.reapIdle(idleMillis = 0), "a non-positive window disables reaping")
    assertEquals(false, host.closed)
  }

  /**
   * Pooled daemons are real JVMs and must draw on the same whole-box budget as streams. Refusing is
   * the safe answer: the caller falls back to baked pixels rather than the box spawning a process
   * it can't afford.
   */
  @Test
  fun `a daemon is not opened when the live-seat budget is exhausted`() {
    val seats = LiveSeatLimiter(totalPermits = 2)
    val opens = mutableListOf<String>()
    val pool =
      ServePerPreviewDaemonPool(liveSeats = seats, seatWeight = { 1 }) { id ->
        FakeHost().also { opens.add(id) }
      }
    assertTrue(pool.get("A") != null)
    assertTrue(pool.get("B") != null)
    assertNull(pool.get("C"), "the budget is spent, so no third daemon is spawned")
    assertEquals(listOf("A", "B"), opens, "the refused open never reached the opener")
    assertEquals(0, seats.availablePermits())
  }

  @Test
  fun `reaping and eviction return their seats to the budget`() {
    var now = 0L
    val seats = LiveSeatLimiter(totalPermits = 2)
    val pool =
      ServePerPreviewDaemonPool(maxOpen = 8, clock = { now }, liveSeats = seats) { _ -> FakeHost() }
    pool.get("A")
    pool.get("B")
    assertEquals(0, seats.availablePermits())

    now = 100_000
    assertEquals(2, pool.reapIdle(idleMillis = 1_000))
    assertEquals(2, seats.availablePermits(), "reaped daemons hand their seats back")
    assertTrue(pool.get("C") != null, "the freed budget admits a new daemon")
  }

  @Test
  fun `a throwing open hands its seat back`() {
    val seats = LiveSeatLimiter(totalPermits = 1)
    val pool =
      ServePerPreviewDaemonPool(liveSeats = seats) { _ -> throw IllegalStateException("launch") }
    repeat(3) {
      runCatching { pool.get("A") }
      assertEquals(1, seats.availablePermits(), "a failed launch must not consume the budget")
    }
  }
}
