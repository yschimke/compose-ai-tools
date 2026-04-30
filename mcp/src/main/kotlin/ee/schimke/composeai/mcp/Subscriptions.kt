package ee.schimke.composeai.mcp

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-session subscription bookkeeping. The two operations every MCP server with the `subscribe`
 * resources capability needs:
 *
 * - **Per-URI subscriptions** ([subscribe]/[unsubscribe]) — clients call `resources/subscribe` with
 *   a specific URI; we forward `notifications/resources/updated` to that session whenever that
 *   URI's bytes change.
 * - **Area-of-interest watch sets** ([watch]/[unwatch]) — clients call the `watch` MCP tool with a
 *   workspace + (optional) module + (optional) FQN glob, and the server expands that to a URI set,
 *   prioritises rendering it via `setVisible`/`setFocus` on the appropriate daemons, and pushes
 *   per-URI updates as renders complete. Re-expansion happens on `discoveryUpdated`.
 *
 * Sessions are opaque — we identify them by `Any` reference equality so the MCP transport layer
 * (which owns the actual session object) decides what counts as a session. In v0 every connected
 * MCP client is one session; HTTP transport later may multiplex.
 */
class Subscriptions {

  /** URI → set of session refs subscribed to it. */
  private val byUri = ConcurrentHashMap<String, MutableSet<Any>>()

  /** Session ref → its watch sets. */
  private val watches = ConcurrentHashMap<Any, MutableList<WatchEntry>>()

  fun subscribe(uri: String, session: Any) {
    val set = byUri.computeIfAbsent(uri) { ConcurrentHashMap.newKeySet() }
    set.add(session)
  }

  fun unsubscribe(uri: String, session: Any) {
    byUri[uri]?.remove(session)
  }

  /** Registers a watch entry for [session]. Returns the entry so the tool can echo it back. */
  fun watch(session: Any, entry: WatchEntry): WatchEntry {
    val list = watches.computeIfAbsent(session) { mutableListOf() }
    synchronized(list) { list.add(entry) }
    return entry
  }

  /** Removes every watch for [session] matching [predicate]. Returns the number removed. */
  fun unwatch(session: Any, predicate: (WatchEntry) -> Boolean): Int {
    val list = watches[session] ?: return 0
    return synchronized(list) {
      val before = list.size
      list.removeAll(predicate)
      before - list.size
    }
  }

  /** Drops all subscriptions and watches owned by [session]. Called on session disconnect. */
  fun forget(session: Any) {
    byUri.values.forEach { it.remove(session) }
    watches.remove(session)
  }

  /** Sessions currently subscribed to [uri]. */
  fun sessionsSubscribedTo(uri: String): Set<Any> = byUri[uri]?.toSet() ?: emptySet()

  /** Watch entries registered by [session], snapshot. */
  fun watchesFor(session: Any): List<WatchEntry> =
    watches[session]?.let { synchronized(it) { it.toList() } } ?: emptyList()

  /**
   * Returns every session whose watch set matches [uri]. Used to push per-URI updates to clients
   * who didn't explicitly `subscribe` but did `watch` a glob covering the URI. A session that both
   * subscribed AND watched is returned once (set semantics).
   */
  fun sessionsWatching(uri: PreviewUri): Set<Any> {
    val out = mutableSetOf<Any>()
    for ((session, entries) in watches) {
      val list = synchronized(entries) { entries.toList() }
      if (list.any { it.matches(uri) }) out.add(session)
    }
    return out
  }

  /**
   * The aggregated URI set every watch (across all sessions) cares about for
   * [workspaceId] + [modulePath]. Used by [WatchPropagator] to compute what to forward as
   * `setVisible` on the daemon — the union, not the per-session view.
   */
  fun urisWatchedFor(
    workspaceId: WorkspaceId,
    modulePath: String,
    candidatePreviews: Sequence<PreviewUri>,
  ): Set<PreviewUri> {
    val allEntries = watches.values.flatMap { synchronized(it) { it.toList() } }
    if (allEntries.isEmpty()) return emptySet()
    val out = mutableSetOf<PreviewUri>()
    candidatePreviews.forEach { uri ->
      if (uri.workspaceId != workspaceId || uri.modulePath != modulePath) return@forEach
      if (allEntries.any { it.matches(uri) }) out.add(uri)
    }
    return out
  }

  /** Snapshot of every (session, watch) pair — for diagnostics / `list_watches` tool. */
  fun snapshotAllWatches(): List<Pair<Any, WatchEntry>> {
    val out = mutableListOf<Pair<Any, WatchEntry>>()
    for ((session, entries) in watches) {
      val list = synchronized(entries) { entries.toList() }
      list.forEach { out.add(session to it) }
    }
    return out
  }
}

/**
 * One area-of-interest registration. The matching dimensions:
 *
 * - **workspaceId** — required. Cross-workspace watch is opt-in; the v0 watch tool requires callers
 *   to register a workspace explicitly (via `register_project`) and pass its id.
 * - **modulePath** — null means "any module in the workspace". Useful for "show me everything in
 *   this project".
 * - **fqnGlob** — null means "every preview in the matched module(s)". Otherwise a glob from
 *   [FqnGlob].
 *
 * Pure-data; equality is structural.
 */
data class WatchEntry(
  val workspaceId: WorkspaceId,
  val modulePath: String? = null,
  private val fqnGlobPattern: String? = null,
) {
  private val compiledGlob: FqnGlob? = fqnGlobPattern?.let { FqnGlob(it) }

  val fqnGlob: String?
    get() = fqnGlobPattern

  fun matches(uri: PreviewUri): Boolean {
    if (uri.workspaceId != workspaceId) return false
    if (modulePath != null && uri.modulePath != modulePath) return false
    if (compiledGlob != null && !compiledGlob.matches(uri.previewFqn)) return false
    return true
  }

  override fun toString(): String =
    "WatchEntry(workspace=$workspaceId, module=$modulePath, fqn=$fqnGlobPattern)"
}
