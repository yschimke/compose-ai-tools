package ee.schimke.composeai.daemon.history

/**
 * Filter for [HistorySource.list]. Mirrors `history/list` params from HISTORY.md § "Layer 2 —
 * JSON-RPC API" / § "Worktree-aware listing". All filters are optional; missing filter ⇒ no
 * constraint on that dimension.
 *
 * `since` / `until` accept ISO-8601 timestamps; the source applies a string-comparison range over
 * `HistoryEntry.timestamp` (which is itself ISO-8601 UTC, so lexical order = chronological order).
 *
 * `branch` is exact match; [branchPattern] is a regex for "branch starts with `agent/`" style
 * filters. Forward-compat for cross-worktree merging in H6+ — H1+H2 only see entries from one
 * source so most filters land mostly-redundant, but the read API still honours them.
 *
 * `sourceKind` / `sourceId` filter by storage backend identity (HISTORY.md § "Wire-format
 * effects"); H1+H2 only have `LocalFsHistorySource` so these are effectively no-ops, but the shape
 * lets H9+ multi-source merging plug in without re-shaping the filter.
 */
data class HistoryFilter(
  val previewId: String? = null,
  val since: String? = null,
  val until: String? = null,
  val limit: Int? = null,
  val cursor: String? = null,
  val branch: String? = null,
  val branchPattern: String? = null,
  val commit: String? = null,
  val worktreePath: String? = null,
  val agentId: String? = null,
  val sourceKind: String? = null,
  val sourceId: String? = null,
)

/**
 * One page of history entries returned by [HistorySource.list]. [entries] are newest-first;
 * [nextCursor] is non-null when more entries match the filter beyond [HistoryFilter.limit];
 * [totalCount] is the count of entries matching the filter BEFORE pagination, so a UI can show
 * "showing 50 of 312".
 */
data class HistoryListPage(
  val entries: List<HistoryEntry>,
  val nextCursor: String? = null,
  val totalCount: Int,
)

/**
 * Result of [HistorySource.read]. [pngPath] is the absolute path to the bytes; [pngBytes] is
 * non-null only when the caller asked for inline bytes (e.g. an MCP client over a remote stdio).
 */
data class HistoryReadResult(
  val entry: HistoryEntry,
  val previewMetadata: PreviewMetadataSnapshot?,
  val pngPath: String,
  val pngBytes: ByteArray? = null,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is HistoryReadResult) return false
    if (entry != other.entry) return false
    if (previewMetadata != other.previewMetadata) return false
    if (pngPath != other.pngPath) return false
    if (pngBytes == null) return other.pngBytes == null
    if (other.pngBytes == null) return false
    return pngBytes.contentEquals(other.pngBytes)
  }

  override fun hashCode(): Int {
    var result = entry.hashCode()
    result = 31 * result + (previewMetadata?.hashCode() ?: 0)
    result = 31 * result + pngPath.hashCode()
    result = 31 * result + (pngBytes?.contentHashCode() ?: 0)
    return result
  }
}

/**
 * Outcome of [HistorySource.write].
 *
 * `WRITTEN` — entry persisted (sidecar + index line, and either a fresh PNG or a dedup-by-hash
 * pointer at an existing one).
 *
 * `SKIPPED_DUPLICATE` — the bytes match the most recent existing entry for this `previewId`, so the
 * source skipped writing both the sidecar and the index line. `HistoryManager.recordRender` uses
 * this to suppress `historyAdded` and avoid cluttering the consumer's UI with redundant entries
 * for save-loops that produce identical pixels (e.g. saving a comment-only edit, repeated focus
 * cycles, sandbox warm-up renders against the same composition state).
 *
 * The "most recent" rule is what distinguishes this from the dedup-by-hash PNG path (where any
 * earlier matching hash means we re-use the file). If render history goes A → B → A, the third
 * render is a meaningful event ("we went back to A") and is kept; render history A → A → A skips
 * everything after the first.
 */
enum class WriteResult {
  WRITTEN,
  SKIPPED_DUPLICATE,
}

/**
 * Pluggable history backend — see HISTORY.md § "HistorySource interface".
 *
 * H1+H2 ships only [LocalFsHistorySource]. Future phases (H10 onward) will add
 * `GitRefHistorySource` and `HttpMirrorHistorySource`; the consumer side merges across configured
 * sources by `pngHash + previewId + git.commit`. The interface is intentionally narrow: write is a
 * side-effect of [HistoryManager.recordRender]; read is paginated; watch is reserved for future
 * phases (a UI doesn't poll, it subscribes to `historyAdded`).
 */
interface HistorySource {
  /** Stable identifier — e.g. `"fs:/abs/historyDir"`, `"git:preview/main"`, `"http:https://…"`. */
  val id: String

  /** `kind` from [HistorySourceInfo] — `"fs"`, `"git"`, `"http"`. */
  val kind: String

  /** True when this source can accept [write] calls. FS=true; git/HTTP read-only sources=false. */
  fun supportsWrites(): Boolean

  /**
   * Persists [entry] (and its associated PNG bytes) into the backing store. Throws when this source
   * isn't writable — gate via [supportsWrites] first.
   *
   * Returns [WriteResult.WRITTEN] when the entry landed and [WriteResult.SKIPPED_DUPLICATE] when
   * the bytes match the most recent existing entry for the same `previewId` and the source elected
   * to suppress the write. Callers (notably [HistoryManager.recordRender]) use that to decide
   * whether to fan out a `historyAdded` notification.
   *
   * Failures here must NOT be load-bearing for the render itself — `HistoryManager.recordRender`
   * catches and logs, then continues. The render succeeded; history is observation, not state.
   */
  fun write(entry: HistoryEntry, png: ByteArray): WriteResult

  /** Lists history entries newest-first, applying [filter] and paginating. */
  fun list(filter: HistoryFilter): HistoryListPage

  /** Reads one entry by id. Returns null when the id isn't present in this source. */
  fun read(entryId: String, includeBytes: Boolean = false): HistoryReadResult?

  /**
   * H4 — applies the pruning policy from [config] to this source's storage. Read-only sources
   * (git-ref, HTTP) return [PruneResult.EMPTY] without touching anything; the daemon doesn't write
   * to those backends, so cleanup is the producer's concern.
   *
   * **Pruning order** (HISTORY.md § "Pruning policy"):
   * 1. Age — drop entries with `timestamp < now - maxAgeDays`.
   * 2. Per-preview count — keep newest [HistoryPruneConfig.maxEntriesPerPreview] per preview.
   * 3. Total size — if surviving set still exceeds [HistoryPruneConfig.maxTotalSizeBytes], drop
   *    oldest entries across all previews until under threshold.
   *
   * Each pass operates on the survivor set from the previous; the most-recent entry per preview
   * is NEVER dropped (HISTORY.md: "diff requires at least one prior entry to be useful").
   *
   * **Dry-run mode** ([dryRun] = true) computes the removal set + freed bytes without touching
   * disk. Useful for "what would auto-prune do?" probes from a consumer.
   *
   * Each individual knob set to `0` or negative is treated as disabled — that pass is skipped.
   */
  fun prune(config: HistoryPruneConfig, dryRun: Boolean = false): PruneResult = PruneResult.EMPTY
}
