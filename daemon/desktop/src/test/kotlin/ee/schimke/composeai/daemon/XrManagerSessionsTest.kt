package ee.schimke.composeai.daemon

import ee.schimke.composeai.renderer.xr.client.StreamFrame
import ee.schimke.composeai.renderer.xr.client.XrRenderServerHandle
import ee.schimke.composeai.renderer.xr.client.XrSessionManager
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam that lets `:daemon:core` stop naming `:renderer-xr-client` in its API: this adapter is
 * the only place the renderer client's types meet the daemon's port.
 *
 * `XrSessionManager`'s own behaviour is `XrSessionManagerTest`'s subject and the RPC surface is
 * `StreamRpcIntegrationTest`'s; what is left, and what is only testable here, is that the mapping
 * between them is faithful — every frame field carried across, and every call delegated.
 */
class XrManagerSessionsTest {

  private class FakeHandle : XrRenderServerHandle {
    private val seq = AtomicInteger(0)
    @Volatile var closed = false
    val stopped = mutableListOf<String>()
    var lastPanels: JsonArray? = null
    var lastDimensions: Pair<Int?, Int?>? = null
    override val capabilities = buildJsonObject {}

    // Deliberately distinct values per field so a transposed mapping cannot pass.
    private fun frame() =
      seq.incrementAndGet().let { n -> StreamFrame(n.toLong() + 40, 64, 48, "png", "payload-$n") }

    override fun render(
      sessionId: String,
      scene: JsonElement,
      sceneDir: String?,
      environment: String?,
      width: Int?,
      height: Int?,
    ): StreamFrame {
      lastDimensions = width to height
      return frame()
    }

    override fun updatePanels(sessionId: String, panels: JsonArray): StreamFrame {
      lastPanels = panels
      return frame()
    }

    override fun stop(sessionId: String) {
      stopped.add(sessionId)
    }

    override fun close() {
      closed = true
    }
  }

  private fun scene() = buildJsonObject {
    put("version", 1)
    put("units", "dp")
  }

  @Test
  fun openCarriesEveryFrameFieldAcross() {
    val handle = FakeHandle()
    val sessions = XrManagerSessions(XrSessionManager { handle })

    val frame = sessions.open("s1", scene(), width = 320, height = 240)

    assertEquals(XrFrame(seq = 41L, width = 64, height = 48, "png", "payload-1"), frame)
    assertEquals(320 to 240, handle.lastDimensions)
  }

  @Test
  fun openReturnsNullWhenTheRendererIsUnavailable() {
    // The factory answering null is how "no native binary" reaches the daemon; the port must
    // preserve it as null rather than throwing, or `xr/start` stops degrading gracefully.
    val sessions = XrManagerSessions(XrSessionManager { null })

    assertNull(sessions.open("s1", scene()))
    assertFalse(sessions.isOpen("s1"))
  }

  @Test
  fun updatePanelsDelegatesAndMapsTheFreshFrame() {
    val handle = FakeHandle()
    val sessions = XrManagerSessions(XrSessionManager { handle })
    sessions.open("s1", scene())

    val panels = JsonArray(listOf(buildJsonObject { put("id", "top") }))
    val frame = sessions.updatePanels("s1", panels)

    assertEquals(panels, handle.lastPanels)
    assertEquals(XrFrame(seq = 42L, width = 64, height = 48, "png", "payload-2"), frame)
  }

  @Test
  fun structureAndIsOpenTrackTheSession() {
    val sessions = XrManagerSessions(XrSessionManager { FakeHandle() })
    val scene = scene()
    sessions.open("s1", scene)

    assertTrue(sessions.isOpen("s1"))
    assertEquals(scene, sessions.structure("s1"))

    sessions.close("s1")

    assertFalse(sessions.isOpen("s1"))
    assertNull(sessions.structure("s1"))
  }

  @Test
  fun closingThePortTearsDownTheSharedProcess() {
    // `JsonRpcServer` calls close() on shutdown and never touches the manager, so if the adapter
    // did not delegate this the native child would outlive the daemon.
    val handle = FakeHandle()
    val sessions = XrManagerSessions(XrSessionManager { handle })
    sessions.open("s1", scene())

    sessions.close()

    assertTrue(handle.closed)
  }
}
