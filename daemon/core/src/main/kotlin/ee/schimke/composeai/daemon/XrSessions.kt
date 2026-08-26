package ee.schimke.composeai.daemon

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * One frame rendered out of an XR session, in the shape the daemon puts on the wire.
 *
 * A deliberate re-declaration of the XR client's `StreamFrame` rather than a re-export of it. The
 * point of [XrSessions] is that `:daemon:core` no longer names a renderer client in its API, and a
 * port whose return type is the renderer's own type would not achieve that — the client would still
 * be on every consumer's compile classpath. The two are structurally identical and the adapter maps
 * between them; that mapping is the seam.
 *
 * [dataBase64] is base64-encoded image bytes and [encoding] names the container (e.g. `png`). The
 * daemon forwards the payload as-is, so nothing here decodes it.
 */
public data class XrFrame(
  val seq: Long,
  val width: Int,
  val height: Int,
  val encoding: String,
  val dataBase64: String,
)

/**
 * The daemon's port onto XR rendering: a set of live sessions keyed by the daemon's
 * `frameStreamId`, which `xr/start` opens, `xr/updatePanels` drives, `xr/structure` reads and
 * `xr/stop` closes.
 *
 * This exists so `:daemon:core` can serve the `xr/…` methods without depending on the JVM client
 * for the native `xr-composite --serve` process. Before it, `JsonRpcServer`'s constructor took an
 * `XrRenderServerFactory`, which put `:renderer-xr-client` in this module's **compile ABI** — so
 * every consumer of the daemon protocol, including an extracted preview server that never renders
 * XR, resolved a renderer client transitively. That is #3824 preparation item 4, and the reason the
 * contract probe recorded `renderer-xr-client` as a leak.
 *
 * The implementation lives with the thing it talks to: `:daemon:desktop` adapts `XrSessionManager`
 * onto this interface and injects it. XR is host-native, so the desktop daemon is the only one that
 * wires a non-null port; elsewhere it stays null and the `xr/…` methods answer `MethodNotFound`.
 *
 * **Threading** matches what the daemon does: calls for distinct ids may be concurrent, calls for
 * one id are serialised by the caller (the daemon dispatches per-stream from one thread).
 */
public interface XrSessions : AutoCloseable {
  /**
   * Open a session for [id] and render [scene], returning the first frame — or `null` when the
   * renderer isn't available, since XR is best-effort and the caller degrades rather than failing.
   * [width]/[height] set the session viewport. Re-opening an existing [id] replaces its scene.
   */
  public fun open(
    id: String,
    scene: JsonElement,
    sceneDir: String? = null,
    environment: String? = null,
    width: Int? = null,
    height: Int? = null,
  ): XrFrame?

  /** True if a session is currently open for [id]. */
  public fun isOpen(id: String): Boolean

  /**
   * Push per-frame panel mutations into the session for [id] and return the fresh frame. Throws if
   * no session is open for [id] or the renderer died; the daemon logs and drops the frame.
   */
  public fun updatePanels(id: String, panels: JsonArray): XrFrame

  /**
   * The held scene for [id] — the `xr/structure` data product (panel tree + poses as inline JSON),
   * kept in step with `updatePanels` deltas. `null` when no session is open for [id].
   */
  public fun structure(id: String): JsonElement?

  /** Close and drop the session for [id]; no-op if none is open. */
  public fun close(id: String)

  /**
   * Close every live session and release the renderer — called on daemon shutdown.
   *
   * The daemon calls this **more than once** on some paths: transport EOF closes before the
   * idle-timeout grace window, and `cleanShutdown` closes again afterwards. Implementations should
   * make it idempotent, and should not throw — the daemon guards the call anyway, since a throw
   * here would otherwise be raised mid-shutdown, before the host stops and before the exit hook
   * runs, but swallowing an unexpected exception is a worse place to learn about it than handling
   * it where the renderer is.
   */
  override fun close()
}
