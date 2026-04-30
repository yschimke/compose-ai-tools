package ee.schimke.composeai.daemon.history

import java.util.Base64

/**
 * Shared `HistoryFilter` matchers and pagination cursor helpers.
 *
 * Extracted from [LocalFsHistorySource] (H1+H2) so [GitRefHistorySource] (H10-read) and any future
 * read-only source ([HttpMirrorHistorySource], H13) apply identical filter / pagination logic
 * without duplicating it across implementations. See HISTORY.md § "HistorySource interface" — every
 * source returns the same shape; only the storage backend differs.
 */
internal object HistoryFilters {

  const val DEFAULT_LIMIT: Int = 50
  const val MAX_LIMIT: Int = 500

  /**
   * Returns true when [entry] satisfies every non-null clause of [filter]. Clauses match the
   * `HistoryFilter` documentation in HISTORY.md § "Worktree-aware listing" + § "Wire-format
   * effects".
   */
  fun matches(entry: HistoryEntry, filter: HistoryFilter): Boolean {
    if (filter.previewId != null && entry.previewId != filter.previewId) return false
    if (filter.since != null && entry.timestamp < filter.since) return false
    if (filter.until != null && entry.timestamp > filter.until) return false
    if (filter.branch != null && entry.git?.branch != filter.branch) return false
    if (filter.branchPattern != null) {
      val branch = entry.git?.branch ?: return false
      if (!Regex(filter.branchPattern).matches(branch)) return false
    }
    if (filter.commit != null) {
      val git = entry.git ?: return false
      if (git.commit != filter.commit && git.shortCommit != filter.commit) return false
    }
    if (filter.worktreePath != null && entry.worktree?.path != filter.worktreePath) return false
    if (filter.agentId != null && entry.worktree?.agentId != filter.agentId) return false
    if (filter.sourceKind != null && entry.source.kind != filter.sourceKind) return false
    if (filter.sourceId != null && entry.source.id != filter.sourceId) return false
    return true
  }

  /**
   * Applies pagination to a newest-first [matched] list using [filter]'s `cursor` and `limit`.
   * Returns the page slice plus the next-cursor (or null when the page is the tail).
   *
   * Cursor is an opaque base64 of `<timestamp>|<id>`; we drop entries until we pass it.
   */
  fun paginate(matched: List<HistoryEntry>, filter: HistoryFilter): PaginatedSlice {
    val afterCursor =
      if (filter.cursor != null) {
        val decoded = decodeCursor(filter.cursor) ?: return PaginatedSlice(emptyList(), null)
        matched.dropWhile { it.timestamp != decoded.timestamp || it.id != decoded.id }.drop(1)
      } else {
        matched
      }
    val limit = (filter.limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
    val page = afterCursor.take(limit)
    val nextCursor =
      if (afterCursor.size > limit) {
        val last = page.last()
        encodeCursor(last.timestamp, last.id)
      } else null
    return PaginatedSlice(entries = page, nextCursor = nextCursor)
  }

  data class PaginatedSlice(val entries: List<HistoryEntry>, val nextCursor: String?)

  fun encodeCursor(timestamp: String, id: String): String {
    val raw = "$timestamp|$id".toByteArray(Charsets.UTF_8)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
  }

  data class CursorPosition(val timestamp: String, val id: String)

  fun decodeCursor(cursor: String): CursorPosition? {
    return try {
      val raw = Base64.getUrlDecoder().decode(cursor)
      val text = String(raw, Charsets.UTF_8)
      val sep = text.indexOf('|')
      if (sep < 0) return null
      CursorPosition(timestamp = text.substring(0, sep), id = text.substring(sep + 1))
    } catch (_: Throwable) {
      null
    }
  }
}
