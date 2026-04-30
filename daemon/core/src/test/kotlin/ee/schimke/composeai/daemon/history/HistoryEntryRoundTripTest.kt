package ee.schimke.composeai.daemon.history

import ee.schimke.composeai.daemon.protocol.RenderMetrics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the kotlinx.serialization round-trip for [HistoryEntry] — every nested struct
 * (`worktree` / `git` / `source` / `triggerDetail` / `previewMetadata` / `metrics` /
 * `deltaFromPrevious`) must survive encode → decode without field loss.
 *
 * The on-disk sidecar JSON is the wire format; this test is the one place where the schema's
 * shape is locked end-to-end. New fields added to [HistoryEntry] should grow this fixture.
 */
class HistoryEntryRoundTripTest {

  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

  @Test
  fun fully_populated_entry_round_trips() {
    val entry =
      HistoryEntry(
        id = "20260430-101234-a1b2c3d4",
        previewId = "com.example.RedSquare",
        module = ":samples:android",
        timestamp = "2026-04-30T10:12:34Z",
        pngHash = "a1b2c3d4e5f6789012345678901234567890123456789012345678901234aaaa",
        pngSize = 4218L,
        pngPath = "20260430-101234-a1b2c3d4.png",
        producer = "daemon",
        trigger = "fileChanged",
        triggerDetail =
          buildJsonObject {
            put("kind", JsonPrimitive("source"))
            put("path", JsonPrimitive("/abs/path/Foo.kt"))
          },
        source = HistorySourceInfo(kind = "fs", id = "fs:/home/yuri/.compose-preview-history"),
        worktree =
          WorktreeInfo(
            path = "/home/yuri/workspace/compose-ai-tools",
            id = "main",
            agentId = "agent-foo",
          ),
        git =
          GitInfo(
            branch = "agent/preview-daemon-streamAB",
            commit = "6af6b8c1d4e2f7a8b9c0d1e2f3a4b5c6d7e8f9a0",
            shortCommit = "6af6b8c",
            dirty = true,
            remote = "https://github.com/yschimke/compose-ai-tools",
          ),
        renderTookMs = 234L,
        metrics =
          RenderMetrics(
            heapAfterGcMb = 312L,
            nativeHeapMb = 540L,
            sandboxAgeRenders = 17L,
            sandboxAgeMs = 81234L,
          ),
        previewMetadata =
          PreviewMetadataSnapshot(
            displayName = "Red square",
            group = "buttons",
            sourceFile = "/abs/path/Foo.kt",
            config = "phone-portrait",
          ),
        previousId = "20260430-101207-9f8e7d6c",
        deltaFromPrevious = HistoryDelta(pngHashChanged = true, diffPx = 142L, ssim = 0.97),
      )
    val text = json.encodeToString(HistoryEntry.serializer(), entry)
    val decoded = json.decodeFromString(HistoryEntry.serializer(), text)
    assertEquals(entry, decoded)
  }

  @Test
  fun minimal_entry_round_trips() {
    // Tests the "no provenance, no previous, no delta" path — the first render in a non-git workspace.
    val entry =
      HistoryEntry(
        id = "20260430-101234-deadbeef",
        previewId = "com.example.Foo",
        module = "",
        timestamp = "2026-04-30T10:12:34Z",
        pngHash = "deadbeef00000000000000000000000000000000000000000000000000000000",
        pngSize = 100L,
        pngPath = "20260430-101234-deadbeef.png",
        producer = "daemon",
        trigger = "renderNow",
        source = HistorySourceInfo(kind = "fs", id = "fs:/tmp"),
        renderTookMs = 5L,
      )
    val text = json.encodeToString(HistoryEntry.serializer(), entry)
    val decoded = json.decodeFromString(HistoryEntry.serializer(), text)
    assertEquals(entry, decoded)
  }
}
