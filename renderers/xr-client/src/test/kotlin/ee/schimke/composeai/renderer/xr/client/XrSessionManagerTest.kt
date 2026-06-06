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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class XrSessionManagerTest {

  private data class RenderCall(val sessionId: String, val width: Int?, val height: Int?)

  private class FakeServer : XrRenderServerHandle {
    val seq = AtomicLong(0)
    var closed = false
    var failNextRender = false
    @Volatile var alive = true
    val stopped = mutableListOf<String>()
    val renders = mutableListOf<RenderCall>()
    override val capabilities: JsonObject = buildJsonObject {}

    override fun isAlive(): Boolean = alive

    private fun frame() = StreamFrame(seq.incrementAndGet(), 64, 48, "png", "data")

    override fun render(
      sessionId: String,
      scene: JsonElement,
      sceneDir: String?,
      environment: String?,
      width: Int?,
      height: Int?,
    ): StreamFrame {
      renders.add(RenderCall(sessionId, width, height))
      if (failNextRender) throw XrServerException("render boom")
      return frame()
    }

    override fun updatePanels(sessionId: String, panels: JsonArray) = frame()

    override fun stop(sessionId: String) {
      stopped.add(sessionId)
    }

    override fun close() {
      closed = true
    }
  }

  /** Factory backed by a supplier; counts how many times it was started. */
  private class CountingFactory(private val supplier: () -> XrRenderServerHandle?) :
    XrRenderServerFactory {
    constructor(server: XrRenderServerHandle?) : this({ server })

    var starts = 0

    override fun start(): XrRenderServerHandle? {
      starts++
      return supplier()
    }
  }

  private val scene: JsonElement = buildJsonObject {}

  @Test
  fun openRendersFirstFrame_updatePanelsAdvances_closeStops() {
    val server = FakeServer()
    val manager = XrSessionManager(CountingFactory(server))

    val first = manager.open("s1", scene, sceneDir = ".")
    assertEquals(1L, first?.seq)
    assertTrue(manager.isOpen("s1"))
    assertEquals(1, manager.activeCount)

    val second = manager.updatePanels("s1", buildJsonArray {})
    assertEquals(2L, second.seq)

    manager.close("s1")
    assertFalse(manager.isOpen("s1"))
    assertEquals(0, manager.activeCount)
    // close(id) stops the session on the shared server; it does NOT tear the process down.
    assertEquals(listOf("s1"), server.stopped)
    assertFalse(server.closed)
  }

  @Test
  fun sessionsShareOneServerProcess() {
    val server = FakeServer()
    val factory = CountingFactory(server)
    val manager = XrSessionManager(factory)

    manager.open("a", scene)
    manager.open("b", scene)

    assertEquals(2, manager.activeCount)
    assertEquals(1, factory.starts) // one shared native process for both sessions
    assertEquals(setOf("a", "b"), server.renders.map { it.sessionId }.toSet())
  }

  @Test
  fun openReturnsNullWhenServerUnavailable() {
    val manager = XrSessionManager(CountingFactory(null))
    assertNull(manager.open("s1", scene))
    assertFalse(manager.isOpen("s1"))
    assertEquals(0, manager.activeCount)
  }

  @Test
  fun updatePanelsOnUnknownSessionThrows() {
    val manager = XrSessionManager(CountingFactory(FakeServer()))
    assertFailsWith<XrServerException> { manager.updatePanels("missing", buildJsonArray {}) }
  }

  @Test
  fun passesRequestedDimensionsToRender() {
    val server = FakeServer()
    val manager = XrSessionManager(CountingFactory(server))
    manager.open("s1", scene, width = 640, height = 400)
    val call = server.renders.single()
    assertEquals(640, call.width)
    assertEquals(400, call.height)
  }

  @Test
  fun failedOpenStopsThePartialSession() {
    val server = FakeServer().apply { failNextRender = true }
    val manager = XrSessionManager(CountingFactory(server))

    assertFailsWith<XrServerException> { manager.open("s1", scene) }

    assertFalse(manager.isOpen("s1"))
    assertEquals(0, manager.activeCount)
    // The native server may have allocated the session before the render failed; it's stopped.
    assertEquals(listOf("s1"), server.stopped)
  }

  @Test
  fun retainsSceneAsStructureUntilClosed() {
    val server = FakeServer()
    val manager = XrSessionManager(CountingFactory(server))
    val scene = buildJsonObject { put("units", "dp") }

    manager.open("s1", scene)
    assertEquals(scene, manager.structure("s1"))

    manager.close("s1")
    assertNull(manager.structure("s1"))
  }

  @Test
  fun updatePanelsMergesDeltasIntoStructure() {
    val manager = XrSessionManager(CountingFactory(FakeServer()))
    val scene = buildJsonObject {
      put("units", "dp")
      put(
        "panels",
        buildJsonArray {
          add(
            buildJsonObject {
              put("id", "top")
              put("x", 0)
            }
          )
        },
      )
    }
    manager.open("s1", scene)

    manager.updatePanels(
      "s1",
      buildJsonArray {
        // Partial delta on an existing panel — overlays.
        add(
          buildJsonObject {
            put("id", "top")
            put("x", 120)
          }
        )
        // Complete new panel — appended.
        add(
          buildJsonObject {
            put("id", "extra")
            putJsonObject("poseInRoot") {}
            putJsonObject("sizeDp") {}
            put("texture", "extra.png")
          }
        )
        // Partial new panel — dropped (the native renderer would skip it).
        add(
          buildJsonObject {
            put("id", "junk")
            put("x", 9)
          }
        )
      },
    )

    val panels = manager.structure("s1")!!.jsonObject["panels"]!!.jsonArray
    val byId = panels.associateBy { it.jsonObject["id"]?.jsonPrimitive?.content }
    assertEquals(120, byId["top"]!!.jsonObject["x"]?.jsonPrimitive?.int) // existing moved
    assertTrue(byId.containsKey("extra"), "complete new panel is appended")
    assertFalse(byId.containsKey("junk"), "partial new panel is dropped")
  }

  @Test
  fun respawnsAfterTheSharedServerDies() {
    val dead = FakeServer()
    val fresh = FakeServer()
    val queue = ArrayDeque(listOf<XrRenderServerHandle>(dead, fresh))
    val factory = CountingFactory { queue.removeFirst() }
    val manager = XrSessionManager(factory)

    manager.open("a", scene)
    assertEquals(1, factory.starts)
    assertTrue(manager.isOpen("a"))

    dead.alive = false // the shared child crashed

    // The next open detects the dead process, drops it, and re-spawns a fresh one.
    manager.open("b", scene)
    assertEquals(2, factory.starts)
    assertTrue(dead.closed, "the dead server is closed")
    assertFalse(manager.isOpen("a"), "sessions on the dead process are forgotten")
    assertTrue(manager.isOpen("b"))
    assertNull(manager.structure("a"))
  }

  @Test
  fun updatePanelsOnDeadServerDropsSessionAndThrows() {
    val server = FakeServer()
    val manager = XrSessionManager(CountingFactory(server))
    manager.open("s1", scene)

    server.alive = false // child died between frames on a live stream

    assertFailsWith<XrServerException> { manager.updatePanels("s1", buildJsonArray {}) }
    assertFalse(manager.isOpen("s1"), "the dead session is forgotten")
    assertTrue(server.closed, "the dead server is closed")
  }

  @Test
  fun closeClosesTheSharedServer() {
    val server = FakeServer()
    val manager = XrSessionManager(CountingFactory(server))
    manager.open("a", scene)
    manager.open("b", scene)
    assertEquals(2, manager.activeCount)

    manager.close()
    assertEquals(0, manager.activeCount)
    assertTrue(server.closed)
  }
}
