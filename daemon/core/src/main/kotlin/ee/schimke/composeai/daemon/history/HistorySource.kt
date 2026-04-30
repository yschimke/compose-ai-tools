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
 * effects"); H1+H2 only have `LocalFsHistorySource` so these are effectively no-ops, but the
 * shape lets H9+ multi-source merging plug in without re-shaping the filter.
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
 * Pluggable history backend — see HISTORY.md § "HistorySource interface".
 *
 * H1+H2 ships only [LocalFsHistorySource]. Future phases (H10 onward) will add `GitRefHistorySource`
 * and `HttpMirrorHistorySource`; the consumer side merges across configured sources by `pngHash +
 * previewId + git.commit`. The interface is intentionally narrow: write is a side-effect of
 * [HistoryManager.recordRender]; read is paginated; watch is reserved for future phases (a UI
 * doesn't poll, it subscribes to `historyAdded`).
 */
interface HistorySource {
  /** Stable identifier — e.g. `"fs:/abs/historyDir"`, `"git:preview/main"`, `"http:https://…"`. */
  val id: String

  /** `kind` from [HistorySourceInfo] — `"fs"`, `"git"`, `"http"`. */
  val kind: String

  /** True when this source can accept [write] calls. FS=true; git/HTTP read-only sources=false. */
  fun supportsWrites(): Boolean

  /**
   * Persists [entry] (and its associated PNG bytes) into the backing store. Throws when this
   * source isn't writable — gate via [supportsWrites] first.
   *
   * Failures here must NOT be load-bearing for the render itself — `HistoryManager.recordRender`
   * catches and logs, then continues. The render succeeded; history is observation, not state.
   */
  fun write(entry: HistoryEntry, png: ByteArray)

  /** Lists history entries newest-first, applying [filter] and paginating. */
  fun list(filter: HistoryFilter): HistoryListPage

  /** Reads one entry by id. Returns null when the id isn't present in this source. */
  fun read(entryId: String, includeBytes: Boolean = false): HistoryReadResult?
}
