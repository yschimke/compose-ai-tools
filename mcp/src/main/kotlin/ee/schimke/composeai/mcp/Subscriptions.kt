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
 * Sessions are opaque — we identify them by `Session` reference equality so the MCP transport layer
 * (which owns the actual session object) decides what counts as a session. In v0 every connected
 * MCP client is one session; HTTP transport later may multiplex.
 */
class Subscriptions {

  /** URI → set of session refs subscribed to it. */
  private val byUri = ConcurrentHashMap<String, MutableSet<Session>>()

  /** Session ref → its watch sets. */
  private val watches = ConcurrentHashMap<Session, MutableList<WatchEntry>>()

  /**
   * D1 — `(uri, kind)` → set of sessions subscribed to that data-product attachment via
   * `subscribe_preview_data`. Refcounted across sessions: the supervisor only forwards
   * `data/subscribe` to the daemon on first reference and `data/unsubscribe` on last release. This
   * matches the resource-subscription model — multiple MCP sessions can hold the same logical
   * subscription without redundant wire traffic to the daemon — and prevents leaked daemon-side
   * subscriptions when a session disconnects without explicitly unsubscribing.
   */
  private val byDataKey = ConcurrentHashMap<DataSubKey, MutableSet<Session>>()

  fun subscribe(uri: String, session: Session) {
    val set = byUri.computeIfAbsent(uri) { ConcurrentHashMap.newKeySet() }
    set.add(session)
  }

  fun unsubscribe(uri: String, session: Session) {
    byUri[uri]?.remove(session)
  }

  /**
   * Adds a `(uri, kind)` data-product subscription for [session]. Returns `true` iff this is the
   * first session interested in the pair — the supervisor uses that signal to decide whether to
   * forward `data/subscribe` to the daemon (idempotent on repeat).
   */
  fun subscribeData(uri: String, kind: String, session: Session): Boolean {
    val key = DataSubKey(uri, kind)
    var firstRef = false
    byDataKey.compute(key) { _, existing ->
      val set = existing ?: ConcurrentHashMap.newKeySet<Session>().also { firstRef = true }
      // Re-subscribing the same session is idempotent (set semantics) — but we still surface
      // `firstRef = true` only when the set was empty before this call.
      set.add(session)
      set
    }
    return firstRef
  }

  /**
   * Removes [session] from the `(uri, kind)` subscription. Returns `true` iff this was the last
   * session — the supervisor uses that signal to decide whether to forward `data/unsubscribe` to
   * the daemon.
   */
  fun unsubscribeData(uri: String, kind: String, session: Session): Boolean {
    val key = DataSubKey(uri, kind)
    var lastRef = false
    byDataKey.computeIfPresent(key) { _, set ->
      set.remove(session)
      if (set.isEmpty()) {
        lastRef = true
        null
      } else set
    }
    return lastRef
  }

  /**
   * Returns the `(uri, kind)` pairs [session] held the last reference to, removing them from the
   * registry. The supervisor calls this on session disconnect and forwards `data/unsubscribe` to
   * the matching daemon for each pair so the daemon doesn't leak subscriptions for previews the
   * client will never look at again.
   */
  fun forgetDataSubscriptions(session: Session): List<DataSubKey> {
    val released = mutableListOf<DataSubKey>()
    val iter = byDataKey.entries.iterator()
    while (iter.hasNext()) {
      val (key, set) = iter.next()
      if (set.remove(session) && set.isEmpty()) {
        released.add(key)
        iter.remove()
      }
    }
    return released
  }

  /** Registers a watch entry for [session]. Returns the entry so the tool can echo it back. */
  fun watch(session: Session, entry: WatchEntry): WatchEntry {
    val list = watches.computeIfAbsent(session) { mutableListOf() }
    synchronized(list) { list.add(entry) }
    return entry
  }

  /** Removes every watch for [session] matching [predicate]. Returns the number removed. */
  fun unwatch(session: Session, predicate: (WatchEntry) -> Boolean): Int {
    val list = watches[session] ?: return 0
    return synchronized(list) {
      val before = list.size
      list.removeAll(predicate)
      before - list.size
    }
  }

  /**
   * Drops resource subscriptions and watches owned by [session]. Data-product subscriptions are NOT
   * dropped here — call [forgetDataSubscriptions] separately and forward `data/unsubscribe` to the
   * matching daemons for each released `(uri, kind)`. Splitting the two lets the caller see which
   * keys lost their last reference (so it knows which wire calls to make) without this method
   * needing to know about the supervisor.
   */
  fun forget(session: Session) {
    byUri.values.forEach { it.remove(session) }
    watches.remove(session)
  }

  /** Sessions currently subscribed to [uri]. */
  fun sessionsSubscribedTo(uri: String): Set<Session> = byUri[uri]?.toSet() ?: emptySet()

  /** Watch entries registered by [session], snapshot. */
  fun watchesFor(session: Session): List<WatchEntry> =
    watches[session]?.let { synchronized(it) { it.toList() } } ?: emptyList()

  /**
   * Returns every session whose watch set matches [uri]. Used to push per-URI updates to clients
   * who didn't explicitly `subscribe` but did `watch` a glob covering the URI. A session that both
   * subscribed AND watched is returned once (set semantics).
   */
  fun sessionsWatching(uri: PreviewUri): Set<Session> {
    val out = mutableSetOf<Session>()
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
  fun snapshotAllWatches(): List<Pair<Session, WatchEntry>> {
    val out = mutableListOf<Pair<Session, WatchEntry>>()
    for ((session, entries) in watches) {
      val list = synchronized(entries) { entries.toList() }
      list.forEach { out.add(session to it) }
    }
    return out
  }
}

/**
 * Refcount key for `subscribe_preview_data` bookkeeping. Modelled as a value type so the `(uri,
 * kind)` pair stays opaque to callers — they get back the released keys on disconnect and forward
 * unsubscribes without reaching into individual fields.
 */
data class DataSubKey(val uri: String, val kind: String)

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
