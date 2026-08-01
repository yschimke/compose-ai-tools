package ee.schimke.composeai.daemon.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryFiltersTest {

  @Test
  fun matches_uses_only_entry_fields_shared_by_all_sources() {
    val local = entry(id = "local", sourceKind = "fs", sourceId = "fs:/history")
    val git =
      local.copy(id = "git", source = HistorySourceInfo(kind = "git", id = "git:preview/main"))
    val shared =
      HistoryFilter(
        previewId = "com.example.Card",
        since = "2026-04-30T10:00:00Z",
        until = "2026-04-30T11:00:00Z",
        branch = "codex/history",
        commit = "abcdef0",
        worktreePath = "/repo",
        agentId = "agent-1",
      )

    assertTrue(
      HistoryFilters.matches(local, shared.copy(sourceKind = "fs", sourceId = "fs:/history"))
    )
    assertTrue(
      HistoryFilters.matches(git, shared.copy(sourceKind = "git", sourceId = "git:preview/main"))
    )
    assertFalse(HistoryFilters.matches(local, shared.copy(sourceKind = "git")))
  }

  @Test
  fun matches_supports_short_commit_and_branch_regex() {
    val matching =
      entry(branch = "codex/history-filters", commit = "abcdef012345", short = "abcdef0")

    assertTrue(HistoryFilters.matches(matching, HistoryFilter(commit = "abcdef0")))
    assertTrue(HistoryFilters.matches(matching, HistoryFilter(branchPattern = "codex/.*")))
    assertFalse(HistoryFilters.matches(matching, HistoryFilter(branchPattern = "main")))
  }

  @Test
  fun ref_is_routing_only_not_a_match_clause() {
    assertTrue(HistoryFilters.matches(entry(), HistoryFilter(ref = "refs/heads/preview/main")))
  }

  @Test
  fun paginate_returns_limit_bounded_slice_and_cursor_after_last_entry() {
    val entries = (1..4).map { i -> entry(id = "id-$i", timestamp = "2026-04-30T10:0$i:00Z") }

    val first = HistoryFilters.paginate(entries, HistoryFilter(limit = 2))
    assertEquals(listOf("id-1", "id-2"), first.entries.map { it.id })
    assertEquals(HistoryFilters.encodeCursor("2026-04-30T10:02:00Z", "id-2"), first.nextCursor)

    val second =
      HistoryFilters.paginate(entries, HistoryFilter(limit = 2, cursor = first.nextCursor))
    assertEquals(listOf("id-3", "id-4"), second.entries.map { it.id })
    assertNull(second.nextCursor)
  }

  @Test
  fun invalid_cursor_returns_empty_tail() {
    val page = HistoryFilters.paginate(listOf(entry()), HistoryFilter(cursor = "not base64"))

    assertEquals(emptyList<HistoryEntry>(), page.entries)
    assertNull(page.nextCursor)
  }

  private fun entry(
    id: String = "id-1",
    timestamp: String = "2026-04-30T10:12:34Z",
    branch: String = "codex/history",
    commit: String = "abcdef012345",
    short: String = "abcdef0",
    sourceKind: String = "fs",
    sourceId: String = "fs:/history",
  ): HistoryEntry =
    HistoryEntry(
      id = id,
      previewId = "com.example.Card",
      module = ":app",
      timestamp = timestamp,
      pngHash = "hash-$id",
      pngSize = 12L,
      pngPath = "$id.png",
      producer = "daemon",
      trigger = "renderNow",
      source = HistorySourceInfo(kind = sourceKind, id = sourceId),
      worktree = WorktreeInfo(path = "/repo", id = "repo", agentId = "agent-1"),
      git = GitInfo(branch = branch, commit = commit, shortCommit = short, dirty = false),
      renderTookMs = 7L,
    )
}
