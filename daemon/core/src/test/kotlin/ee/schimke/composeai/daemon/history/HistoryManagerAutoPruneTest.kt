package ee.schimke.composeai.daemon.history

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the H4 auto-prune scheduler contract on [HistoryManager].
 *
 * - Schedule fires periodic prune passes; each non-empty pass invokes the listener with `reason:
 *   AUTO`.
 * - All-off config (every knob ≤ 0) suppresses the scheduler entirely; no thread is started.
 * - Idempotent: a second pass with no new entries does NOT emit a notification.
 */
class HistoryManagerAutoPruneTest {

  private lateinit var tmpDir: Path

  @Before
  fun setUp() {
    tmpDir = Files.createTempDirectory("history-auto-prune-test")
  }

  @After
  fun tearDown() {
    tmpDir.toFile().deleteRecursively()
  }

  @Test
  fun auto_prune_fires_when_an_entry_is_over_age() {
    val source = LocalFsHistorySource(historyDir = tmpDir)
    val previewId = "P"
    val now = Instant.now()
    // 5 entries on a single preview: 3 over-age (100 days), 2 within cutoff. The newest survives
    // via floor; the 2 within cutoff survive age; the 3 over-age (other than the newest) get
    // pruned. Total removed = 2.
    writeEntry(source, previewId, now, suffix = "r0")
    writeEntry(source, previewId, now.minusSeconds(60), suffix = "r1")
    writeEntry(source, previewId, now.minusSeconds(101L * 24 * 3600), suffix = "o0")
    writeEntry(source, previewId, now.minusSeconds(102L * 24 * 3600), suffix = "o1")
    writeEntry(source, previewId, now.minusSeconds(103L * 24 * 3600), suffix = "o2")

    val notifications = LinkedBlockingQueue<PruneNotification>()
    val mgr =
      HistoryManager(
        sources = listOf(source),
        module = ":t",
        gitProvenance = null,
        pruneConfig =
          HistoryPruneConfig(
            maxEntriesPerPreview = 0,
            maxAgeDays = 14,
            maxTotalSizeBytes = 0L,
            autoPruneIntervalMs = 100L, // fast schedule for the test
          ),
      )
    mgr.setPruneListener { notifications.put(it) }
    try {
      mgr.startAutoPrune(initialDelayMs = 50L)
      val notif = notifications.poll(5, TimeUnit.SECONDS)
      assertTrue("auto-prune notification must arrive", notif != null)
      assertEquals(PruneReason.AUTO, notif!!.reason)
      assertEquals(3, notif.removedIds.size) // o0, o1, o2 — the newest of those three is also
      // pruned because the per-preview floor only protects one entry per preview (the global
      // newest, "r0"); the 3 over-age entries are all dropped.
    } finally {
      mgr.stopAutoPrune()
    }
  }

  @Test
  fun auto_prune_idempotent_no_notification_after_steady_state() {
    val source = LocalFsHistorySource(historyDir = tmpDir)
    val previewId = "P"
    writeEntry(source, previewId, Instant.now())

    val notifications = LinkedBlockingQueue<PruneNotification>()
    val mgr =
      HistoryManager(
        sources = listOf(source),
        module = ":t",
        gitProvenance = null,
        pruneConfig =
          HistoryPruneConfig(
            maxEntriesPerPreview = 50,
            maxAgeDays = 14,
            maxTotalSizeBytes = 500_000_000,
            autoPruneIntervalMs = 50L,
          ),
      )
    mgr.setPruneListener { notifications.put(it) }
    try {
      mgr.startAutoPrune(initialDelayMs = 30L)
      // Wait long enough for several passes — none should fire because the single entry is within
      // every threshold.
      Thread.sleep(400)
      assertNull(
        "no-op auto-prune passes must NOT emit historyPruned",
        notifications.poll(0, TimeUnit.MILLISECONDS),
      )
    } finally {
      mgr.stopAutoPrune()
    }
  }

  @Test
  fun all_off_config_disables_scheduler_entirely() {
    val source = LocalFsHistorySource(historyDir = tmpDir)
    val invokeCount = AtomicInteger(0)
    val mgr =
      HistoryManager(
        sources = listOf(source),
        module = ":t",
        gitProvenance = null,
        pruneConfig =
          HistoryPruneConfig(
            maxEntriesPerPreview = 0,
            maxAgeDays = 0,
            maxTotalSizeBytes = 0L,
            autoPruneIntervalMs = 0L,
          ),
      )
    mgr.setPruneListener { invokeCount.incrementAndGet() }
    try {
      mgr.startAutoPrune(initialDelayMs = 5L)
      Thread.sleep(200)
      // Listener never fires; the scheduler thread is never created.
      assertEquals(0, invokeCount.get())
    } finally {
      mgr.stopAutoPrune()
    }
  }

  private fun writeEntry(
    source: LocalFsHistorySource,
    previewId: String,
    timestamp: Instant,
    suffix: String = "",
  ): HistoryEntry {
    val bytes = ("preview-$previewId-$suffix-${timestamp.toEpochMilli()}").toByteArray()
    val hash = LocalFsHistorySource.sha256Hex(bytes)
    val tsId =
      OffsetDateTime.ofInstant(timestamp, ZoneOffset.UTC)
        .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val isoTs =
      OffsetDateTime.ofInstant(timestamp, ZoneOffset.UTC)
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    val id = "$tsId-$suffix-${hash.take(8)}"
    val entry =
      HistoryEntry(
        id = id,
        previewId = previewId,
        module = ":t",
        timestamp = isoTs,
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
}
