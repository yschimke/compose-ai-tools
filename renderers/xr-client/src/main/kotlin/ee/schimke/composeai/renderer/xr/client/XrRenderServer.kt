package ee.schimke.composeai.renderer.xr.client

import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Abstraction over a running, multi-session XR render server, so callers (and the daemon's session
 * manager) can be unit-tested against a fake without spawning the native process. One handle fronts
 * one native process that fans many sessions (keyed by `sessionId`) over a single Filament engine.
 * [XrRenderServer] is the real, process-backed implementation.
 */
public interface XrRenderServerHandle : AutoCloseable {
  /** The server's advertised `capabilities` from `initialize`. */
  public val capabilities: JsonObject

  /** Render [scene] (serialized `SpatialScene`) for [sessionId]; returns the streamed frame. */
  public fun render(
    sessionId: String,
    scene: JsonElement,
    sceneDir: String? = null,
    environment: String? = null,
    width: Int? = null,
    height: Int? = null,
  ): StreamFrame

  /** Apply per-frame panel mutations to [sessionId] and return the freshly streamed frame. */
  public fun updatePanels(sessionId: String, panels: JsonArray): StreamFrame

  /** Tear down [sessionId] on the server (its per-session GL resources; the engine is kept). */
  public fun stop(sessionId: String)

  /**
   * Whether the underlying process is still usable. The session manager drops a handle that returns
   * `false` and re-spawns on the next open. Default `true` keeps fakes simple.
   */
  public fun isAlive(): Boolean = true
}

/**
 * Spawns the shared [XrRenderServerHandle], or `null` when the native binary isn't available.
 * Injected into the session manager so tests substitute a fake. The default implementation resolves
 * + spawns the real `xr-composite --serve` via [XrRenderServer.startIfAvailable]. The factory
 *   starts one process for the whole manager; per-session sizing is passed on each `render`.
 */
public fun interface XrRenderServerFactory {
  public fun start(): XrRenderServerHandle?

  public companion object {
    /** The production factory: resolve + spawn the real native server. */
    public val Native: XrRenderServerFactory = XrRenderServerFactory {
      XrRenderServer.startIfAvailable()
    }
  }
}

/**
 * A running native `xr-composite --serve` render server, driven over [XrServerClient]. [start]
 * resolves + spawns the binary and performs the `initialize` handshake (so the returned instance is
 * ready to render), then [render] / [updatePanels] / [stop] drive sessions by id and [close] tears
 * the child down.
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

  public override fun render(
    sessionId: String,
    scene: JsonElement,
    sceneDir: String?,
    environment: String?,
    width: Int?,
    height: Int?,
  ): StreamFrame = client.render(sessionId, scene, sceneDir, environment, width, height)

  public override fun updatePanels(sessionId: String, panels: JsonArray): StreamFrame =
    client.updatePanels(sessionId, panels)

  public override fun stop(sessionId: String): Unit = client.stop(sessionId)

  public override fun isAlive(): Boolean = client.isAlive()

  override fun close(): Unit = client.close()

  public companion object {
    /**
     * Spawn `xr-composite --serve` from [binary] (+ [materials]) and complete `initialize`. Throws
     * [XrServerException] if the handshake fails. [width]/[height] are the process-level defaults
     * for sessions that don't pass their own.
     */
    public fun start(
      binary: File,
      materials: File,
      width: Int = 1280,
      height: Int = 800,
    ): XrRenderServer {
      val client = XrServerClient.spawn(binary, materials, width, height)
      val caps =
        try {
          client.initialize()
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
    public fun startIfAvailable(version: String? = null): XrRenderServer? {
      val binary = XrCompositeBinary.resolve(version = version) ?: return null
      val materials = XrCompositeBinary.resolveMaterials(binary) ?: return null
      return start(binary, materials)
    }
  }
}
