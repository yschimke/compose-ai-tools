package ee.schimke.composeai.renderer.xr.client

import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

class XrSessionManagerTest {

  private class FakeServer : XrRenderServerHandle {
    val seq = AtomicLong(0)
    var closed = false
    override val capabilities: JsonObject = buildJsonObject {}

    private fun frame() = StreamFrame(seq.incrementAndGet(), 64, 48, "png", "data")

    override fun render(scene: JsonElement, sceneDir: String?, environment: String?) = frame()

    override fun updatePanels(panels: JsonArray) = frame()

    override fun close() {
      closed = true
    }
  }

  private val scene: JsonElement = buildJsonObject {}

  @Test
  fun openRendersFirstFrameAndUpdatePanelsAdvances() {
    val server = FakeServer()
    val manager = XrSessionManager(factory = { _, _ -> server })

    val first = manager.open("s1", scene, sceneDir = ".")
    assertEquals(1L, first?.seq)
    assertTrue(manager.isOpen("s1"))
    assertEquals(1, manager.activeCount)

    val second = manager.updatePanels("s1", buildJsonArray {})
    assertEquals(2L, second.seq)

    manager.close("s1")
    assertFalse(manager.isOpen("s1"))
    assertTrue(server.closed)
  }

  @Test
  fun openReturnsNullWhenServerUnavailable() {
    val manager = XrSessionManager(factory = { _, _ -> null })
    assertNull(manager.open("s1", scene))
    assertFalse(manager.isOpen("s1"))
    assertEquals(0, manager.activeCount)
  }

  @Test
  fun updatePanelsOnUnknownSessionThrows() {
    val manager = XrSessionManager(factory = { _, _ -> FakeServer() })
    assertFailsWith<XrServerException> { manager.updatePanels("missing", buildJsonArray {}) }
  }

  @Test
  fun reopeningSameIdClosesThePriorSession() {
    val first = FakeServer()
    val second = FakeServer()
    val servers = ArrayDeque(listOf(first, second))
    val manager = XrSessionManager(factory = { _, _ -> servers.removeFirst() })

    manager.open("s1", scene)
    manager.open("s1", scene)
    assertTrue(first.closed, "prior session should be closed on reopen")
    assertFalse(second.closed)
    assertEquals(1, manager.activeCount)
  }

  @Test
  fun passesRequestedDimensionsToFactory() {
    var seenW = 0
    var seenH = 0
    val manager =
      XrSessionManager(
        factory = { w, h ->
          seenW = w
          seenH = h
          FakeServer()
        }
      )
    manager.open("s1", scene, width = 640, height = 400)
    assertEquals(640, seenW)
    assertEquals(400, seenH)
  }

  @Test
  fun fallsBackToDefaultDimensionsWhenUnset() {
    var seenW = 0
    var seenH = 0
    val manager =
      XrSessionManager(
        factory = { w, h ->
          seenW = w
          seenH = h
          FakeServer()
        },
        defaultWidth = 800,
        defaultHeight = 600,
      )
    manager.open("s1", scene)
    assertEquals(800, seenW)
    assertEquals(600, seenH)
  }

  @Test
  fun closeAllClosesEveryLiveSession() {
    val servers = mutableListOf<FakeServer>()
    val manager = XrSessionManager(factory = { _, _ -> FakeServer().also { servers.add(it) } })
    manager.open("a", scene)
    manager.open("b", scene)
    assertEquals(2, manager.activeCount)

    manager.close()
    assertEquals(0, manager.activeCount)
    assertTrue(servers.all { it.closed })
  }
}
