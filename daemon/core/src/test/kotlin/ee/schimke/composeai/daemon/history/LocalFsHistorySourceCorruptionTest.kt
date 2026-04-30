package ee.schimke.composeai.daemon.history

import java.nio.file.Files
import java.nio.file.StandardOpenOption
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * Pins reader-side robustness from HISTORY.md § "Concurrency model" / § "Provenance trust":
 * - Truncated index lines are skipped with a warn log, not a parse exception.
 * - Index entries that reference a sidecar that doesn't exist on disk are silently dropped from the
 *   listing — "self-healing on next prune".
 */
class LocalFsHistorySourceCorruptionTest {

  private lateinit var tmpDir: java.nio.file.Path

  @Before
  fun setUp() {
    tmpDir = Files.createTempDirectory("history-corruption-test")
  }

  @After
  fun tearDown() {
    tmpDir.toFile().deleteRecursively()
  }

  @Test
  fun truncated_index_lines_and_missing_sidecars_are_skipped() {
    val source = LocalFsHistorySource(historyDir = tmpDir)
    // Write one valid entry — establishes the per-preview directory + index.
    val bytes = byteArrayOf(1, 2, 3)
    val hash = LocalFsHistorySource.sha256Hex(bytes)
    val validId = "ts-01-${hash.take(8)}"
    val validEntry = makeEntry(id = validId, hash = hash, size = bytes.size.toLong())
    source.write(validEntry, bytes)

    // Append a truncated line + a syntactically-valid line for an entry with no sidecar on disk.
    val orphanId = "ts-02-deadbeef"
    val orphanEntry =
      makeEntry(id = orphanId, hash = "deadbeef".repeat(8), size = 100L)
        .copy(timestamp = "2026-04-30T11:00:00Z")
    val indexFile = tmpDir.resolve(LocalFsHistorySource.INDEX_FILENAME)
    val truncated = "{\"id\":\"truncated\",\"previewId\":" // Deliberately incomplete.
    val orphanLine =
      kotlinx.serialization.json.Json.encodeToString(HistoryEntry.serializer(), orphanEntry)
    Files.write(
      indexFile,
      ("\n" + truncated + "\n" + orphanLine + "\n").toByteArray(),
      StandardOpenOption.APPEND,
    )

    val page = source.list(HistoryFilter())
    // Only the original valid entry should survive — truncated line + orphan reference both
    // filtered.
    assertEquals(1, page.entries.size)
    assertEquals(validId, page.entries[0].id)
    assertNotNull(page.entries[0])
  }

  private fun makeEntry(id: String, hash: String, size: Long): HistoryEntry =
    HistoryEntry(
      id = id,
      previewId = "Foo",
      module = ":t",
      timestamp = "2026-04-30T10:00:00Z",
      pngHash = hash,
      pngSize = size,
      pngPath = "$id.png",
      producer = "daemon",
      trigger = "renderNow",
      source = HistorySourceInfo(kind = "fs", id = "fs:${tmpDir.toAbsolutePath()}"),
      renderTookMs = 1L,
    )
}
