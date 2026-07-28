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
 * availability deliberately does not gate server readiness: a usable server with a partial external
 * catalog set should still deploy.
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
  )

  /** Immutable snapshot of one catalog's current availability and latest attempt. */
  data class State(
    val config: Config,
    val available: Boolean = false,
    val error: String? = null,
    val lastAttemptEpochMillis: Long? = null,
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

  /** Retire a catalog. Returns false when it wasn't configured. */
  fun remove(system: String): Boolean =
    synchronized(lock) {
      if (states.remove(system) == null) return false
      ordered.removeAll { it.system == system }
      true
    }

  /** The configured entry for [system], or null when it isn't served here. */
  fun configFor(system: String): Config? = states[system]?.config

  fun record(result: ServeCatalogStore.Result) {
    when (result) {
      is ServeCatalogStore.Result.Ok -> recordSuccess(result.system)
      is ServeCatalogStore.Result.Failed -> recordFailure(result.system, result.reason)
    }
  }

  fun recordSuccess(system: String) {
    val at = clock()
    states.computeIfPresent(system) { _, previous ->
      previous.copy(available = true, error = null, lastAttemptEpochMillis = at)
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

  /** Catalogs with a usable registered copy; used to seed only successful branch heads. */
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
