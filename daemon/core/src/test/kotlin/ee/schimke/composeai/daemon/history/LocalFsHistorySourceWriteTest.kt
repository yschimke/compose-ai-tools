package ee.schimke.composeai.daemon.history

import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the on-disk write contract from HISTORY.md § "On-disk schema":
 * - PNG file lands at `<historyDir>/<sanitisedPreviewId>/<id>.png`.
 * - Sidecar JSON lands at `<historyDir>/<sanitisedPreviewId>/<id>.json` and parses back into the
 *   same [HistoryEntry].
 * - `index.jsonl` accumulates one line per write in append order.
 * - Dedup-by-hash: a second write of identical bytes does NOT re-write the PNG, but DOES write a
 *   sidecar + index line whose `pngPath` points at the original PNG.
 */
class LocalFsHistorySourceWriteTest {

  private lateinit var tmpDir: java.nio.file.Path

  private val json = Json { ignoreUnknownKeys = true }

  @Before
  fun setUp() {
    tmpDir = Files.createTempDirectory("history-write-test")
  }

  @After
  fun tearDown() {
    tmpDir.toFile().deleteRecursively()
  }

  @Test
  fun three_distinct_writes_produce_three_pngs_three_sidecars_three_index_lines() {
    val source = LocalFsHistorySource(historyDir = tmpDir)
    val previewId = "com.example.Foo"
    val sanitised = "com.example.Foo"

    val entries =
      (1..3).map { i ->
        val bytes = byteArrayOf(i.toByte(), 0x00, 0x01)
        val hash = LocalFsHistorySource.sha256Hex(bytes)
        val entry =
          makeEntry(
            id = "ts-$i-${hash.take(8)}",
            previewId = previewId,
            hash = hash,
            size = bytes.size.toLong(),
          )
        source.write(entry, bytes)
        Triple(entry, bytes, hash)
      }

    val previewDir = tmpDir.resolve(sanitised)
    assertTrue(Files.isDirectory(previewDir))
    for ((entry, _, _) in entries) {
      val pngFile = previewDir.resolve("${entry.id}.png")
      val sidecarFile = previewDir.resolve("${entry.id}.json")
      assertTrue("PNG missing for ${entry.id}", Files.exists(pngFile))
      assertTrue("Sidecar missing for ${entry.id}", Files.exists(sidecarFile))
      val text = Files.readString(sidecarFile)
      val decoded = json.decodeFromString(HistoryEntry.serializer(), text)
      assertEquals(entry.id, decoded.id)
      assertEquals(entry.previewId, decoded.previewId)
      assertEquals(entry.pngHash, decoded.pngHash)
      assertEquals("${entry.id}.png", decoded.pngPath)
    }

    val indexLines =
      Files.readAllLines(tmpDir.resolve(LocalFsHistorySource.INDEX_FILENAME)).filter {
        it.isNotBlank()
      }
    assertEquals(3, indexLines.size)
    val ids = indexLines.map { json.decodeFromString(HistoryEntry.serializer(), it).id }
    assertEquals(entries.map { it.first.id }, ids)
  }

