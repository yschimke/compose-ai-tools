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
 * Counters surfaced via the `status` MCP tool. Four buckets:
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
 * 4. **Forces** — every `render_preview.force` call. Bumped from
 *    `DaemonMcpServer.invalidateClasspathForForce`; the recent-reasons ring buffer is what an
 *    operator pastes into a comment on issue #924 when reporting the gap that made the agent reach
 *    for the escape hatch.
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

  // Manifest reload counters — bumped when the poller re-reads `previews.json` because its
  // mtime+hash advanced. Lets an operator confirm the issue-#834 path actually fires when
  // Gradle's `discoverPreviews` rewrites the manifest between renders.
  val manifestStats = AtomicLong()
  val manifestRereads = AtomicLong()
  val manifestPreviewsAdded = AtomicLong()
  val manifestPreviewsRemoved = AtomicLong()

  // Random-sampling counters — the deterministic-render probe bumps these.
  val samplingProbes = AtomicLong()
  val samplingSkippedBusy = AtomicLong()
  val samplingDeterministic = AtomicLong()
  val samplingNondeterministic = AtomicLong()

  // Force counters — bumped by `render_preview` when an agent passed `force.reason`. Each call here
  // is an agent telling us our freshness logic missed an edit; the goal is for this to stay near
  // zero. See https://github.com/yschimke/compose-ai-tools/issues/924.
  val forcesUsed = AtomicLong()

  /**
   * Last-seen timestamp for previews observed to render non-deterministically. Insertion-ordered;
   * the oldest entry is evicted past [MAX_RECENT_NONDETERMINISTIC]. Synchronised because the
   * sampling thread and the status-tool thread both touch it.
   */
  private val recentNondeterministic = LinkedHashMap<String, Long>()

  /**
   * Recent `render_preview.force.reason` strings, paired with the URI they fired against.
   * Insertion-ordered; oldest evicted past [MAX_RECENT_FORCES]. Surfaced via `status` so an
   * operator can see why agents reached for the escape hatch and link the report on issue #924.
   */
  private val recentForces = ArrayDeque<ForceRecord>()

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

  @Synchronized
  fun recordForce(uri: String, reason: String) {
    forcesUsed.incrementAndGet()
    recentForces.addLast(ForceRecord(uri = uri, reason = reason, atMs = System.currentTimeMillis()))
    while (recentForces.size > MAX_RECENT_FORCES) recentForces.removeFirst()
  }

  @Synchronized fun snapshotForces(): List<ForceRecord> = recentForces.toList()

  data class ForceRecord(val uri: String, val reason: String, val atMs: Long)

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
    putJsonObject("manifest") {
      put("stats", JsonPrimitive(manifestStats.get()))
      put("rereads", JsonPrimitive(manifestRereads.get()))
      put("previewsAdded", JsonPrimitive(manifestPreviewsAdded.get()))
      put("previewsRemoved", JsonPrimitive(manifestPreviewsRemoved.get()))
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
    putJsonObject("forces") {
      put("used", JsonPrimitive(forcesUsed.get()))
      putJsonArray("recent") {
        snapshotForces().forEach { record ->
          add(
            buildJsonObject {
              put("uri", record.uri)
              put("reason", record.reason)
              put("atMs", JsonPrimitive(record.atMs))
            }
          )
        }
      }
    }
  }

  companion object {
    private const val MAX_RECENT_NONDETERMINISTIC = 32
    private const val MAX_RECENT_FORCES = 16
  }
}
