package ee.schimke.composeai.daemon.history

import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
  fun consecutive_identical_render_returns_SKIPPED_DUPLICATE_and_writes_nothing() {
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
    val firstResult = source.write(firstEntry, bytes)
    val secondResult = source.write(secondEntry, bytes)

    assertEquals(WriteResult.WRITTEN, firstResult)
    assertEquals(
      "Tier 1 dedup: most-recent hash matches → skip everything",
      WriteResult.SKIPPED_DUPLICATE,
      secondResult,
    )

    val previewDir = tmpDir.resolve("Foo")
    val firstPng = previewDir.resolve("${firstEntry.id}.png")
    val firstSidecar = previewDir.resolve("${firstEntry.id}.json")
    val secondPng = previewDir.resolve("${secondEntry.id}.png")
    val secondSidecar = previewDir.resolve("${secondEntry.id}.json")

    assertTrue("First PNG must exist", Files.exists(firstPng))
    assertTrue("First sidecar must exist", Files.exists(firstSidecar))
    assertFalse("Second PNG must NOT exist (skipped)", Files.exists(secondPng))
    assertFalse("Second sidecar must NOT exist (skipped)", Files.exists(secondSidecar))

    // Index has exactly one line — the second write didn't append.
    val indexLines =
      Files.readAllLines(tmpDir.resolve(LocalFsHistorySource.INDEX_FILENAME)).filter {
        it.isNotBlank()
      }
    assertEquals(1, indexLines.size)
  }

  @Test
  fun A_then_B_then_A_keeps_three_entries_with_pngPath_pointer_on_third() {
    // Tier 2 — non-consecutive matching hash. Render history A → B → A: the third entry IS a
    // meaningful event ("we went back to A"), so a sidecar lands; but the PNG points at the
    // first A's bytes rather than re-writing them.
    val source = LocalFsHistorySource(historyDir = tmpDir)
    val previewId = "Bouncy"
    val bytesA = byteArrayOf(0x01, 0x02, 0x03)
    val bytesB = byteArrayOf(0x09, 0x08, 0x07)
    val hashA = LocalFsHistorySource.sha256Hex(bytesA)
    val hashB = LocalFsHistorySource.sha256Hex(bytesB)

    val firstA =
      makeEntry(
        id = "20260430-100000-${hashA.take(8)}",
        previewId = previewId,
        hash = hashA,
        size = 3,
      )
    val midB =
      makeEntry(
        id = "20260430-100001-${hashB.take(8)}",
        previewId = previewId,
        hash = hashB,
        size = 3,
      )
    val secondA =
      makeEntry(
        id = "20260430-100002-${hashA.take(8)}",
        previewId = previewId,
        hash = hashA,
        size = 3,
      )

    assertEquals(WriteResult.WRITTEN, source.write(firstA, bytesA))
    assertEquals(WriteResult.WRITTEN, source.write(midB, bytesB))
    assertEquals(
      "A → B → A: third write is meaningful, must land",
      WriteResult.WRITTEN,
      source.write(secondA, bytesA),
    )

    val previewDir = tmpDir.resolve("Bouncy")
    val firstAPng = previewDir.resolve("${firstA.id}.png")
    val midBPng = previewDir.resolve("${midB.id}.png")
    val secondAPng = previewDir.resolve("${secondA.id}.png")
    val secondASidecar = previewDir.resolve("${secondA.id}.json")

    assertTrue("First A PNG exists", Files.exists(firstAPng))
    assertTrue("Mid B PNG exists", Files.exists(midBPng))
    assertFalse("Second A's PNG must NOT be rewritten — pointer-only", Files.exists(secondAPng))
    assertTrue("Second A sidecar exists", Files.exists(secondASidecar))

    val secondSidecarEntry =
      json.decodeFromString(HistoryEntry.serializer(), Files.readString(secondASidecar))
    assertEquals(
      "Second A sidecar's pngPath points at first A's PNG",
      "${firstA.id}.png",
      secondSidecarEntry.pngPath,
    )

    val indexLines =
      Files.readAllLines(tmpDir.resolve(LocalFsHistorySource.INDEX_FILENAME)).filter {
        it.isNotBlank()
      }
    assertEquals(3, indexLines.size)
  }

  @Test
  fun consecutive_identical_render_skip_survives_simulated_cross_restart() {
    // Tier 1 dedup must hit even when the "first" entry was written by a previous daemon process
    // and the in-memory cache is empty — the source walks the per-preview directory listing.
    val previewId = "Bar"
    val bytes = byteArrayOf(0x77, 0x77, 0x77)
    val hash = LocalFsHistorySource.sha256Hex(bytes)
    val firstEntry =
      makeEntry(
        id = "20260430-101000-${hash.take(8)}",
        previewId = previewId,
        hash = hash,
        size = bytes.size.toLong(),
      )
    assertEquals(
      WriteResult.WRITTEN,
      LocalFsHistorySource(historyDir = tmpDir).write(firstEntry, bytes),
    )

    val sourceB = LocalFsHistorySource(historyDir = tmpDir)
    val secondEntry =
      makeEntry(
        id = "20260430-101005-${hash.take(8)}",
        previewId = previewId,
        hash = hash,
        size = bytes.size.toLong(),
      )
    val secondResult = sourceB.write(secondEntry, bytes)

    assertEquals(
      "Cross-restart tier-1 dedup: most-recent hash on disk matches → SKIPPED_DUPLICATE",
      WriteResult.SKIPPED_DUPLICATE,
      secondResult,
    )

    val previewDir = tmpDir.resolve("Bar")
    val firstPng = previewDir.resolve("${firstEntry.id}.png")
    val secondPng = previewDir.resolve("${secondEntry.id}.png")
    val secondSidecar = previewDir.resolve("${secondEntry.id}.json")
    assertTrue(Files.exists(firstPng))
    assertFalse("Cross-restart skip must NOT write a duplicate PNG", Files.exists(secondPng))
    assertFalse("Cross-restart skip must NOT write a sidecar", Files.exists(secondSidecar))

    // Index has exactly one line — the second write didn't append.
    val indexLines =
      Files.readAllLines(tmpDir.resolve(LocalFsHistorySource.INDEX_FILENAME)).filter {
        it.isNotBlank()
      }
    assertEquals(1, indexLines.size)
  }

  @Test
  fun semantics_only_change_with_identical_pixels_is_recorded_not_deduped() {
    // Issue #1785 — a render that changes only semantics (e.g. a dropped testTag /
    // contentDescription) produces byte-identical pixels. Tier-1 dedup must NOT skip it, else
    // `history/diff mode=semantics` could never report the change. The PNG is still pointer-deduped
    // (tier 2), so only a fresh sidecar + index line land — no duplicate bytes.
    val source = LocalFsHistorySource(historyDir = tmpDir)
    val previewId = "SemanticOnly"
    val bytes = byteArrayOf(0x42, 0x42, 0x42)
    val hash = LocalFsHistorySource.sha256Hex(bytes)

    val first =
      makeEntry(
        id = "20260430-100000-${hash.take(8)}",
        previewId = previewId,
        hash = hash,
        size = bytes.size.toLong(),
        semantics = semanticsJson(testTag = "submit"),
      )
    val second =
      makeEntry(
        id = "20260430-100001-${hash.take(8)}",
        previewId = previewId,
        hash = hash,
        size = bytes.size.toLong(),
        semantics = semanticsJson(testTag = "cancel"),
      )

    assertEquals(WriteResult.WRITTEN, source.write(first, bytes))
    assertEquals(
      "identical pixels but a changed semantics tree must land",
      WriteResult.WRITTEN,
      source.write(second, bytes),
    )

    val previewDir = tmpDir.resolve("SemanticOnly")
    val secondPng = previewDir.resolve("${second.id}.png")
    val secondSidecar = previewDir.resolve("${second.id}.json")
    assertFalse("Second PNG must NOT be rewritten — pointer-only", Files.exists(secondPng))
    assertTrue("Second sidecar must exist", Files.exists(secondSidecar))
    val secondEntry =
      json.decodeFromString(HistoryEntry.serializer(), Files.readString(secondSidecar))
    assertEquals("Second pngPath points at first's PNG", "${first.id}.png", secondEntry.pngPath)

    val indexLines =
      Files.readAllLines(tmpDir.resolve(LocalFsHistorySource.INDEX_FILENAME)).filter {
        it.isNotBlank()
      }
    assertEquals(2, indexLines.size)
  }

  @Test
  fun identical_pixels_and_identical_semantics_is_skipped() {
    val source = LocalFsHistorySource(historyDir = tmpDir)
    val previewId = "Steady"
    val bytes = byteArrayOf(0x55, 0x66, 0x77)
    val hash = LocalFsHistorySource.sha256Hex(bytes)

    val first =
      makeEntry(
        id = "20260430-100000-${hash.take(8)}",
        previewId = previewId,
        hash = hash,
        size = bytes.size.toLong(),
        semantics = semanticsJson(testTag = "submit"),
      )
    val second =
      makeEntry(
        id = "20260430-100001-${hash.take(8)}",
        previewId = previewId,
        hash = hash,
        size = bytes.size.toLong(),
        semantics = semanticsJson(testTag = "submit"),
      )

    assertEquals(WriteResult.WRITTEN, source.write(first, bytes))
    assertEquals(
      "identical pixels AND identical semantics is still a pure duplicate",
      WriteResult.SKIPPED_DUPLICATE,
      source.write(second, bytes),
    )
  }

  @Test
  fun identical_semantics_with_different_nodeId_is_skipped() {
    // The volatile per-render `nodeId` reshuffles between identical renders. Because the dedup
    // compares trees structurally (SemanticsDiff ignores nodeId), a re-render whose only delta is
    // nodeId churn must still dedup — otherwise every identical render would record a spurious
    // entry.
    val source = LocalFsHistorySource(historyDir = tmpDir)
    val previewId = "NodeIdChurn"
    val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
    val hash = LocalFsHistorySource.sha256Hex(bytes)

    val first =
      makeEntry(
        id = "20260430-100000-${hash.take(8)}",
        previewId = previewId,
        hash = hash,
        size = bytes.size.toLong(),
        semantics = semanticsJson(nodeId = "1", testTag = "submit"),
      )
    val second =
      makeEntry(
        id = "20260430-100001-${hash.take(8)}",
        previewId = previewId,
        hash = hash,
        size = bytes.size.toLong(),
        semantics = semanticsJson(nodeId = "999", testTag = "submit"),
      )

    assertEquals(WriteResult.WRITTEN, source.write(first, bytes))
    assertEquals(
      "nodeId-only churn is not a semantic change → dedup still skips",
      WriteResult.SKIPPED_DUPLICATE,
      source.write(second, bytes),
    )
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

  private fun makeEntry(
    id: String,
    previewId: String,
    hash: String,
    size: Long,
    semantics: JsonElement? = null,
  ): HistoryEntry =
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
      semantics = semantics,
    )

  /** A one-node `compose/semantics` payload as a [JsonElement], for seeding sidecars. */
  private fun semanticsJson(nodeId: String = "1", testTag: String): JsonElement =
    json.parseToJsonElement(
      """{"root":{"nodeId":"$nodeId","boundsInRoot":"0,0,10,10","testTag":"$testTag"}}"""
    )
}
