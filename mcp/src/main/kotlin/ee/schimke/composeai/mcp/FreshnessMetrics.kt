package ee.schimke.composeai.mcp

import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Counters surfaced via the `status` MCP tool. Three buckets:
 *
 * 1. **Probe outcomes** — every call to `ensureSourceFreshBeforeRender` lands in exactly one of
 *    these buckets, so an operator can see the rate of "actually changed" vs "unchanged" vs
 *    "couldn't tell" decisions and compare against the rate of agent-perceived stale renders.
 * 2. **Polling cycles** — the background source-freshness poller's run count + scan/fire totals. A
 *    poller that scans many previews but never fires means agents are calling `resources/read` fast
 *    enough to catch every edit; a poller that fires often means real edits are slipping through
 *    the on-demand probe.
 * 3. **Random sampling** — the deterministic-render probe's totals: how many probes ran, how many
 *    came back with `unchanged: true` (deterministic), and how many came back with mismatched bytes
 *    despite no source change (non-deterministic — points at clock-reading composables, daemon
 *    classloader bugs, or build-output drift). Recent non-deterministic URIs are kept in a small
 *    ring buffer so the operator can spot offending previews.
 */
class FreshnessMetrics {

  // Probe outcomes — bumped from inside ensureSourceFreshBeforeRender. Mutually exclusive: every
  // call increments `probesTotal` and exactly one of the outcome counters.
  val probesTotal = AtomicLong()
  val probesNoEntry = AtomicLong()
  val probesNoSource = AtomicLong()
  val probesFirstSighting = AtomicLong()
  val probesUnchangedByHash = AtomicLong()
  val probesUnchangedNoBaseline = AtomicLong()
  val probesChangedByMtime = AtomicLong()
  val probesChangedByHash = AtomicLong()

  // Polling counters — the background poller bumps these as it walks the catalog.
  val pollingCycles = AtomicLong()
  val pollingPreviewsScanned = AtomicLong()
  val pollingChangesDetected = AtomicLong()

  // Random-sampling counters — the deterministic-render probe bumps these.
  val samplingProbes = AtomicLong()
  val samplingSkippedBusy = AtomicLong()
  val samplingDeterministic = AtomicLong()
  val samplingNondeterministic = AtomicLong()

  /**
   * Last-seen timestamp for previews observed to render non-deterministically. Insertion-ordered;
   * the oldest entry is evicted past [MAX_RECENT_NONDETERMINISTIC]. Synchronised because the
   * sampling thread and the status-tool thread both touch it.
   */
  private val recentNondeterministic = LinkedHashMap<String, Long>()

  @Synchronized
  fun recordNondeterministic(uri: String) {
    recentNondeterministic.remove(uri)
    recentNondeterministic[uri] = System.currentTimeMillis()
    while (recentNondeterministic.size > MAX_RECENT_NONDETERMINISTIC) {
      val oldest = recentNondeterministic.keys.iterator().next()
      recentNondeterministic.remove(oldest)
    }
  }

  @Synchronized
  fun snapshotNondeterministic(): Map<String, Long> = LinkedHashMap(recentNondeterministic)

  fun toJson(): JsonObject = buildJsonObject {
    putJsonObject("probes") {
      put("total", JsonPrimitive(probesTotal.get()))
      put("noEntry", JsonPrimitive(probesNoEntry.get()))
      put("noSource", JsonPrimitive(probesNoSource.get()))
      put("firstSighting", JsonPrimitive(probesFirstSighting.get()))
      put("unchangedByHash", JsonPrimitive(probesUnchangedByHash.get()))
      put("unchangedNoBaseline", JsonPrimitive(probesUnchangedNoBaseline.get()))
      put("changedByMtime", JsonPrimitive(probesChangedByMtime.get()))
      put("changedByHash", JsonPrimitive(probesChangedByHash.get()))
    }
    putJsonObject("polling") {
      put("cycles", JsonPrimitive(pollingCycles.get()))
      put("previewsScanned", JsonPrimitive(pollingPreviewsScanned.get()))
      put("changesDetected", JsonPrimitive(pollingChangesDetected.get()))
    }
    putJsonObject("sampling") {
      put("probes", JsonPrimitive(samplingProbes.get()))
      put("skippedBusy", JsonPrimitive(samplingSkippedBusy.get()))
      put("deterministic", JsonPrimitive(samplingDeterministic.get()))
      put("nondeterministic", JsonPrimitive(samplingNondeterministic.get()))
      putJsonArray("recentNondeterministicUris") {
        snapshotNondeterministic().forEach { (uri, ts) ->
          add(
            buildJsonObject {
              put("uri", uri)
              put("lastSeenMs", JsonPrimitive(ts))
            }
          )
        }
      }
    }
  }

  companion object {
    private const val MAX_RECENT_NONDETERMINISTIC = 32
  }
}
