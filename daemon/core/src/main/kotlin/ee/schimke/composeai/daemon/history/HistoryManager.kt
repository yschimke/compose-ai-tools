package ee.schimke.composeai.daemon.history

import ee.schimke.composeai.daemon.protocol.RenderMetrics
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
  /**
   * H4 — pruning config. Production mains read from sysprops via [HistoryPruneConfig.fromSysprops];
   * tests pass an explicit instance. The auto-prune scheduler reads this at construction time;
   * manual `history/prune` RPC calls override individual fields per-call.
   */
  pruneConfig: HistoryPruneConfig = HistoryPruneConfig(),
) {
  private val pruneConfigRef: AtomicReference<HistoryPruneConfig> = AtomicReference(pruneConfig)

  val pruneConfig: HistoryPruneConfig
    get() = pruneConfigRef.get()

  /**
   * Initialize-time override path for clients that cannot set daemon JVM sysprops directly. Must be
   * called before [startAutoPrune] to affect the scheduler interval for this daemon session.
   */
  fun configurePruneConfig(config: HistoryPruneConfig) {
    pruneConfigRef.set(config)
  }

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
    var anyAttempted = false
    for (source in sources) {
      if (!source.supportsWrites()) continue
      anyAttempted = true
      try {
        when (source.write(entry, pngBytes)) {
          WriteResult.WRITTEN -> anyWritten = true
          WriteResult.SKIPPED_DUPLICATE -> {
            // Source decided this render's bytes match the most recent entry; nothing to do.
            // We deliberately do NOT update previousByPreview — the previous still IS the most
            // recent entry on disk, and its (id, pngHash) tuple is still correct.
          }
        }
      } catch (t: Throwable) {
        System.err.println(
          "compose-ai-daemon: HistoryManager.recordRender(${entry.id}): write to ${source.id} " +
            "failed (${t.javaClass.simpleName}: ${t.message})"
        )
      }
    }
    // No writable source configured → return null (the disabled-by-default path).
    // All writable sources skipped as duplicates → return null too; caller suppresses
    // `historyAdded` and the consumer never sees a redundant entry for repeated identical renders.
    if (!anyAttempted || !anyWritten) return null

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

  /**
   * Lists across all configured sources. With one source this delegates trivially; with multiple
   * (H10-read onward — LocalFs + one or more `GitRefHistorySource`s) it merges per-source pages,
   * dedups entries that surface in multiple sources by `(previewId, pngHash)` keeping the
   * highest-priority source's copy, applies the filter, sorts newest-first, and re-paginates the
   * combined result.
   *
   * Per-source filtering is intentionally NOT trusted to compose with cross-source pagination — we
   * always re-filter the merged set so a `sourceKind` filter applied across sources behaves the
   * same way it does inside one source.
   *
   * Source priority for dedup: the order in [sources]. LocalFs first means the writable source's
   * canonical entry (with `source.kind = "fs"`) is what surfaces; a GitRef-only render still
   * surfaces its `source.kind = "git"` entry.
   */
  fun list(filter: HistoryFilter): HistoryListPage {
    if (sources.isEmpty()) return HistoryListPage(entries = emptyList(), totalCount = 0)
    if (sources.size == 1) return sources.single().list(filter)

    // Pull a generous page from each source (no pagination at the per-source layer; we apply
    // pagination after merging). We pass the full filter so per-source `sourceKind` / `sourceId`
    // narrows can short-circuit non-matching sources cheaply at the source layer.
    val perSourceFilter = filter.copy(limit = HistoryFilters.MAX_LIMIT, cursor = null)
    val merged = LinkedHashMap<Pair<String, String>, HistoryEntry>() // (previewId, pngHash) → entry
    for (source in sources) {
      val page =
        try {
          source.list(perSourceFilter)
        } catch (t: Throwable) {
          System.err.println(
            "compose-ai-daemon: HistoryManager.list: source ${source.id} list() threw " +
              "(${t.javaClass.simpleName}: ${t.message}); skipping"
          )
          continue
        }
      for (entry in page.entries) {
        val key = entry.previewId to entry.pngHash
        // Preserve first writer wins — earlier sources have priority.
        merged.putIfAbsent(key, entry)
      }
    }
    // Apply the user filter against the merged set; sort newest-first by timestamp; paginate.
    val matched =
      merged.values
        .filter { HistoryFilters.matches(it, filter) }
        .sortedWith(compareByDescending<HistoryEntry> { it.timestamp }.thenByDescending { it.id })
    val totalCount = matched.size
    val slice = HistoryFilters.paginate(matched, filter)
    return HistoryListPage(
      entries = slice.entries,
      nextCursor = slice.nextCursor,
      totalCount = totalCount,
    )
  }

  /** Reads one entry. Falls through sources in order until a hit. */
  fun read(entryId: String, includeBytes: Boolean): HistoryReadResult? {
    for (source in sources) {
      val result = source.read(entryId, includeBytes = includeBytes)
      if (result != null) return result
    }
    return null
  }

  // ---------------------------------------------------------------------------------------
  // H4 — Pruning + auto-prune scheduler.
  // ---------------------------------------------------------------------------------------

  /**
   * Listener for `historyPruned` notifications. Set by [JsonRpcServer] at construction; null when
   * unwired (in-process tests, fake-mode harness scenarios that don't care).
   */
  @Volatile private var pruneListener: ((PruneNotification) -> Unit)? = null

  /** Auto-prune scheduler — null until [startAutoPrune] is called; null forever when disabled. */
  private val scheduler: AtomicReference<ScheduledExecutorService?> = AtomicReference(null)

  private val autoPruneFuture: AtomicReference<ScheduledFuture<*>?> = AtomicReference(null)

  private val autoPruneStopped = AtomicBoolean(false)

  /**
   * H4 — registers a listener invoked after each non-empty prune pass (auto or manual) so the
   * caller can emit a `historyPruned` JSON-RPC notification. [JsonRpcServer.runHistoryManager]
   * wires this on construction; tests pass a buffer-capturing lambda or leave it null.
   */
  fun setPruneListener(listener: ((PruneNotification) -> Unit)?) {
    pruneListener = listener
  }

  /**
   * H4 — runs one prune pass across writable [LocalFsHistorySource]s. Read-only sources (git-ref,
   * HTTP) are intentionally skipped — pruning them is the producer's concern.
   *
   * Returns `(removedIds across all sources, totalFreedBytes, perSourceResults)`. When [reason] is
   * non-null and the combined removed set is non-empty, fires the [pruneListener] (if set).
   *
   * @param config the pruning policy to apply. The auto-prune scheduler passes [pruneConfig];
   *   manual `history/prune` callers pass a per-call overlay (explicit params over defaults).
   * @param dryRun when true, no disk mutations happen; returns the would-remove set.
   * @param reason controls the [PruneNotification.reason] field — `AUTO` for the scheduler,
   *   `MANUAL` for `history/prune`. Null suppresses the listener (used by dry-run probes).
   */
  fun pruneNow(
    config: HistoryPruneConfig = pruneConfig,
    dryRun: Boolean = false,
    reason: PruneReason? = null,
  ): PruneAggregateResult {
    val perSource = LinkedHashMap<String, PruneResult>()
    val combinedRemoved = mutableListOf<String>()
    var combinedFreed = 0L
    for (source in sources) {
      if (!source.supportsWrites()) continue
      val result =
        try {
          source.prune(config, dryRun = dryRun)
        } catch (t: Throwable) {
          System.err.println(
            "compose-ai-daemon: HistoryManager.prune: source ${source.id} prune() threw " +
              "(${t.javaClass.simpleName}: ${t.message}); skipping"
          )
          PruneResult.EMPTY
        }
      perSource[source.id] = result
      combinedRemoved.addAll(result.removedEntryIds)
      combinedFreed += result.freedBytes
    }
    val aggregate =
      PruneAggregateResult(
        removedEntryIds = combinedRemoved,
        freedBytes = combinedFreed,
        sourceResults = perSource,
      )
    if (!dryRun && reason != null && combinedRemoved.isNotEmpty()) {
      val listener = pruneListener
      if (listener != null) {
        try {
          listener(
            PruneNotification(
              removedIds = combinedRemoved.toList(),
              freedBytes = combinedFreed,
              reason = reason,
            )
          )
        } catch (t: Throwable) {
          System.err.println(
            "compose-ai-daemon: HistoryManager.pruneNow: pruneListener threw " +
              "(${t.javaClass.simpleName}: ${t.message}); ignoring"
          )
        }
      }
    }
    return aggregate
  }

  /**
   * H4 — starts the auto-prune scheduler.
   *
   * **Lifecycle.** Schedules one initial pass after [initialDelayMs] (default few seconds — runs
   * after the daemon's sandbox bootstrap, so first prune doesn't compete with cold-start I/O), then
   * repeats every [HistoryPruneConfig.autoPruneIntervalMs]. [stopAutoPrune] cancels and joins the
   * scheduler.
   *
   * **All-off short-circuit.** When [pruneConfig].isAllOff is true, this is a no-op — the scheduler
   * thread is never created. Same for any non-positive [HistoryPruneConfig.autoPruneIntervalMs].
   *
   * **Safety.** Reentrancy-guarded — second call with the scheduler already running is a no-op. The
   * scheduler thread is daemonised so the JVM can exit even if [stopAutoPrune] isn't called.
   */
  fun startAutoPrune(initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS) {
    val config = pruneConfig
    if (config.isAllOff) return
    if (config.autoPruneIntervalMs <= 0L) return
    if (!isEnabled) return
    if (autoPruneStopped.get()) return
    val existing = scheduler.get()
    if (existing != null) return
    val exec = Executors.newSingleThreadScheduledExecutor { runnable ->
      Thread(runnable, "compose-ai-daemon-history-auto-prune").apply { isDaemon = true }
    }
    if (!scheduler.compareAndSet(null, exec)) {
      // Lost a race — another thread set the scheduler first. Shut down our redundant exec.
      exec.shutdownNow()
      return
    }
    val future =
      exec.scheduleWithFixedDelay(
        {
          try {
            pruneNow(config = config, dryRun = false, reason = PruneReason.AUTO)
          } catch (t: Throwable) {
            System.err.println(
              "compose-ai-daemon: HistoryManager auto-prune pass failed " +
                "(${t.javaClass.simpleName}: ${t.message}); will retry on next interval"
            )
          }
        },
        initialDelayMs.coerceAtLeast(0L),
        config.autoPruneIntervalMs,
        TimeUnit.MILLISECONDS,
      )
    autoPruneFuture.set(future)
  }

  /**
   * H4 — stops the auto-prune scheduler. Idempotent. Cancels any in-flight pass via interrupt; the
   * pass observes the interrupt at its next syscall and exits without leaving the index in a
   * partial state (the rewrite is atomic).
   */
  fun stopAutoPrune() {
    autoPruneStopped.set(true)
    val future = autoPruneFuture.getAndSet(null)
    future?.cancel(true)
    val exec = scheduler.getAndSet(null) ?: return
    exec.shutdownNow()
    try {
      exec.awaitTermination(2, TimeUnit.SECONDS)
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    }
  }

  companion object {

    /** Initial delay for [startAutoPrune] — a few seconds, after the sandbox is up. */
    const val DEFAULT_INITIAL_DELAY_MS: Long = 5_000L

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
      pruneConfig: HistoryPruneConfig = HistoryPruneConfig(),
    ): HistoryManager {
      val sources =
        if (historyDir == null) emptyList()
        else listOf(LocalFsHistorySource(historyDir = historyDir))
      return HistoryManager(
        sources = sources,
        module = module,
        gitProvenance = gitProvenance,
        pruneConfig = pruneConfig,
      )
    }

    /**
     * H10-read — builds a manager with a writable [LocalFsHistorySource] plus zero or more
     * read-only [GitRefHistorySource]s. The local source is always priority-0 (writes go there);
     * git refs follow in declared order. See HISTORY.md § "Ordering semantics".
     *
     * @param gitRefs full ref names (`refs/heads/preview/main`); empty when no refs are configured.
     * @param warnEmitter passed to each [GitRefHistorySource] for the ref-missing case. The
     *   production daemon main passes a lambda that posts a warn-level `log` notification; tests
     *   pass a buffer-capturing lambda.
     */
    fun forLocalFsAndGitRefs(
      historyDir: java.nio.file.Path?,
      module: String,
      gitProvenance: GitProvenance?,
      gitRefs: List<String> = emptyList(),
      repoRoot: java.nio.file.Path? = historyDir?.parent,
      warnEmitter: (String) -> Unit = { System.err.println(it) },
      pruneConfig: HistoryPruneConfig = HistoryPruneConfig(),
    ): HistoryManager {
      val sources = buildList {
        if (historyDir != null) add(LocalFsHistorySource(historyDir = historyDir))
        if (repoRoot != null) {
          for (ref in gitRefs) {
            add(
              GitRefHistorySource(
                repoRoot = repoRoot,
                ref = ref,
                cacheDir =
                  historyDir?.let(GitRefHistorySource::defaultCacheDir)
                    ?: repoRoot.resolve(".compose-preview-history").resolve(".git-ref-cache"),
                warnEmitter = warnEmitter,
              )
            )
          }
        }
      }
      return HistoryManager(
        sources = sources,
        module = module,
        gitProvenance = gitProvenance,
        pruneConfig = pruneConfig,
      )
    }
  }
}
