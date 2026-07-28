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
  data class Config(val system: String, val listed: Boolean, val repo: String, val branch: String)

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

  private val ordered =
    configured
      .distinctBy { it.system }
      .also { require(it.size == configured.size) { "duplicate catalog system id" } }
  private val states = ConcurrentHashMap(ordered.associate { it.system to State(it) })

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

  /** Stable configured-order snapshot, safe to iterate without holding a lock. */
  fun snapshot(): List<State> = ordered.map { states.getValue(it.system) }

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
