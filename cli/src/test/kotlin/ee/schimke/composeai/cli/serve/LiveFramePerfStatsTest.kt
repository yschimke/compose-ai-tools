package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The live lane's answer to "what fps does a live session actually achieve?" (#4281). Everything
 * here is measured off a fake clock, because the property under test is the arithmetic — a real
 * clock would make the percentiles a timing race.
 */
class LiveFramePerfStatsTest {

  private var now = 0L
  private val stats = LiveFramePerfStats(clock = { now })

  @Test
  fun `a server nobody has streamed from reports nothing`() {
    assertNull(stats.snapshot())
    assertNull(stats.snapshot("m3-catalog"))
  }

  @Test
  fun `painted frames and heartbeats are counted apart`() {
    val socket = stats.openSocket("m3-catalog", "button-filled")
    socket.recordFrame(6_672)
    now += 250
    socket.recordFrame(6_600)
    socket.recordHeartbeat()
    socket.recordHeartbeat()

    val snap = assertNotNull(stats.snapshot())
    assertEquals(2L, snap.frames)
    assertEquals(2L, snap.heartbeats)
    assertEquals(13_272L, snap.payloadBytes)
    assertEquals(6_636L, snap.avgPayloadBytes)
    assertEquals(6_672L, snap.maxPayloadBytes)
  }

  @Test
  fun `achieved fps comes from the median gap between painted frames`() {
    val socket = stats.openSocket("m3-catalog", "button-filled")
    // The pre-#4274 live loop: a flat 250 ms tick, which is 4 fps however it is described.
    repeat(9) {
      socket.recordFrame(1_000)
      now += 250
    }

    val snap = assertNotNull(stats.snapshot())
    assertEquals(250L, snap.p50IntervalMs)
    assertEquals(4.0, snap.achievedFps)
    assertEquals(8, snap.intervalWindow, "n frames yield n-1 intervals")
  }

  @Test
  fun `an idle backoff shows up as a widening tail, not a lower median`() {
    // #4283's geometric idle backoff: the cadence holds while pixels move and stretches afterwards.
    // p50 must keep describing the interactive cadence, and the max must show the backoff.
    val socket = stats.openSocket("m3-catalog", "button-filled")
    listOf(16L, 16L, 16L, 16L, 250L, 500L, 1_000L, 2_000L).forEach { gap ->
      socket.recordFrame(1_000)
      now += gap
    }
    socket.recordFrame(1_000)

    val snap = assertNotNull(stats.snapshot())
    assertEquals(16L, snap.minIntervalMs)
    assertEquals(2_000L, snap.maxIntervalMs)
    assertEquals(16L, snap.p50IntervalMs)
    assertEquals(62.5, snap.achievedFps)
  }

  @Test
  fun `sockets are scoped per catalog`() {
    stats.openSocket("m3-catalog", "button-filled").recordFrame(100)
    stats.openSocket("wear-m3-catalog", "chip").recordFrame(200)

    assertEquals(1L, assertNotNull(stats.snapshot("m3-catalog")).frames)
    assertEquals(100L, assertNotNull(stats.snapshot("m3-catalog")).payloadBytes)
    assertEquals(200L, assertNotNull(stats.snapshot("wear-m3-catalog")).payloadBytes)
    assertEquals(2L, assertNotNull(stats.snapshot()).frames)
    assertNull(stats.snapshot("cmp-catalog"), "a catalog with no live socket must not be invented")
  }

  @Test
  fun `closing a socket keeps its totals and drops it from the open list`() {
    val socket = stats.openSocket("m3-catalog", "button-filled")
    socket.recordFrame(500)
    now += 100
    assertEquals(1, assertNotNull(stats.snapshot()).openSockets)

    socket.close()
    socket.close() // idempotent

    val snap = assertNotNull(stats.snapshot())
    assertEquals(0, snap.openSockets)
    assertEquals(1L, snap.socketsOpened)
    assertEquals(1L, snap.frames)
    assertTrue(snap.streams.isEmpty())
  }

  @Test
  fun `an open socket reports what it is watching now, across a switch`() {
    val socket = stats.openSocket("m3-catalog", "button-filled")
    socket.recordFrame(500)
    now += 40
    socket.recordFrame(500)
    now += 60
    socket.watching("toolbar-horizontalfloating")

    val stream = assertNotNull(stats.snapshot()).streams.single()
    assertEquals("m3-catalog", stream.system)
    assertEquals("toolbar-horizontalfloating", stream.previewId)
    assertEquals(2L, stream.frames)
    assertEquals(40L, stream.lastIntervalMs)
  }
}
