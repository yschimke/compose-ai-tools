package ee.schimke.composeai.renderer.xr.client

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * Holds one native XR render server per live stream id (the daemon's `frameStreamId`), the
 * server-side analogue of the daemon's held interactive sessions: open a session for a scene, push
 * per-frame panel updates, and close it. Each session owns its own native process via the injected
 * [factory] (defaulting to the real `xr-composite --serve`); tests inject a fake.
 *
 * The daemon's JSON-RPC layer (the next increment) wraps this: `xr/start` → [open],
 * `xr/updatePanels` → [updatePanels], `xr/stop` → [close], and feeds each returned [StreamFrame]
 * into its frame-stream registry. Keeping the lifecycle here keeps that wiring a thin adapter.
 *
 * Threading: safe for concurrent calls on distinct ids; calls for one id are expected to be
 * serialised by the caller (the daemon dispatches per-stream from one thread).
 */
public class XrSessionManager(
  private val factory: XrRenderServerFactory = XrRenderServerFactory.Native,
  private val width: Int = 1280,
  private val height: Int = 800,
) : AutoCloseable {

  private val sessions = ConcurrentHashMap<String, XrRenderServerHandle>()

  /** Number of live sessions — for diagnostics / tests. */
  public val activeCount: Int
    get() = sessions.size

  /** True if a session is currently open for [id]. */
  public fun isOpen(id: String): Boolean = sessions.containsKey(id)

  /**
   * Open a session for [id] and render [scene], returning the first frame — or `null` when the
   * native server isn't available (XR is best-effort, so the caller degrades gracefully). Closes
   * any prior session held under the same [id] first.
   */
  public fun open(
    id: String,
    scene: JsonElement,
    sceneDir: String? = null,
    environment: String? = null,
  ): StreamFrame? {
    close(id)
    val server = factory.start(width, height) ?: return null
    sessions[id] = server
    return try {
      server.render(scene, sceneDir, environment)
    } catch (t: Throwable) {
      close(id)
      throw t
    }
  }

  /**
   * Push per-frame panel mutations into the session for [id], returning the fresh frame. Throws
   * [XrServerException] if no session is open for [id].
   */
  public fun updatePanels(id: String, panels: JsonArray): StreamFrame {
    val server = sessions[id] ?: throw XrServerException("no XR session open for id=$id")
    return server.updatePanels(panels)
  }

  /** Close and drop the session for [id]; no-op if none is open. */
  public fun close(id: String) {
    sessions.remove(id)?.let { server ->
      try {
        server.close()
      } catch (t: Throwable) {
        System.err.println(
          "XrSessionManager: close($id) threw ${t.javaClass.simpleName}: ${t.message}; continuing"
        )
      }
    }
  }

  /** Close every live session — call on daemon shutdown. */
  override fun close() {
    sessions.keys.toList().forEach(::close)
  }
}
