package ee.schimke.composeai.daemon

import ee.schimke.composeai.renderer.xr.client.StreamFrame
import ee.schimke.composeai.renderer.xr.client.XrRenderServerFactory
import ee.schimke.composeai.renderer.xr.client.XrSessionManager
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * Adapts [XrSessionManager] — the JVM client's multiplexer over one native `xr-composite --serve`
 * process — onto the daemon's [XrSessions] port.
 *
 * This class is the whole reason `:daemon:core` no longer names `:renderer-xr-client` in its API.
 * The daemon serves `xr/…` against the port; the mapping to the renderer client happens here, in
 * the module that actually has a native renderer to talk to. XR is host-native, so the desktop
 * daemon is the only one that wires it.
 *
 * The manager is owned by this adapter: [close] tears down every live session and the shared child
 * process, which is what `JsonRpcServer` calls on shutdown.
 */
public class XrManagerSessions(private val manager: XrSessionManager) : XrSessions {

  /** Wrap a factory directly — the shape [ee.schimke.composeai.daemon.DaemonMain] wires. */
  public constructor(factory: XrRenderServerFactory) : this(XrSessionManager(factory))

  override fun open(
    id: String,
    scene: JsonElement,
    sceneDir: String?,
    environment: String?,
    width: Int?,
    height: Int?,
  ): XrFrame? = manager.open(id, scene, sceneDir, environment, width, height)?.toXrFrame()

  override fun isOpen(id: String): Boolean = manager.isOpen(id)

  override fun updatePanels(id: String, panels: JsonArray): XrFrame =
    manager.updatePanels(id, panels).toXrFrame()

  override fun structure(id: String): JsonElement? = manager.structure(id)

  override fun close(id: String): Unit = manager.close(id)

  override fun close(): Unit = manager.close()
}

/**
 * The structural mapping between the renderer client's frame and the daemon's. Field-for-field
 * today; kept explicit so a change on either side is a compile error here rather than a silent
 * re-shape of what goes on the wire.
 */
private fun StreamFrame.toXrFrame(): XrFrame =
  XrFrame(
    seq = seq,
    width = width,
    height = height,
    encoding = encoding,
    dataBase64 = dataBase64,
  )
