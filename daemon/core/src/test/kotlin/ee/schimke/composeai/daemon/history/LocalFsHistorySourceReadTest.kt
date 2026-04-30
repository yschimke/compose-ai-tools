package ee.schimke.composeai.daemon.history

import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the read contract for [LocalFsHistorySource]:
 * - [list] returns newest-first by `timestamp` field (lexical order matches chronological since
 *   ISO-8601 UTC).
 * - `previewId` / `since` / `until` / `branch` filters narrow the result.
 * - Pagination cursor is opaque, round-trips correctly, and the second page picks up where the
 *   first left off.
 * - [read] resolves an entry by id and returns the absolute PNG path; missing entries return null.
 */
class LocalFsHistorySourceReadTest {

  private lateinit var tmpDir: java.nio.file.Path
  private lateinit var source: LocalFsHistorySource

  @Before
  fun setUp() {
    tmpDir = Files.createTempDirectory("history-read-test")
    source = LocalFsHistorySource(historyDir = tmpDir)
  }

  @After
  fun tearDown() {
    tmpDir.toFile().deleteRecursively()
  }

  @Test
  fun list_returns_newest_first_across_multiple_previews() {
    writeEntry(previewId = "A", timestampField = "2026-04-30T10:00:00Z", suffix = "01")
    writeEntry(previewId = "B", timestampField = "2026-04-30T10:00:05Z", suffix = "02")
    writeEntry(previewId = "A", timestampField = "2026-04-30T10:00:10Z", suffix = "03")

    val page = source.list(HistoryFilter())
    assertEquals(3, page.entries.size)
    assertEquals(3, page.totalCount)
    // Newest first.
    assertEquals("2026-04-30T10:00:10Z", page.entries[0].timestamp)
    assertEquals("2026-04-30T10:00:05Z", page.entries[1].timestamp)
    assertEquals("2026-04-30T10:00:00Z", page.entries[2].timestamp)
  }

  @Test
  fun previewId_filter_narrows_result() {
    writeEntry(previewId = "A", timestampField = "2026-04-30T10:00:00Z", suffix = "01")
    writeEntry(previewId = "B", timestampField = "2026-04-30T10:00:05Z", suffix = "02")
    writeEntry(previewId = "A", timestampField = "2026-04-30T10:00:10Z", suffix = "03")

    val page = source.list(HistoryFilter(previewId = "A"))
    assertEquals(2, page.entries.size)
    assertTrue(page.entries.all { it.previewId == "A" })
  }

  @Test
  fun since_until_filter_narrows_result() {
    writeEntry(previewId = "A", timestampField = "2026-04-30T10:00:00Z", suffix = "01")
    writeEntry(previewId = "A", timestampField = "2026-04-30T10:00:05Z", suffix = "02")
    writeEntry(previewId = "A", timestampField = "2026-04-30T10:00:10Z", suffix = "03")

    val page =
      source.list(HistoryFilter(since = "2026-04-30T10:00:03Z", until = "2026-04-30T10:00:08Z"))
    assertEquals(1, page.entries.size)
    assertEquals("2026-04-30T10:00:05Z", page.entries[0].timestamp)
  }

  @Test
  fun branch_filter_narrows_result() {
    writeEntry(
      previewId = "A",
      timestampField = "2026-04-30T10:00:00Z",
      suffix = "01",
      git = GitInfo(branch = "main", commit = "deadbeef", shortCommit = "deadbee", dirty = false),
    )
    writeEntry(
      previewId = "A",
      timestampField = "2026-04-30T10:00:05Z",
      suffix = "02",
      git =
        GitInfo(branch = "agent/foo", commit = "cafef00d", shortCommit = "cafef00", dirty = false),
    )
    val page = source.list(HistoryFilter(branch = "agent/foo"))
    assertEquals(1, page.entries.size)
    assertEquals("agent/foo", page.entries[0].git?.branch)
  }

  @Test
  fun pagination_cursor_round_trips_correctly() {
    // Five entries, limit = 2, so we expect: page1 (newest 2) → cursor → page2 (next 2) → cursor →
    // page3 (last 1, no cursor).
    val timestamps =
      listOf(
        "2026-04-30T10:00:00Z",
        "2026-04-30T10:00:01Z",
        "2026-04-30T10:00:02Z",
        "2026-04-30T10:00:03Z",
        "2026-04-30T10:00:04Z",
      )
    timestamps.forEachIndexed { idx, ts ->
      writeEntry(previewId = "A", timestampField = ts, suffix = "%02d".format(idx))
    }
    val page1 = source.list(HistoryFilter(limit = 2))
    assertEquals(2, page1.entries.size)
    assertEquals(5, page1.totalCount)
    assertNotNull(page1.nextCursor)
    val page2 = source.list(HistoryFilter(limit = 2, cursor = page1.nextCursor))
    assertEquals(2, page2.entries.size)
    assertNotNull(page2.nextCursor)
    val page3 = source.list(HistoryFilter(limit = 2, cursor = page2.nextCursor))
    assertEquals(1, page3.entries.size)
    assertNull("last page must not return a cursor", page3.nextCursor)
    // Concatenated entries must equal the entire newest-first order.
    val all = (page1.entries + page2.entries + page3.entries).map { it.timestamp }
    assertEquals(timestamps.reversed(), all)
  }

  @Test
  fun read_returns_entry_and_resolves_png_path() {
    val (id, _) =
      writeEntry(previewId = "A", timestampField = "2026-04-30T10:00:00Z", suffix = "01")
    val read = source.read(id, includeBytes = false)
    assertNotNull(read)
    assertEquals(id, read!!.entry.id)
    assertTrue(Files.exists(java.nio.file.Path.of(read.pngPath)))
    assertNull(read.pngBytes)
  }

  @Test
  fun read_with_inline_returns_bytes() {
    val (id, bytes) =
      writeEntry(previewId = "A", timestampField = "2026-04-30T10:00:00Z", suffix = "01")
    val read = source.read(id, includeBytes = true)
    assertNotNull(read)
    assertNotNull(read!!.pngBytes)
    assertTrue(bytes.contentEquals(read.pngBytes!!))
  }

  @Test
  fun read_returns_null_for_missing_id() {
    val read = source.read("does-not-exist", includeBytes = false)
    assertNull(read)
  }

  /**
   * Writes one synthetic entry. Returns the id and the PNG bytes for callers that want to assert on
   * round-trip identity (e.g. inline-bytes test).
   */
  private fun writeEntry(
    previewId: String,
    timestampField: String,
    suffix: String,
    git: GitInfo? = null,
  ): Pair<String, ByteArray> {
    val bytes = "preview-$previewId-$suffix".toByteArray()
    val hash = LocalFsHistorySource.sha256Hex(bytes)
    val id = "$timestampField-$suffix-${hash.take(8)}"
    val entry =
      HistoryEntry(
        id = id,
        previewId = previewId,
        module = ":t",
        timestamp = timestampField,
        pngHash = hash,
        pngSize = bytes.size.toLong(),
        pngPath = "$id.png",
        producer = "daemon",
        trigger = "renderNow",
        source = HistorySourceInfo(kind = "fs", id = "fs:${tmpDir.toAbsolutePath()}"),
        git = git,
        renderTookMs = 1L,
      )
    source.write(entry, bytes)
    return Pair(id, bytes)
  }
}
