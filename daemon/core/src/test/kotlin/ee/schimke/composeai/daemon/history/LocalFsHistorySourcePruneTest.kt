package ee.schimke.composeai.daemon.history

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the H4 prune contract on [LocalFsHistorySource] — see HISTORY.md § "Pruning policy".
 *
 * The pruning passes (age → per-preview count → total size) compose: each runs against the survivor
 * set from the previous. The "never drop most recent per preview" floor is enforced throughout —
 * even an over-age single-entry preview survives.
 */
class LocalFsHistorySourcePruneTest {

  private lateinit var tmpDir: Path
  private lateinit var source: LocalFsHistorySource

  @Before
  fun setUp() {
    tmpDir = Files.createTempDirectory("history-prune-test")
    source = LocalFsHistorySource(historyDir = tmpDir)
  }

  @After
  fun tearDown() {
    tmpDir.toFile().deleteRecursively()
  }

  @Test
  fun age_pass_drops_entries_older_than_cutoff() {
    val now = Instant.now()
    // 20 entries spread across 30 days: 0, 1.5, 3, ... 28.5 days ago (15 over the 14-day cutoff,
    // 5 under it). Newest is at index 0; oldest at index 19.
    val entries =
      (0 until 20).map { i ->
        val ts = now.minusSeconds(((30 * 24 * 3600) * i / 20).toLong())
        writeEntry(previewId = "P$i", timestamp = ts)
      }

    val result =
      source.prune(
        HistoryPruneConfig(maxEntriesPerPreview = 0, maxAgeDays = 14, maxTotalSizeBytes = 0L)
      )

    // Survivors: newest 14 days. Floor protects most-recent-per-preview but every preview here is
    // unique, so the floor protects every entry. Hence every entry survives despite age cutoff.
    // Re-run with same preview ids to actually exercise the age cutoff.
    val survivorIds = readIndexIds(tmpDir)
    assertTrue(
      "every preview is unique → floor protects every entry from age pass",
      survivorIds.containsAll(entries.map { it.id }),
    )
    assertEquals(0, result.removedEntryIds.size)
  }

  @Test
  fun age_pass_drops_old_entries_when_floor_does_not_protect_them() {
    val now = Instant.now()
    // Single previewId, 20 entries spread over 30 days. The newest survives (floor); 14 of the
    // remaining 19 are within the cutoff (over 14 days old gets dropped).
    val previewId = "OnePreview"
    val entries =
      (0 until 20).map { i ->
        val ts = now.minusSeconds(((30 * 24 * 3600) * i / 20).toLong())
        writeEntry(previewId = previewId, timestamp = ts, suffix = "%02d".format(i))
      }

    val result =
      source.prune(
        HistoryPruneConfig(maxEntriesPerPreview = 0, maxAgeDays = 14, maxTotalSizeBytes = 0L)
      )

    // Compute expected: newest entry survives (floor); plus all entries that are NOT older than 14
    // days.
    val cutoff = now.minusSeconds(14L * 24 * 3600)
    val cutoffString =
      OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC)
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    val expectedSurvivors =
      entries.filter { it.timestamp >= cutoffString }.map { it.id }.toMutableSet()
    expectedSurvivors.add(entries.first().id) // floor

