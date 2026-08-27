package ee.schimke.composeai.cli.serve

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe source of truth for every catalog the operator configured and its latest load
 * outcome.
 *
 * A failed catalog used to disappear completely: startup logged one stderr line, while readiness,
 * `/status`, the home index, and the branch refresher only knew about successfully registered
 * sessions. That made "configured but broken" indistinguishable from "not configured". This tracker
 * preserves the configured set across startup and background refreshes so every consumer observes
 * the same state.
 *
 * [State.available] means a usable copy is currently registered. A refresh failure after an earlier
 * success keeps it true because [ServeCatalogStore] retains the last good staged copy; the
 * [State.error] still records that the latest refresh failed. An initial failure has
 * `available=false`, remains visible in status, and stays eligible for refresh retry. Catalog
 * availability deliberately does not require every catalog for server readiness: a usable server
 * with a partial external catalog set should still deploy.
 */
class CatalogLoadTracker(
  configured: List<Config>,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  /** One configured catalog and where its delivery branch lives. */
  data class Config(
    val system: String,
    val listed: Boolean,
    val repo: String,
    val branch: String,
    /**
     * The front-page section this catalog was published under ([ServeCatalogsConfig.Entry.group],
     * resolved against the config's group table), or null when it declared none. Carried here
     * because this tracker is the configured-catalog source of truth every consumer already reads —
     * including the home index, which needs the grouping to be config rather than code.
     */
    val group: ServeWeb.HomeGroup? = null,
    /**
     * Startup fetch order, highest first ([ServeCatalogsConfig.Entry.loadPriority]). Kept beside
     * the rest of the configured entry because [loadOrder] is the one consumer, and it reads the
     * same snapshot every other consumer does.
     */
    val loadPriority: Int = 0,
  )

  /** Immutable snapshot of one catalog's current availability and latest attempt. */
  data class State(
    val config: Config,
    val available: Boolean = false,
    val error: String? = null,
    val lastAttemptEpochMillis: Long? = null,
    /** Render failures declared by the latest successfully loaded catalog. */
    val failedRenders: Int = 0,
  ) {
    val loadState: String
      get() =
        when {
          available && error == null -> "loaded"
          available -> "stale"
          error != null -> "failed"
          else -> "pending"
        }
  }

  /**
   * Configured order, mutable because the catalog set is now runtime config: the admin API
   * ([ServeCatalogAdmin]) publishes and retires catalogs on a running server. Guarded by [lock] for
   * ordering; [states] stays a concurrent map so the hot read paths (status, home index, refresh)
   * never block on a registration.
   */
  private val lock = Any()
  private val ordered =
    configured
      .distinctBy { it.system }
      .also { require(it.size == configured.size) { "duplicate catalog system id" } }
      .toMutableList()
  private val states = ConcurrentHashMap(ordered.associate { it.system to State(it) })

  /**
   * Publish a new catalog, appended after the already-configured ones. Returns false when
   * [config]'s system is already tracked — re-publishing an existing id is the caller's conflict to
   * report, not something to silently overwrite (it would drop the running catalog's load state).
   */
  fun add(config: Config): Boolean =
    synchronized(lock) {
      if (states.containsKey(config.system)) return false
      states[config.system] = State(config)
      ordered += config
      true
    }

  /**
   * Replace [system]'s **listing** metadata — where it appears and when it is fetched, not where it
   * comes from — keeping its load state and its registered content untouched. Returns false when it
   * isn't tracked.
   *
   * Needed because a catalog's front-page placement is resolved once, at registration, into a
   * [ServeWeb.HomeGroup] snapshot. So a group defined *after* a catalog was published would never
   * reach it: the group table would gain the entry while the already-registered catalog kept `group
   * = null` and stayed under the owner fallback. [ServeCatalogAdmin] calls this to re-resolve every
   * claim whenever the group table changes, which is what makes a group edit take effect without a
   * restart or a re-fetch.
   *
   * Deliberately cannot change [Config.repo] or [Config.branch] — those decide what bytes get
   * served, and changing them behind a live registration would leave the served content disagreeing
   * with its own provenance (and its trust verdict). Re-pointing a catalog is a retire plus a
   * publish.
   */
  fun relist(
    system: String,
    listed: Boolean,
    group: ServeWeb.HomeGroup?,
    // Deliberately not defaulted: every caller states it, so none can silently reset it to 0.
    loadPriority: Int,
  ): Boolean =
    synchronized(lock) {
      val existing = states[system] ?: return false
      val updated =
        existing.config.copy(listed = listed, group = group, loadPriority = loadPriority)
      states[system] = existing.copy(config = updated)
      val at = ordered.indexOfFirst { it.system == system }
      if (at >= 0) ordered[at] = updated
      true
    }

  /**
   * Replace [system]'s **provenance** — the repository and branch its bytes come from — keeping its
   * position and its load state. Returns false when it isn't tracked.
   *
   * The counterpart to [relist], and deliberately a separate call with a much narrower contract,
   * because the two are safe at different moments. [relist] may run at any time: it moves a card on
   * the front page and changes nothing about what is served. This one may run in exactly one
   * situation — **after** the new source has already been loaded and registered under [system] —
   * because until then the served content and this record would disagree about where the bytes came
   * from, and that record is what the trust verdict and the permalinks are built from.
   *
   * [ServeCatalogAdmin.register] is the only caller, and it loads first for that reason: a failed
   * load leaves the old catalog serving and never reaches here. Re-pointing by retiring and
   * re-publishing instead — what the admin API used to require — has no such property: the retire
   * succeeds, the publish fetches, and a fetch that fails leaves the system published nowhere.
   */
  fun repoint(system: String, repo: String, branch: String): Boolean =
    synchronized(lock) {
      val existing = states[system] ?: return false
      val updated = existing.config.copy(repo = repo, branch = branch)
      states[system] = existing.copy(config = updated)
      val at = ordered.indexOfFirst { it.system == system }
      if (at >= 0) ordered[at] = updated
      true
    }

  /** Retire a catalog. Returns false when it wasn't configured. */
  fun remove(system: String): Boolean =
    synchronized(lock) {
      if (states.remove(system) == null) return false
      ordered.removeAll { it.system == system }
      true
    }

  /** The configured entry for [system], or null when it isn't served here. */
  fun configFor(system: String): Config? = states[system]?.config

  /** First currently usable catalog in configured order, or null while every catalog is pending. */
  fun firstAvailableSystem(): String? = snapshot().firstOrNull { it.available }?.config?.system

  fun record(result: ServeCatalogStore.Result) {
    when (result) {
      is ServeCatalogStore.Result.Ok -> recordSuccess(result.system, result.failedRenderCount)
      is ServeCatalogStore.Result.Failed -> recordFailure(result.system, result.reason)
    }
  }

  fun recordSuccess(system: String, failedRenders: Int = 0) {
    val at = clock()
    states.computeIfPresent(system) { _, previous ->
      previous.copy(
        available = true,
        error = null,
        lastAttemptEpochMillis = at,
        failedRenders = failedRenders,
      )
    }
  }

  fun recordFailure(system: String, reason: String) {
    val at = clock()
    states.computeIfPresent(system) { _, previous ->
      // A refresh is staged before swap, so failure leaves an earlier usable copy available.
      previous.copy(error = oneLine(reason), lastAttemptEpochMillis = at)
    }
  }

  /**
   * Stable configured-order snapshot, safe to iterate without holding a lock. Taken under [lock] so
   * a concurrent [add]/[remove] can't tear the ordering; an entry retired between the two reads is
   * dropped rather than throwing.
   */
  fun snapshot(): List<State> =
    synchronized(lock) { ordered.toList() }.mapNotNull { states[it.system] }

  /**
   * The same catalogs as [snapshot], in the order the **initial fetch** should walk them: highest
   * [Config.loadPriority] first, ties keeping configured order (the sort is stable).
   *
   * Separate from [snapshot] on purpose. Configured order is the front page's, and the two wants
   * genuinely differ: the queue wants the box's load-bearing catalogs back first after a restart,
   * while the index wants sections and cards where the operator put them. Sorting the tracker
   * itself would have moved the cards — and moved [firstAvailableSystem], which is the session the
   * readiness probe renders — as a side effect of a fetch-order preference (issue #4231).
   */
  fun loadOrder(): List<State> = snapshot().sortedByDescending { it.config.loadPriority }

  /** Catalogs with a usable registered copy; used to seed only successful branch heads. */
  /**
   * Every catalog this server is **configured** to serve, whether or not it loaded.
   *
   * The distinction that matters to the theme cache's sweeper: a configured system that failed to
   * load must keep its warmed renders (a fetch can fail transiently, and re-warming m3-catalog
   * costs ~28 hours), while a system no longer configured at all has renders nothing can ever read
   * again and must be reclaimed. Absence from [availableSystems] alone cannot tell those apart.
   */
  fun configuredSystems(): Set<String> = states.keys.toSet()

  fun availableSystems(): Set<String> =
    states.values.asSequence().filter { it.available }.map { it.config.system }.toSet()

  /** True only after every explicitly configured catalog has a usable registered copy. */
  fun allAvailable(): Boolean = states.values.all { it.available }

  fun startupSummary(): String {
    val current = snapshot()
    val available = current.count { it.available }
    val failed = current.filter { !it.available && it.error != null }
    return buildString {
      append("catalogs ").append(available).append('/').append(current.size).append(" loaded")
      if (failed.isNotEmpty()) {
        append("; failed: ")
        append(failed.joinToString(", ") { it.config.system })
      }
    }
  }

  private fun oneLine(reason: String): String =
    reason.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "unknown error"
}
