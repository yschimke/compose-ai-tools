package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import ee.schimke.composeai.daemon.protocol.UiMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServeBroadcastHubTest {

  private fun frame(seq: Long, payload: String?): StreamFrameParams =
    StreamFrameParams(
      frameStreamId = "fs",
      seq = seq,
      ptsMillis = 0,
      widthPx = 2,
      heightPx = 2,
      codec = StreamCodec.PNG,
      payloadBase64 = payload,
    )

  /** A single upstream stream: the hub feeds [emit] back as frames; records input + close. */
  private class FakeUpstream {
    lateinit var emit: (StreamFrameParams) -> Unit
    val inputs = CopyOnWriteArrayList<InteractiveInputKind>()
    val closed = AtomicBoolean(false)
    val handle =
      object : StreamHandle {
        override fun input(
          kind: InteractiveInputKind,
          pixelX: Int?,
          pixelY: Int?,
          pointerId: Int?,
          scrollDeltaY: Float?,
          keyCode: String?,
        ) {
          inputs.add(kind)
        }

        override fun close() {
          closed.set(true)
        }
      }
  }

  /**
   * A [StreamOpener] that hands out a fresh [FakeUpstream] per open (or `null` if not streamable).
   */
  private class FakeOpener(private val streamable: Boolean = true) : StreamOpener {
    val opens = CopyOnWriteArrayList<FakeUpstream>()

    override fun open(
      previewId: String,
      overrides: PreviewOverrides,
      codec: StreamCodec?,
      maxFps: Int?,
      onFrame: (StreamFrameParams) -> Unit,
    ): StreamHandle? {
      if (!streamable) return null
      val up = FakeUpstream()
      up.emit = onFrame
      opens.add(up)
      return up.handle
    }
  }

  @Test
  fun `watchers of the same key share one upstream and both receive frames`() {
    val opener = FakeOpener()
    val hub = ServeBroadcastHub(opener)
    val a = CopyOnWriteArrayList<Long>()
    val b = CopyOnWriteArrayList<Long>()

    assertNotNull(hub.subscribe("p", PreviewOverrides()) { a.add(it.seq) })
    assertNotNull(hub.subscribe("p", PreviewOverrides()) { b.add(it.seq) })

    assertEquals(1, opener.opens.size, "two watchers of one key open a single upstream")
    assertEquals(1, hub.activeStreamCount())

    opener.opens[0].emit(frame(7, "AA"))
    assertEquals(listOf(7L), a)
    assertEquals(listOf(7L), b)
  }

  @Test
  fun `a late joiner is replayed the last painted frame`() {
    val opener = FakeOpener()
    val hub = ServeBroadcastHub(opener)
    assertNotNull(hub.subscribe("p", PreviewOverrides()) {})
    opener.opens[0].emit(frame(3, "AA"))

    val late = CopyOnWriteArrayList<Long>()
    assertNotNull(hub.subscribe("p", PreviewOverrides()) { late.add(it.seq) })
    assertEquals(listOf(3L), late, "the newcomer should see the current picture immediately")
  }

  @Test
  fun `payload-less heartbeats are not replayed to late joiners`() {
    val opener = FakeOpener()
    val hub = ServeBroadcastHub(opener)
    assertNotNull(hub.subscribe("p", PreviewOverrides()) {})
    opener.opens[0].emit(frame(1, null)) // unchanged heartbeat — nothing to paint

    val late = CopyOnWriteArrayList<Long>()
    assertNotNull(hub.subscribe("p", PreviewOverrides()) { late.add(it.seq) })
    assertTrue(late.isEmpty())
  }

  @Test
  fun `closing one of two watchers keeps the upstream, closing the last tears it down`() {
    val opener = FakeOpener()
    val hub = ServeBroadcastHub(opener)
    val h1 = assertNotNull(hub.subscribe("p", PreviewOverrides()) {})
    val h2 = assertNotNull(hub.subscribe("p", PreviewOverrides()) {})
    val up = opener.opens[0]

    h1.close()
    assertFalse(up.closed.get(), "the shared upstream must stay open while a watcher remains")
    assertEquals(1, hub.activeStreamCount())

    h2.close()
    assertTrue(up.closed.get(), "the last watcher out tears the upstream down")
    assertEquals(0, hub.activeStreamCount())
  }

  @Test
  fun `distinct overrides open distinct shared streams`() {
    val opener = FakeOpener()
    val hub = ServeBroadcastHub(opener)
    assertNotNull(hub.subscribe("p", PreviewOverrides()) {})
    assertNotNull(hub.subscribe("p", PreviewOverrides(uiMode = UiMode.DARK)) {})

    assertEquals(2, opener.opens.size)
    assertEquals(2, hub.activeStreamCount())
  }

  @Test
  fun `input from a watcher reaches the shared upstream`() {
    val opener = FakeOpener()
    val hub = ServeBroadcastHub(opener)
    val h = assertNotNull(hub.subscribe("p", PreviewOverrides()) {})

    h.input(InteractiveInputKind.CLICK, pixelX = 1, pixelY = 2)
    assertEquals(listOf(InteractiveInputKind.CLICK), opener.opens[0].inputs)
  }

  @Test
  fun `subscribe returns null and opens nothing when the backend cannot stream`() {
    val hub = ServeBroadcastHub(FakeOpener(streamable = false))
    assertNull(hub.subscribe("p", PreviewOverrides()) {})
    assertEquals(0, hub.activeStreamCount())
  }

  @Test
  fun `after the last watcher leaves a new subscribe opens a fresh upstream`() {
    val opener = FakeOpener()
    val hub = ServeBroadcastHub(opener)
    assertNotNull(hub.subscribe("p", PreviewOverrides()) {}).close()
    assertNotNull(hub.subscribe("p", PreviewOverrides()) {})

    assertEquals(2, opener.opens.size, "a new watcher after teardown opens a fresh upstream")
    assertEquals(1, hub.activeStreamCount())
  }
}
