package ee.schimke.composeai.renderer.xr.client

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * Multiplexes XR render sessions over a single shared native process. The first [open] lazily
 * starts one [XrRenderServerHandle] via the injected [factory] (defaulting to the real
 * `xr-composite --serve`); every session (the daemon's `frameStreamId`) is then driven on that one
 * process/engine, keyed by `sessionId`. Tests inject a fake factory.
 *
 * The daemon's JSON-RPC layer wraps this: `xr/start` → [open], `xr/updatePanels` → [updatePanels],
 * `xr/stop` → [close], and feeds each returned [StreamFrame] into its frame-stream registry.
 *
 * Threading: safe for concurrent calls on distinct ids; calls for one id are expected to be
 * serialised by the caller (the daemon dispatches per-stream from one thread).
 */
public class XrSessionManager(
  private val factory: XrRenderServerFactory = XrRenderServerFactory.Native
) : AutoCloseable {

  // The one shared native process, started on first open(); null until then / after close().
  @Volatile private var server: XrRenderServerHandle? = null
  private val openIds = ConcurrentHashMap.newKeySet<String>()
  private val lock = Any()

  /** Number of live sessions — for diagnostics / tests. */
  public val activeCount: Int
    get() = openIds.size

  /** True if a session is currently open for [id]. */
  public fun isOpen(id: String): Boolean = openIds.contains(id)

  /**
   * Open a session for [id] and render [scene] on the shared server, returning the first frame — or
   * `null` when the native server isn't available (XR is best-effort, so the caller degrades
   * gracefully). [width]/[height] set the session viewport. Re-opening an existing [id] replaces
   * its scene.
   */
  public fun open(
    id: String,
    scene: JsonElement,
    sceneDir: String? = null,
    environment: String? = null,
    width: Int? = null,
    height: Int? = null,
  ): StreamFrame? {
    val srv = ensureServer() ?: return null
    val frame =
      try {
        srv.render(id, scene, sceneDir, environment, width, height)
      } catch (t: Throwable) {
        close(id)
        throw t
      }
    openIds.add(id)
    return frame
  }

  /**
   * Push per-frame panel mutations into the session for [id], returning the fresh frame. Throws
   * [XrServerException] if no session is open for [id].
   */
  public fun updatePanels(id: String, panels: JsonArray): StreamFrame {
    val srv = server
    if (srv == null || !openIds.contains(id)) {
      throw XrServerException("no XR session open for id=$id")
    }
    return srv.updatePanels(id, panels)
  }

  /** Close and drop the session for [id]; no-op if none is open. */
  public fun close(id: String) {
    if (!openIds.remove(id)) return
    try {
      server?.stop(id)
    } catch (t: Throwable) {
      System.err.println(
        "XrSessionManager: stop($id) threw ${t.javaClass.simpleName}: ${t.message}; continuing"
      )
    }
  }

  /** Close every live session and the shared process — call on daemon shutdown. */
  override fun close() {
    val srv: XrRenderServerHandle?
    synchronized(lock) {
      srv = server
      server = null
    }
    openIds.clear()
    srv?.let {
      try {
        it.close()
      } catch (t: Throwable) {
        System.err.println(
          "XrSessionManager: server close threw ${t.javaClass.simpleName}: ${t.message}; continuing"
        )
      }
    }
  }

  private fun ensureServer(): XrRenderServerHandle? {
    server?.let {
      return it
    }
    synchronized(lock) {
      server?.let {
        return it
      }
      val started = factory.start() ?: return null
      server = started
      return started
    }
  }
}
