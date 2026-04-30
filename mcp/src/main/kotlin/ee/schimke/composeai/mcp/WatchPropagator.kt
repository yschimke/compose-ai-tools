package ee.schimke.composeai.mcp

import java.util.concurrent.ConcurrentHashMap

/**
 * Translates the union of every session's watch set into per-daemon `setVisible` / `setFocus`
 * notifications, so the daemon's render queue prioritises previews that any connected agent cares
 * about and keeps them warm.
 *
 * Design notes:
 *
 * - **Per (workspace, module).** Each daemon serves exactly one (workspace, module) pair, and the
 *   wire form of `setVisible` takes preview ids local to that daemon (the daemon doesn't know about
 *   workspaces). So we partition the watched URI set by daemon and emit one `setVisible` per
 *   affected daemon.
 * - **Idempotent / rate-limited.** [recompute] is called on every relevant change (`watch`,
 *   `unwatch`, `discoveryUpdated`, daemon spawn). We compare the new set against the last sent set
 *   per daemon and skip the wire call if unchanged — ordinary client-side debouncing isn't enough
 *   because `discoveryUpdated` can fire repeatedly in a short window.
 * - **Focus = same set as visible in v0.** Splitting "user is interacting" vs "user might want to
 *   look" requires sub-tool surface (e.g. `set_focus` MCP tool) we deliberately defer per
 *   MCP-KOTLIN.md § "Tools surface (v0)".
 *
 * The propagator is intentionally stateless w.r.t. *which* previews exist. The [previewIdProvider]
 * is supplied by the supervisor and reflects the daemon's current discovery state; the propagator
 * filters those candidates by the watch sets and forwards the result.
 */
class WatchPropagator(
  private val subscriptions: Subscriptions,
  private val previewIdProvider: PreviewIdProvider,
) {

  private val lastSent = ConcurrentHashMap<DaemonKey, Set<String>>()

  /**
   * Recomputes the watched-URI set for [daemon] and forwards a `setVisible` + `setFocus` if it
   * differs from the last sent set. Safe to call from any thread.
   */
  fun recompute(daemon: SupervisedDaemon) {
    val candidates = previewIdProvider.previewsFor(daemon)
    val watched =
      subscriptions
        .urisWatchedFor(
          workspaceId = daemon.workspaceId,
          modulePath = daemon.modulePath,
          candidatePreviews = candidates.asSequence(),
        )
        .map { it.previewFqn }
        .toSortedSet() // stable ordering = stable equality
    val key = DaemonKey(daemon.workspaceId, daemon.modulePath)
    val previous = lastSent[key]
    // Treat null and empty as equivalent: the first recompute on a daemon that has no watches
    // shouldn't fire an empty `setVisible` (the daemon already starts with nothing visible).
    if ((previous ?: emptySet()) == watched) return
    lastSent[key] = watched
    val ids = watched.toList()
    // Fan out to every replica — each replica has its own render queue + visible-set state and
    // consults it when prioritising background renders, so they all need the same view of "what
    // the user is looking at". With `replicasPerDaemon = 0` this collapses to one call to the
    // primary, preserving existing behaviour.
    daemon.allClients().forEach { client ->
      runCatching { client.setVisible(ids) }
      runCatching { client.setFocus(ids) }
    }
  }

  /**
   * Drops the cached "last sent" entry for [daemon]. Called when a daemon is shut down or when we
   * know the daemon's view has been reset (e.g. respawn after `classpathDirty`); a future
   * [recompute] will then forward the current watched set even if it matches what we *thought* we
   * sent.
   */
  fun forget(daemon: SupervisedDaemon) {
    lastSent.remove(DaemonKey(daemon.workspaceId, daemon.modulePath))
  }

  private data class DaemonKey(val workspaceId: WorkspaceId, val modulePath: String)
}

/**
 * Lookup the supervisor exposes: the current preview URI set advertised by [daemon]'s discovery
 * state. Updated as `discoveryUpdated` notifications arrive — see `DaemonMcpServer` for the
 * concrete wiring.
 */
fun interface PreviewIdProvider {
  fun previewsFor(daemon: SupervisedDaemon): List<PreviewUri>
}
