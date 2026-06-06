package ee.schimke.composeai.renderer.xr.client

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

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
  // The current scene per session — the daemon's view of the layout, served as the `xr/structure`
  // data product (panel tree + poses, mirrors a11y/hierarchy). Seeded from `open` and kept in step
  // with `updatePanels` deltas so the structure reflects live poses.
  private val scenes = ConcurrentHashMap<String, JsonElement>()
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
        // The native server inserts the session before scene setup, so a failed first render can
        // leave a session allocated. close(id) is a no-op here (id isn't registered yet), so stop
        // it
        // directly — best-effort, since on a transport failure the pipe may already be dead.
        runCatching { srv.stop(id) }
        throw t
      }
    openIds.add(id)
    scenes[id] = scene
    return frame
  }

  /**
   * The held scene for [id] — the `xr/structure` data product (panel tree + poses as inline JSON).
   * `null` when no session is open for [id].
   */
  public fun structure(id: String): JsonElement? = scenes[id]

  /**
   * Push per-frame panel mutations into the session for [id], returning the fresh frame. Throws
   * [XrServerException] if no session is open for [id].
   */
  public fun updatePanels(id: String, panels: JsonArray): StreamFrame {
    val srv = server
    if (srv == null || !openIds.contains(id)) {
      throw XrServerException("no XR session open for id=$id")
    }
    val frame = srv.updatePanels(id, panels)
    // Keep the held structure (served via xr/structure) in step with the live scene: overlay each
    // delta onto the matching panel by id (appending unknown ids), mirroring the native server.
    scenes[id]?.let { scenes[id] = mergePanels(it, panels) }
    return frame
  }

  /** Close and drop the session for [id]; no-op if none is open. */
  public fun close(id: String) {
    scenes.remove(id)
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
    scenes.clear()
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

  /**
   * Overlay each panel delta (`{id, …}`) onto the matching panel in [scene] by id, appending
   * unknown ids — the same merge the native server applies — so the held structure tracks live
   * updates. Returns [scene] unchanged if it isn't a scene object with a `panels` array.
   */
  private fun mergePanels(scene: JsonElement, deltas: JsonArray): JsonElement {
    val obj = scene as? JsonObject ?: return scene
    val panels =
      (obj["panels"] as? JsonArray)?.map { it as JsonObject }?.toMutableList() ?: return scene
    for (delta in deltas) {
      val d = delta as? JsonObject ?: continue
      val id = d["id"]?.jsonPrimitive?.contentOrNull ?: continue
      val idx = panels.indexOfFirst { it["id"]?.jsonPrimitive?.contentOrNull == id }
      if (idx >= 0) {
        panels[idx] = buildJsonObject {
          panels[idx].forEach { (k, v) -> put(k, v) }
          d.forEach { (k, v) -> put(k, v) } // delta fields win
        }
      } else if (
        d.containsKey("poseInRoot") && d.containsKey("sizeDp") && d.containsKey("texture")
      ) {
        // The native server only appends an unknown id when it's a complete SpatialPanel; skip
        // partial new-panel deltas so the structure can't report a panel the render dropped.
        panels.add(d)
      }
    }
    return buildJsonObject {
      obj.forEach { (k, v) -> if (k == "panels") put("panels", JsonArray(panels)) else put(k, v) }
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
