package ee.schimke.composeai.cli

import ee.schimke.composeai.daemon.history.HistoryEntry
import ee.schimke.composeai.daemon.history.HistorySourceInfo
import ee.schimke.composeai.daemon.history.LocalFsHistorySource
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class HistoryCommandsTest {
  private val json = Json {
    prettyPrint = false
    encodeDefaults = true
    ignoreUnknownKeys = true
  }

  @Test
  fun `history list response pins schema and returns newest entries first`() {
    val projectDir = tempModule()
    val source = LocalFsHistorySource(projectDir.resolve(".compose-preview-history").toPath())
    source.write(historyEntry(source, id = "old", timestamp = "2026-05-03T07:00:00Z"), bytes("old"))
    source.write(historyEntry(source, id = "new", timestamp = "2026-05-03T07:01:00Z"), bytes("new"))

    val response = listLocalHistory(listOf(PreviewModule("app", projectDir)))
    val encoded = json.encodeToString(CliHistoryListResponse.serializer(), response)
    val parsed = json.decodeFromString(CliHistoryListResponse.serializer(), encoded)

    assertEquals(HISTORY_LIST_SCHEMA, parsed.schema)
    assertTrue(""""schema":"$HISTORY_LIST_SCHEMA"""" in encoded)
    assertEquals(2, parsed.totalCount)
    assertEquals(listOf("new", "old"), parsed.entries.map { it.id })
  }

  @Test
  fun `history list reports total matches before module pagination`() {
    val projectDir = tempModule()
    val source = LocalFsHistorySource(projectDir.resolve(".compose-preview-history").toPath())
    repeat(LocalFsHistorySource.MAX_LIMIT + 1) { index ->
      source.write(
        historyEntry(
          source,
          id = "entry-$index",
          timestamp = "2026-05-03T07:${(index % 60).toString().padStart(2, '0')}:00Z",
        ),
        bytes("entry-$index"),
      )
    }

    val response = listLocalHistory(listOf(PreviewModule("app", projectDir)), limit = 1)

    assertEquals(LocalFsHistorySource.MAX_LIMIT + 1, response.totalCount)
    assertEquals(1, response.entries.size)
  }

  @Test
  fun `history diff response reports png hash changes and includes metadata`() {
    val projectDir = tempModule()
    val source = LocalFsHistorySource(projectDir.resolve(".compose-preview-history").toPath())
    source.write(historyEntry(source, id = "a", timestamp = "2026-05-03T07:00:00Z"), bytes("a"))
    source.write(historyEntry(source, id = "b", timestamp = "2026-05-03T07:01:00Z"), bytes("b"))

    val response =
      assertNotNull(
        diffLocalHistory(listOf(PreviewModule("app", projectDir)), fromId = "a", toId = "b")
      )
    val encoded = json.encodeToString(CliHistoryDiffResponse.serializer(), response)
    val parsed = json.decodeFromString(CliHistoryDiffResponse.serializer(), encoded)

    assertEquals(HISTORY_DIFF_SCHEMA, parsed.schema)
    assertTrue(parsed.pngHashChanged)
    assertEquals("a", parsed.fromMetadata.id)
    assertEquals("b", parsed.toMetadata.id)
  }

  @Test
  fun `history diff returns null for missing entries`() {
    val projectDir = tempModule()
    LocalFsHistorySource(projectDir.resolve(".compose-preview-history").toPath())

    assertNull(
      diffLocalHistory(
        listOf(PreviewModule("app", projectDir)),
        fromId = "missing-a",
        toId = "missing-b",
      )
    )
  }

  private fun tempModule(): File = Files.createTempDirectory("history-command-test").toFile()

  private fun historyEntry(
    source: LocalFsHistorySource,
    id: String,
    timestamp: String,
    previewId: String = "com.example.Foo",
  ): HistoryEntry {
    val png = bytes(id)
    return HistoryEntry(
      id = id,
      previewId = previewId,
      module = ":app",
      timestamp = timestamp,
      pngHash = LocalFsHistorySource.sha256Hex(png),
      pngSize = png.size.toLong(),
      pngPath = "$id.png",
      producer = "daemon",
      trigger = "manual",
      source = HistorySourceInfo(kind = source.kind, id = source.id),
      renderTookMs = 1,
    )
  }

  private fun bytes(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)
}