  @Test
  fun dedup_by_hash_does_not_write_a_duplicate_png_but_writes_a_sidecar_pointing_at_the_first() {
    val source = LocalFsHistorySource(historyDir = tmpDir)
    val previewId = "Foo"
    val bytes = byteArrayOf(0x10, 0x20, 0x30, 0x40)
    val hash = LocalFsHistorySource.sha256Hex(bytes)

    val firstEntry =
      makeEntry(
        id = "a-${hash.take(8)}",
        previewId = previewId,
        hash = hash,
        size = bytes.size.toLong(),
      )
    val secondEntry =
      makeEntry(
        id = "b-${hash.take(8)}",
        previewId = previewId,
        hash = hash,
        size = bytes.size.toLong(),
      )
    source.write(firstEntry, bytes)
    source.write(secondEntry, bytes)

    val previewDir = tmpDir.resolve("Foo")
    val firstPng = previewDir.resolve("${firstEntry.id}.png")
    val secondPng = previewDir.resolve("${secondEntry.id}.png")
    val secondSidecar = previewDir.resolve("${secondEntry.id}.json")

    assertTrue("First PNG must exist", Files.exists(firstPng))
    assertFalse("Second PNG must NOT exist (dedup)", Files.exists(secondPng))
    assertTrue("Second sidecar must exist", Files.exists(secondSidecar))

    // Second sidecar's pngPath should point at the first PNG's filename.
    val sidecarText = Files.readString(secondSidecar)
    val decoded = json.decodeFromString(HistoryEntry.serializer(), sidecarText)
    assertEquals("${firstEntry.id}.png", decoded.pngPath)

    // Index has both lines, both readable.
    val indexLines =
      Files.readAllLines(tmpDir.resolve(LocalFsHistorySource.INDEX_FILENAME)).filter {
        it.isNotBlank()
      }
    assertEquals(2, indexLines.size)
  }

  @Test
  fun dedup_survives_simulated_cross_restart() {
    // Write entry 1 via source A; throw it away; create source B against the same dir; write entry
    // 2 with the same bytes; assert dedup still hits.
    val previewId = "Bar"
    val bytes = byteArrayOf(0x77, 0x77, 0x77)
    val hash = LocalFsHistorySource.sha256Hex(bytes)
    val firstEntry =
      makeEntry(
        id = "old-${hash.take(8)}",
        previewId = previewId,
        hash = hash,
        size = bytes.size.toLong(),
      )
    LocalFsHistorySource(historyDir = tmpDir).write(firstEntry, bytes)

    val sourceB = LocalFsHistorySource(historyDir = tmpDir)
    val secondEntry =
      makeEntry(
        id = "new-${hash.take(8)}",
        previewId = previewId,
        hash = hash,
        size = bytes.size.toLong(),
      )
    sourceB.write(secondEntry, bytes)

    val previewDir = tmpDir.resolve("Bar")
    val firstPng = previewDir.resolve("${firstEntry.id}.png")
    val secondPng = previewDir.resolve("${secondEntry.id}.png")
    assertTrue(Files.exists(firstPng))
    assertFalse("Cross-restart dedup must skip the duplicate PNG", Files.exists(secondPng))

    val sidecar =
      json.decodeFromString(
        HistoryEntry.serializer(),
        Files.readString(previewDir.resolve("${secondEntry.id}.json")),
      )
    assertEquals("${firstEntry.id}.png", sidecar.pngPath)
  }

  @Test
  fun previewId_with_path_separator_is_sanitised() {
    val source = LocalFsHistorySource(historyDir = tmpDir)
    val rawId = "com/example/foo:bar"
    val bytes = byteArrayOf(1, 2, 3)
    val hash = LocalFsHistorySource.sha256Hex(bytes)
    val entry =
      makeEntry(
        id = "ts-${hash.take(8)}",
        previewId = rawId,
        hash = hash,
        size = bytes.size.toLong(),
      )
    source.write(entry, bytes)

    // sanitisation collapses '/' and ':' to '_'
    assertTrue(Files.isDirectory(tmpDir.resolve("com_example_foo_bar")))
    assertNotNull(Files.list(tmpDir.resolve("com_example_foo_bar")).findFirst().orElse(null))
  }

  private fun makeEntry(id: String, previewId: String, hash: String, size: Long): HistoryEntry =
    HistoryEntry(
      id = id,
      previewId = previewId,
      module = ":t",
      timestamp = "2026-04-30T10:12:34Z",
      pngHash = hash,
      pngSize = size,
      pngPath = "$id.png",
      producer = "daemon",
      trigger = "renderNow",
      source = HistorySourceInfo(kind = "fs", id = "fs:${tmpDir.toAbsolutePath()}"),
      renderTookMs = 1L,
    )
}