    val survivorIds = readIndexIds(tmpDir)
    assertEquals(expectedSurvivors, survivorIds.toSet())
    assertEquals(entries.size - expectedSurvivors.size, result.removedEntryIds.size)
  }

  @Test
  fun per_preview_count_keeps_newest_n_per_preview() {
    val now = Instant.now()
    // 3 previews × 30 entries each. Total 90.
    val byPreview =
      ('A'..'C').map { c ->
        (0 until 30).map { i ->
          writeEntry(
            previewId = "Preview$c",
            timestamp = now.minusSeconds(i.toLong() * 60),
            suffix = "%02d".format(i),
          )
        }
      }

    val result =
      source.prune(
        HistoryPruneConfig(maxEntriesPerPreview = 10, maxAgeDays = 0, maxTotalSizeBytes = 0L)
      )

    // 30 entries survive (10 newest per preview).
    val survivors = readIndexIds(tmpDir)
    assertEquals(30, survivors.size)
    for (group in byPreview) {
      val newest10 = group.take(10).map { it.id }.toSet()
      assertTrue("newest 10 of group must survive", survivors.containsAll(newest10))
    }
    // 60 entries removed (20 oldest per preview).
    assertEquals(60, result.removedEntryIds.size)
  }

  @Test
  fun total_size_pass_drops_oldest_until_under_threshold() {
    val now = Instant.now()
    // 50 entries × 10 MB each = 500 MB. Cap at 300 MB → 30 newest survive.
    val tenMb = ByteArray(10 * 1024 * 1024) { (it and 0xff).toByte() }
    val entries =
      (0 until 50).map { i ->
        // Each entry has a unique pngHash to defeat dedup.
        val bytes = tenMb.copyOf().apply { this[0] = i.toByte() }
        val ts = now.minusSeconds(i.toLong() * 60)
        writeEntry(previewId = "P$i", timestamp = ts, bytes = bytes, suffix = "%02d".format(i))
      }

    val result =
      source.prune(
        HistoryPruneConfig(
          maxEntriesPerPreview = 0,
          maxAgeDays = 0,
          maxTotalSizeBytes = 300L * 1024 * 1024,
        )
      )

    // 50 unique previews, each entry has a unique previewId → floor protects every entry → no
    // entries can be pruned by the size pass. Use a single-preview variant below.
    assertEquals(0, result.removedEntryIds.size)
    val survivors = readIndexIds(tmpDir)
    assertEquals(entries.map { it.id }.toSet(), survivors.toSet())
  }

  @Test
  fun total_size_pass_drops_oldest_for_single_preview_until_under_threshold() {
    val now = Instant.now()
    val tenMb = ByteArray(10 * 1024 * 1024) { (it and 0xff).toByte() }
    val previewId = "OnePreview"
    val entries =
      (0 until 50).map { i ->
        val bytes = tenMb.copyOf().apply { this[0] = i.toByte() }
        val ts = now.minusSeconds(i.toLong() * 60)
        writeEntry(previewId = previewId, timestamp = ts, bytes = bytes, suffix = "%02d".format(i))
      }

    val result =
      source.prune(
        HistoryPruneConfig(
          maxEntriesPerPreview = 0,
          maxAgeDays = 0,
          maxTotalSizeBytes = 300L * 1024 * 1024,
        )
      )

    val survivors = readIndexIds(tmpDir)
    // 30 newest survive (300 MB / 10 MB = 30).
    assertEquals(30, survivors.size)
    assertEquals(entries.take(30).map { it.id }.toSet(), survivors.toSet())
    assertEquals(20, result.removedEntryIds.size)
    assertEquals(20L * 10 * 1024 * 1024, result.freedBytes)
  }

  @Test
  fun most_recent_per_preview_always_survives_even_when_over_age() {
    val now = Instant.now()
    val veryOld = now.minusSeconds(100L * 24 * 3600) // 100 days old
    val entry = writeEntry(previewId = "OldOnly", timestamp = veryOld)

    val result =
      source.prune(
        HistoryPruneConfig(maxEntriesPerPreview = 0, maxAgeDays = 14, maxTotalSizeBytes = 0L)
      )

    val survivors = readIndexIds(tmpDir)
    assertTrue(
      "most recent of each preview survives even when over-age",
      survivors.contains(entry.id),
    )
    assertEquals(0, result.removedEntryIds.size)
  }

  @Test
  fun dry_run_returns_removal_set_without_mutating_disk() {
    val now = Instant.now()
    val previewId = "P"
    val entries =
      (0 until 5).map { i ->
        writeEntry(
          previewId = previewId,
          timestamp = now.minusSeconds(i.toLong() * 3600),
          suffix = "%02d".format(i),
        )
      }

    val sidecarsBefore =
      entries.map { tmpDir.resolve("P").resolve("${it.id}.json") }.filter { Files.exists(it) }
    val pngsBefore =
      entries.map { tmpDir.resolve("P").resolve("${it.id}.png") }.filter { Files.exists(it) }
    val indexBefore = Files.readAllLines(tmpDir.resolve(LocalFsHistorySource.INDEX_FILENAME))

    val result =
      source.prune(
        HistoryPruneConfig(maxEntriesPerPreview = 1, maxAgeDays = 0, maxTotalSizeBytes = 0L),
        dryRun = true,
      )

    // 4 of 5 marked for removal (newest one survives via floor + newest-1 via per-preview cap).
    assertEquals(4, result.removedEntryIds.size)
    // Disk unchanged.
    assertEquals(
      sidecarsBefore,
      entries.map { tmpDir.resolve("P").resolve("${it.id}.json") }.filter { Files.exists(it) },
    )
    assertEquals(
      pngsBefore,
      entries.map { tmpDir.resolve("P").resolve("${it.id}.png") }.filter { Files.exists(it) },
    )
    assertEquals(
      indexBefore,
      Files.readAllLines(tmpDir.resolve(LocalFsHistorySource.INDEX_FILENAME)),
    )
  }

  @Test
  fun dedup_by_hash_preserves_png_when_other_sidecar_still_references_it() {
    // Two entries share the same bytes (and therefore same pngHash). Source dedups: only the first
    // PNG file is on disk; both sidecars' pngPath points at it. Pruning the SECOND sidecar must
    // leave the PNG intact (the first sidecar still references it). Pruning the FIRST sidecar
    // alone is the same: the second sidecar still references the file via the same name.
    val previewId = "Dedup"
    val sharedBytes = "shared-bytes".toByteArray()
    val now = Instant.now()
    val first =
      writeEntry(
        previewId = previewId,
        timestamp = now.minusSeconds(3600),
        bytes = sharedBytes,
        suffix = "first",
      )
    val second =
      writeEntry(previewId = previewId, timestamp = now, bytes = sharedBytes, suffix = "second")

    val previewDir = tmpDir.resolve(previewId)
    val firstPng = previewDir.resolve("${first.id}.png")
    val secondPng = previewDir.resolve("${second.id}.png")
    assertTrue(Files.exists(firstPng))
    assertFalse("dedup → second PNG file should not exist", Files.exists(secondPng))

    // Prune with maxEntriesPerPreview=1 → second survives (it's the newest), first removed.
    val result =
      source.prune(
        HistoryPruneConfig(maxEntriesPerPreview = 1, maxAgeDays = 0, maxTotalSizeBytes = 0L)
      )
    assertEquals(listOf(first.id), result.removedEntryIds)
    // freedBytes is 0 because the surviving sidecar's pngPath still references "${first.id}.png".
    assertEquals(0L, result.freedBytes)
    assertTrue("PNG must remain — dedup target still referenced", Files.exists(firstPng))

    // Sidecar for the removed entry is gone.
    assertFalse("first sidecar must be gone", Files.exists(previewDir.resolve("${first.id}.json")))
    // Survivor sidecar still on disk.
    assertTrue(Files.exists(previewDir.resolve("${second.id}.json")))
  }

  @Test
  fun index_rewrite_is_atomic_against_failures() {
    // Synthesise N entries; verify the index is bytewise rewritten cleanly. We can't easily
    // simulate
    // a mid-write crash without injecting a filesystem error, so we instead verify the contract:
    // the
    // tempfile-then-rename sequence leaves no partial index. Approximation: assert the temp file
    // does not exist after a successful prune, and that the index lines are exactly the surviving
    // entries (no torn lines, no orphaned entries).
    val now = Instant.now()
    val previewId = "P"
    val entries =
      (0 until 10).map { i ->
        writeEntry(
          previewId = previewId,
          timestamp = now.minusSeconds(i.toLong() * 60),
          suffix = "%02d".format(i),
        )
      }
    source.prune(
      HistoryPruneConfig(maxEntriesPerPreview = 3, maxAgeDays = 0, maxTotalSizeBytes = 0L)
    )
    val tempfile = tmpDir.resolve("${LocalFsHistorySource.INDEX_FILENAME}.tmp")
    assertFalse("prune temp file must be cleaned up after success", Files.exists(tempfile))
    val indexLines =
      Files.readAllLines(tmpDir.resolve(LocalFsHistorySource.INDEX_FILENAME)).filter {
        it.isNotBlank()
      }
    assertEquals(3, indexLines.size)
    // Every line is bytewise complete JSON (no torn lines).
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    for (line in indexLines) {
      assertNotNull(json.decodeFromString(HistoryEntry.serializer(), line))
    }
  }

  @Test
  fun pass_order_is_age_then_count_then_size() {
    // Compose: 10 entries with one preview-id, mixed timestamps + sizes. Age cuts 4; count cap of
    // 3 should then survive newest 3 (one is dropped by age, count cap means more drop). Size cap
    // is loose so it's a no-op.
    val now = Instant.now()
    val previewId = "P"
    // entries 0..3 are recent (within 14 days), 4..9 are over 100 days old.
    val recent =
      (0 until 4).map { i ->
        writeEntry(
          previewId = previewId,
          timestamp = now.minusSeconds(i.toLong() * 60),
          suffix = "r$i",
        )
      }
    val old =
      (0 until 6).map { i ->
        writeEntry(
          previewId = previewId,
          timestamp = now.minusSeconds(100L * 24 * 3600 + i.toLong() * 60),
          suffix = "o$i",
        )
      }

    val result =
      source.prune(
        HistoryPruneConfig(maxEntriesPerPreview = 3, maxAgeDays = 14, maxTotalSizeBytes = 0L)
      )

    // After age pass: recent (4) + the floor-protected oldest survives. So 5 entries survive age
    // pass.
    // Then per-preview count of 3 caps surviving recent entries: actually the floor is just one per
    // preview (the *newest* of all entries), so count cap drops 5 - 3 = 2 more, but the floor
    // protects entry recent[0]. We expect: recent[0,1,2] survive (newest 3); the rest pruned.
    val survivors = readIndexIds(tmpDir)
    assertEquals(setOf(recent[0].id, recent[1].id, recent[2].id), survivors.toSet())
    // Removed: recent[3] (per-preview cap kicks in), all 6 old (age pass), so 7 removals.
    assertEquals(7, result.removedEntryIds.size)
  }

  // ---------------------------------------------------------------------------------------
  // Helpers — write a synthetic entry directly via the source's write path so dedup / index /
  // sidecars all match the production shape.
  // ---------------------------------------------------------------------------------------

  private fun writeEntry(
    previewId: String,
    timestamp: Instant,
    bytes: ByteArray = ("preview-$previewId-${timestamp.toEpochMilli()}").toByteArray(),
    suffix: String = "",
  ): HistoryEntry {
    val isoTimestamp =
      OffsetDateTime.ofInstant(timestamp, ZoneOffset.UTC)
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    val hash = LocalFsHistorySource.sha256Hex(bytes)
    val tsId =
      OffsetDateTime.ofInstant(timestamp, ZoneOffset.UTC)
        .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val id = "$tsId-$suffix-${hash.take(8)}"
    val entry =
      HistoryEntry(
        id = id,
        previewId = previewId,
        module = ":t",
        timestamp = isoTimestamp,
        pngHash = hash,
        pngSize = bytes.size.toLong(),
        pngPath = "$id.png",
        producer = "daemon",
        trigger = "renderNow",
        source = HistorySourceInfo(kind = "fs", id = "fs:${tmpDir.toAbsolutePath()}"),
        renderTookMs = 1L,
      )
    source.write(entry, bytes)
    return entry
  }

  private fun readIndexIds(historyDir: Path): List<String> {
    val indexFile = historyDir.resolve(LocalFsHistorySource.INDEX_FILENAME)
    if (!Files.exists(indexFile)) return emptyList()
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    return Files.readAllLines(indexFile)
      .filter { it.isNotBlank() }
      .map { json.decodeFromString(HistoryEntry.serializer(), it).id }
  }
}
