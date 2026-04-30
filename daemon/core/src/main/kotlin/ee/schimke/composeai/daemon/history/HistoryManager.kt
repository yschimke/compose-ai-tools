package ee.schimke.composeai.daemon.history

import ee.schimke.composeai.daemon.protocol.RenderMetrics
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonElement

/**
 * Daemon-side history orchestrator — see HISTORY.md § "What this PR lands § H1" / § "H2".
 *
 * Holds the configured [HistorySource]s in priority order. H1+H2 ships only [LocalFsHistorySource];
 * future phases (H10+) add `GitRefHistorySource` / `HttpMirrorHistorySource` here without changing
 * [JsonRpcServer]'s call site.
 *
 * **Read** ([list], [read]) merges across all configured sources. For H1+H2 with one source this is
 * trivially that source's response; the merge code-path lives here so H9+ multi-source merging
 * lands without re-shaping [JsonRpcServer].
 *
 * **Write** ([recordRender]) goes to every writable source in priority order. A failure in any
 * single source's write does NOT fail the render — [JsonRpcServer] catches and logs but keeps the
 * render's success notification flowing.
 *
 * **Notifications.** After a successful write [recordRender] returns the persisted [HistoryEntry]
 * so the caller can emit a `historyAdded` JSON-RPC notification.
 *
 * @param sources writable + readable backends in priority order. Pass `emptyList()` to disable
 *   history (the daemon's no-op default — production callers wire one [LocalFsHistorySource]).
 * @param module the module project path (e.g. `":samples:android"`); stamped into every entry's
 *   `module` field. Defaults to empty string.
 * @param gitProvenance per-render provenance source. When null (e.g. fake-mode test paths), the
 *   `git` / `worktree` fields land null on entries.
 */
