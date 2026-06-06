package ee.schimke.composeai.renderer.xr.client

import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Abstraction over a running XR render server, so callers (and the daemon's session manager) can be
 * unit-tested against a fake without spawning the native process. [XrRenderServer] is the real,
 * process-backed implementation.
 */
public interface XrRenderServerHandle : AutoCloseable {
  /** The server's advertised `capabilities` from `initialize`. */
  public val capabilities: JsonObject

  /** Render a full [scene] (serialized `SpatialScene`); returns the streamed frame. */
  public fun render(
    scene: JsonElement,
    sceneDir: String? = null,
    environment: String? = null,
  ): StreamFrame

  /** Apply per-frame panel mutations and return the freshly streamed frame. */
  public fun updatePanels(panels: JsonArray): StreamFrame
}

/**
 * Spawns an [XrRenderServerHandle], or `null` when the native binary isn't available. Injected into
 * the session manager so tests substitute a fake. The default implementation resolves + spawns the
 * real `xr-composite --serve` via [XrRenderServer.startIfAvailable].
 */
public fun interface XrRenderServerFactory {
  public fun start(width: Int, height: Int): XrRenderServerHandle?

  public companion object {
    /** The production factory: resolve + spawn the real native server. */
    public val Native: XrRenderServerFactory = XrRenderServerFactory { w, h ->
      XrRenderServer.startIfAvailable(width = w, height = h)
    }
  }
}

/**
 * A running native `xr-composite --serve` render server, driven over [XrServerClient]. This is the
 * single entry point the daemon's XR `RenderSession` backend holds: [start] resolves + spawns the
 * binary and performs the `initialize` handshake (so the returned instance is ready to render),
 * then [render] / [updatePanels] return the streamed frames and [close] tears the child down.
 *
 * The daemon owns one of these per native process and multiplexes sessions over it; keeping the
 * supervision here (resolve → spawn → initialize → close) keeps the daemon side a thin proxy.
 */
public class XrRenderServer
private constructor(
  private val client: XrServerClient,
  /** The server's advertised `capabilities` from `initialize`. */
  public override val capabilities: JsonObject,
) : XrRenderServerHandle {

  /** Render a full [scene] (serialized `SpatialScene`); returns the streamed frame. */
  public override fun render(
    scene: JsonElement,
    sceneDir: String?,
    environment: String?,
  ): StreamFrame = client.render(scene, sceneDir, environment)

  /** Apply per-frame panel mutations and return the freshly streamed frame. */
  public override fun updatePanels(panels: JsonArray): StreamFrame = client.updatePanels(panels)

  override fun close(): Unit = client.close()

  public companion object {
    /**
     * Spawn `xr-composite --serve` from [binary] (+ [materials]) and complete `initialize`. Throws
     * [XrServerException] if the handshake fails.
     */
    public fun start(
      binary: File,
      materials: File,
      width: Int = 1280,
      height: Int = 800,
      frameStreamId: String? = null,
    ): XrRenderServer {
      val client = XrServerClient.spawn(binary, materials, width, height)
      val caps =
        try {
          client.initialize(frameStreamId)
        } catch (e: Throwable) {
          client.close()
          throw e
        }
      return XrRenderServer(client, caps)
    }

    /**
     * Convenience: resolve the binary + materials via [XrCompositeBinary] and [start], or `null` if
     * the binary isn't available (the XR render service is best-effort).
     */
    public fun startIfAvailable(
      width: Int = 1280,
      height: Int = 800,
      version: String? = null,
    ): XrRenderServer? {
      val binary = XrCompositeBinary.resolve(version = version) ?: return null
      val materials = XrCompositeBinary.resolveMaterials(binary) ?: return null
      return start(binary, materials, width, height)
    }
  }
}