class HistoryManager(
  private val sources: List<HistorySource>,
  private val module: String,
  private val gitProvenance: GitProvenance?,
) {

  /**
   * True when at least one writable source is configured — i.e. when `recordRender` is meaningful.
   */
  val isEnabled: Boolean
    get() = sources.any { it.supportsWrites() }

  /**
   * Tracks the most-recent entry per previewId so [recordRender] can fill in `previousId` +
   * `deltaFromPrevious.pngHashChanged`. Carries `(entryId, pngHash)` so the delta pass doesn't have
   * to re-read the previous sidecar off disk.
   */
  private val previousByPreview: AtomicReference<Map<String, PreviousEntry>> =
    AtomicReference(emptyMap())

  private data class PreviousEntry(val id: String, val pngHash: String)

  /**
   * Records one render. Computes [HistoryEntry.id], `pngHash`, the dedup-aware `pngPath`, and the
   * `previousId` / `deltaFromPrevious` fields, then writes to every writable source.
   *
   * Returns the persisted entry on success, or null when no writable source is configured (the
   * disabled-by-default state).
   *
   * **Failure semantics.** If a source's [HistorySource.write] throws, this method logs to stderr
   * but does NOT propagate — history is observation, the render itself is unaffected.
   */
  fun recordRender(
    previewId: String,
    pngBytes: ByteArray,
    trigger: String,
    triggerDetail: JsonElement? = null,
    renderTookMs: Long,
    metrics: RenderMetrics? = null,
    previewMetadata: PreviewMetadataSnapshot? = null,
    timestamp: Instant = Instant.now(),
  ): HistoryEntry? {
    if (!isEnabled) return null
    val pngHash = LocalFsHistorySource.sha256Hex(pngBytes)
    val shortHash = pngHash.substring(0, 8)
    val ts = timestamp.atOffset(ZoneOffset.UTC).format(TIMESTAMP_FORMAT)
    val id = "$ts-$shortHash"
    val isoTimestamp =
      timestamp.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    val (worktreeInfo, gitInfo) = gitProvenance?.snapshot() ?: Pair(null, null)
    val previous = previousByPreview.get()[previewId]
    val previousId = previous?.id
    val delta =
      if (previous != null) {
        // Cheap field — full pixel diff lands in H5. For H1 we flip pngHashChanged based on the
        // previous entry's full pngHash (cached so we don't have to re-read the prior sidecar).
        HistoryDelta(pngHashChanged = previous.pngHash != pngHash, diffPx = null, ssim = null)
      } else null

    // pngPath is provisional — LocalFsHistorySource overwrites it when dedup hits. For non-FS
    // sources or for fresh writes the first source's response shape is what lands; we keep the
    // local-relative filename as the default sidecar value.
    val pngPath = "$id.png"
    val firstWritableSource =
      sources.firstOrNull { it.supportsWrites() }
        ?: return null // Defensive — isEnabled was true, so this shouldn't happen; guard anyway.
    val sourceInfo = HistorySourceInfo(kind = firstWritableSource.kind, id = firstWritableSource.id)

    val entry =
      HistoryEntry(
        id = id,
        previewId = previewId,
        module = module,
        timestamp = isoTimestamp,
        pngHash = pngHash,
        pngSize = pngBytes.size.toLong(),
        pngPath = pngPath,
        producer = "daemon",
        trigger = trigger,
        triggerDetail = triggerDetail,
        source = sourceInfo,
        worktree = worktreeInfo,
        git = gitInfo,
        renderTookMs = renderTookMs,
        metrics = metrics,
        previewMetadata = previewMetadata,
        previousId = previousId,
        deltaFromPrevious = delta,
      )

    var anyWritten = false
    for (source in sources) {
      if (!source.supportsWrites()) continue
      try {
        source.write(entry, pngBytes)
        anyWritten = true
      } catch (t: Throwable) {
        System.err.println(
          "compose-ai-daemon: HistoryManager.recordRender(${entry.id}): write to ${source.id} " +
            "failed (${t.javaClass.simpleName}: ${t.message})"
        )
      }
    }
    if (!anyWritten) return null

    // Update the previousByPreview cache. CAS-loop so concurrent recordRender for the same
    // preview id stays consistent (though `JsonRpcServer.kt` runs history writes single-threaded
    // on the render-watcher path today).
    val newPrev = PreviousEntry(id = id, pngHash = pngHash)
    while (true) {
      val current = previousByPreview.get()
      val updated = current.toMutableMap().also { it[previewId] = newPrev }
      if (previousByPreview.compareAndSet(current, updated)) break
    }
    return entry
  }

  /** Lists across all configured sources. H1+H2 has one source so this delegates trivially. */
  fun list(filter: HistoryFilter): HistoryListPage {
    if (sources.isEmpty()) return HistoryListPage(entries = emptyList(), totalCount = 0)
    if (sources.size == 1) return sources.single().list(filter)
    // Multi-source merging is reserved for H9+. For H1+H2 we still take the first source's page
    // verbatim, but the entry shape carries `source` so a future merge can dedup on
    // `(pngHash, previewId, git.commit)`.
    return sources.first().list(filter)
  }

  /** Reads one entry. Falls through sources in order until a hit. */
  fun read(entryId: String, includeBytes: Boolean): HistoryReadResult? {
    for (source in sources) {
      val result = source.read(entryId, includeBytes = includeBytes)
      if (result != null) return result
    }
    return null
  }

  companion object {
    /** `yyyyMMdd-HHmmss` UTC. Pinned format — HISTORY.md § "On-disk schema" filename shape. */
    private val TIMESTAMP_FORMAT: DateTimeFormatter =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

    /**
     * Builds the default H1 manager wiring — one [LocalFsHistorySource] under [historyDir]. Pass
     * `null` historyDir to get a disabled (`isEnabled = false`) manager.
     */
    fun forLocalFs(
      historyDir: java.nio.file.Path?,
      module: String,
      gitProvenance: GitProvenance?,
    ): HistoryManager {
      val sources =
        if (historyDir == null) emptyList()
        else listOf(LocalFsHistorySource(historyDir = historyDir))
      return HistoryManager(sources = sources, module = module, gitProvenance = gitProvenance)
    }
  }
}
