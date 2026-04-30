package ee.schimke.composeai.daemon.history

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlinx.serialization.json.Json

/**
 * Filesystem-backed [HistorySource] — the H1 default. Writes PNGs + sidecar JSON files plus an
 * append-only `index.jsonl` under [historyDir], following the layout pinned in HISTORY.md §
 * "On-disk schema".
 *
 * **Layout:**
 *
 * ```
 * <historyDir>/
 * ├── index.jsonl
 * └── <sanitisedPreviewId>/
 *     ├── <utc-yyyyMMdd-HHmmss>-<8hex>.png
 *     └── <utc-yyyyMMdd-HHmmss>-<8hex>.json
 * ```
 *
 * **Dedup-by-hash.** When [write] is called with bytes whose SHA-256 matches the most recent
 * entry's [HistoryEntry.pngHash] for the same `previewId`, the PNG file is NOT re-written; the
 * sidecar's `pngPath` points at the already-on-disk PNG. The sidecar + index line still land —
 * "same hash" means no visible change, but the render still happened, so the provenance entry is
 * useful (e.g. "agent re-ran, output unchanged"). HISTORY.md § "What this PR lands § H1".
 *
 * **Cross-restart correctness.** Dedup walks the per-preview directory listing for the most-recent
 * entry whose hash matches; this works whether that entry was written in the current daemon
 * lifetime or a previous one.
 *
 * **Concurrency.** Designed for the single-writer daemon model from HISTORY.md § "Concurrency
 * model". `index.jsonl` writes use [StandardOpenOption.APPEND] (POSIX `O_APPEND`); PNGs and
 * sidecars use distinct filenames per render so two writes never race on the same file.
 */
class LocalFsHistorySource(private val historyDir: Path) : HistorySource {

  /** `fs:<absoluteHistoryDir>` — HISTORY.md § "Built-in sources § LocalFsHistorySource". */
  override val id: String = "fs:${historyDir.toAbsolutePath()}"

  override val kind: String = "fs"

  override fun supportsWrites(): Boolean = true

  init {
    Files.createDirectories(historyDir)
  }

  override fun write(entry: HistoryEntry, png: ByteArray) {
    val sanitisedDir = PreviewIdSanitiser.sanitise(entry.previewId)
    val previewDir = historyDir.resolve(sanitisedDir)
    Files.createDirectories(previewDir)

    val pngFileName = "${entry.id}.png"
    val sidecarFileName = "${entry.id}.json"
    val pngFile = previewDir.resolve(pngFileName)
    val sidecarFile = previewDir.resolve(sidecarFileName)

    // Dedup-by-hash. If the previous entry for this preview has the same pngHash, we point the
    // new sidecar's `pngPath` at that older PNG and do NOT re-write the bytes. We still write
    // the sidecar + index line so the render-event provenance is captured.
    val dedupTarget = findMostRecentEntryWithHash(previewDir, entry.pngHash, exclude = entry.id)
    val effectivePngPath: String =
      if (dedupTarget != null) {
        // Sidecar's pngPath is relative to the sidecar's own directory; we keep that convention.
        dedupTarget
      } else {
        // Fresh bytes — write the PNG.
        pngFile.writeBytes(png)
        pngFileName
      }

    val canonicalEntry =
      if (entry.pngPath == effectivePngPath) entry else entry.copy(pngPath = effectivePngPath)
    val sidecarText = JSON.encodeToString(HistoryEntry.serializer(), canonicalEntry)
    sidecarFile.writeText(sidecarText, StandardCharsets.UTF_8)

    // index.jsonl append. Build a "lite" form by encoding the same entry minus the heavy
    // previewMetadata snapshot — readers fetch the full sidecar via `history/read`. HISTORY.md
    // § "index.jsonl" says: "same fields as the sidecar minus `previewMetadata`".
    val indexEntry = canonicalEntry.copy(previewMetadata = null)
    val indexLine = JSON.encodeToString(HistoryEntry.serializer(), indexEntry) + "\n"
    val indexFile = historyDir.resolve(INDEX_FILENAME)
    Files.write(
      indexFile,
      indexLine.toByteArray(StandardCharsets.UTF_8),
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE,
      StandardOpenOption.APPEND,
    )
  }

  override fun list(filter: HistoryFilter): HistoryListPage {
    val indexFile = historyDir.resolve(INDEX_FILENAME)
    if (!indexFile.exists()) return HistoryListPage(entries = emptyList(), totalCount = 0)
    val allEntries = readIndexNewestFirst(indexFile)
    val matched = allEntries.filter { HistoryFilters.matches(it, filter) }
    val totalCount = matched.size
    val slice = HistoryFilters.paginate(matched, filter)
    return HistoryListPage(
      entries = slice.entries,
      nextCursor = slice.nextCursor,
      totalCount = totalCount,
    )
  }

  override fun read(entryId: String, includeBytes: Boolean): HistoryReadResult? {
    val sidecar = findSidecar(entryId) ?: return null
    val entry =
      try {
        JSON.decodeFromString(HistoryEntry.serializer(), sidecar.readText(StandardCharsets.UTF_8))
      } catch (t: Throwable) {
        System.err.println(
          "compose-ai-daemon: LocalFsHistorySource.read($entryId): malformed sidecar at $sidecar " +
            "(${t.javaClass.simpleName}: ${t.message})"
        )
        return null
      }
    val pngPath = sidecar.parent.resolve(entry.pngPath).toAbsolutePath()
    if (!pngPath.exists()) {
      System.err.println(
        "compose-ai-daemon: LocalFsHistorySource.read($entryId): sidecar references missing PNG " +
          "$pngPath"
      )
      return null
    }
    val bytes = if (includeBytes) pngPath.readBytes() else null
    return HistoryReadResult(
      entry = entry,
      previewMetadata = entry.previewMetadata,
      pngPath = pngPath.toString(),
      pngBytes = bytes,
    )
  }

  /**
   * H4 — applies the pruning policy from HISTORY.md § "Pruning policy". Loads the index newest-
   * first, runs the three-pass cascade (age → per-preview count → total size), enforces the
   * "never drop the most recent entry per preview" floor, then either:
   *
   * - **Dry run** ([dryRun] = true): returns the would-remove set + freed bytes; does NOT touch
   *   disk.
   * - **Live** ([dryRun] = false): deletes sidecar `.json` files; deletes PNG files only when no
   *   surviving sidecar references the same file (dedup-by-hash means N sidecars may share one
   *   PNG); rewrites `index.jsonl` atomically (write-temp-then-atomic-rename) so an interrupted
   *   prune leaves either the old or the new index — never partial.
   *
   * Failures in individual file deletes are logged to stderr and swallowed — pruning never fails
   * the daemon. The on-disk archive is self-healing on the next pass.
   */
  override fun prune(config: HistoryPruneConfig, dryRun: Boolean): PruneResult {
    val indexFile = historyDir.resolve(INDEX_FILENAME)
    if (!indexFile.exists()) return PruneResult.EMPTY

    // Load every entry from the index — self-heals against missing sidecars + malformed lines.
    // Sort newest-first by `timestamp` then `id`. The append-order from production writes is
    // already chronological, but explicit sort makes prune robust against backdated entries
    // (e.g. tests that write in reversed temporal order, or future migration tools).
    val all: List<HistoryEntry> =
      readIndexNewestFirst(indexFile)
        .sortedWith(compareByDescending<HistoryEntry> { it.timestamp }.thenByDescending { it.id })
    if (all.isEmpty()) return PruneResult.EMPTY

    // Compute the "must always survive" set: most recent entry per previewId. This floor applies
    // throughout — every pruning pass intersects against this set so a violator who happens to be
    // the only/most-recent entry for its preview survives.
    val mostRecentPerPreview: Map<String, String> =
      buildMap {
        // `all` is newest-first, so the FIRST occurrence of each previewId is the most recent.
        for (entry in all) {
          if (!containsKey(entry.previewId)) put(entry.previewId, entry.id)
        }
      }

    // Tracks ids marked for removal. Uses LinkedHashSet for stable iteration (= test-friendly).
    val toRemove = LinkedHashSet<String>()

    fun protect(entry: HistoryEntry): Boolean = mostRecentPerPreview[entry.previewId] == entry.id

    // -----------------------------------------------------------------------------------
    // Pass 1 — age. Drop entries with timestamp < (now - maxAgeDays).
    // -----------------------------------------------------------------------------------
    if (config.maxAgeDays > 0) {
      val cutoff = Instant.now().minusSeconds(config.maxAgeDays.toLong() * 86_400L)
      val cutoffString = OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC).format(ISO_TS)
      for (entry in all) {
        if (entry.id in toRemove) continue
        if (protect(entry)) continue
        if (entry.timestamp < cutoffString) toRemove.add(entry.id)
      }
    }

    // -----------------------------------------------------------------------------------
    // Pass 2 — per-preview count. Keep newest N per preview; mark the rest for removal.
    // -----------------------------------------------------------------------------------
    if (config.maxEntriesPerPreview > 0) {
      val grouped: Map<String, List<HistoryEntry>> =
        all.filter { it.id !in toRemove }.groupBy { it.previewId }
      for ((_, group) in grouped) {
        // group is in original (newest-first) order — Kotlin's groupBy preserves first-encounter
        // order. Survivors = first N; older are pruned.
        val excess = group.drop(config.maxEntriesPerPreview)
        for (entry in excess) {
          if (protect(entry)) continue
          toRemove.add(entry.id)
        }
      }
    }

    // -----------------------------------------------------------------------------------
    // Pass 3 — total size. If surviving set still exceeds maxTotalSizeBytes, drop oldest first.
    // -----------------------------------------------------------------------------------
    if (config.maxTotalSizeBytes > 0L) {
      val survivors = all.filter { it.id !in toRemove }
      var totalSize = survivors.sumOf { it.pngSize }
      if (totalSize > config.maxTotalSizeBytes) {
        // Walk oldest → newest, drop until under threshold. `survivors` is newest-first, so iterate
        // in reverse.
        for (entry in survivors.asReversed()) {
          if (totalSize <= config.maxTotalSizeBytes) break
          if (protect(entry)) continue
          if (entry.id in toRemove) continue
          toRemove.add(entry.id)
          totalSize -= entry.pngSize
        }
      }
    }

    if (toRemove.isEmpty()) return PruneResult.EMPTY

    val removedEntries = all.filter { it.id in toRemove }
    val survivors = all.filter { it.id !in toRemove }

    // Compute freedBytes — only count entries whose PNG file actually goes away. A sidecar B'
    // pointing at the same `<id>.png` filename as a surviving sidecar B leaves the PNG on disk.
    // For dedup-by-hash, multiple sidecars may share one PNG: the dedup-target's sidecar's pngPath
    // is the *original* PNG's filename, so survivors[].pngPath stays referenced as long as one
    // sidecar with that pngPath survives.
    val survivingPngPaths: Set<Pair<String, String>> = // (sanitisedPreviewDir, pngPath)
      survivors.mapTo(HashSet()) { PreviewIdSanitiser.sanitise(it.previewId) to it.pngPath }
    val freedBytes =
      removedEntries.sumOf { entry ->
        val key = PreviewIdSanitiser.sanitise(entry.previewId) to entry.pngPath
        if (key in survivingPngPaths) 0L else entry.pngSize
      }

    if (dryRun) {
      return PruneResult(
        removedEntryIds = removedEntries.map { it.id },
        freedBytes = freedBytes,
      )
    }

    // -----------------------------------------------------------------------------------
    // Live mode — actually mutate disk.
    // -----------------------------------------------------------------------------------
    // 1. Rewrite the index first (atomic via tempfile + rename). If anything below fails, the
    //    survivor entries are still readable; orphaned PNGs are tolerable (they're effectively
    //    leaked bytes that the next prune can re-discover via timestamp comparison).
    val indexTempFile = historyDir.resolve("$INDEX_FILENAME.tmp")
    try {
      val sb = StringBuilder()
      // index.jsonl is append-order = oldest first. survivors is newest-first; reverse for write.
      for (entry in survivors.asReversed()) {
        // Match the live append shape: same fields as the sidecar, minus previewMetadata.
        val indexEntry = entry.copy(previewMetadata = null)
        sb.append(JSON.encodeToString(HistoryEntry.serializer(), indexEntry))
        sb.append('\n')
      }
      Files.write(
        indexTempFile,
        sb.toString().toByteArray(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE,
      )
      try {
        Files.move(
          indexTempFile,
          indexFile,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING,
        )
      } catch (_: Throwable) {
        // ATOMIC_MOVE is not supported on every filesystem (older NFS, some Windows configs);
        // fall back to a non-atomic rename. The window between the two is tiny and the survivors
        // are still bytewise complete on disk in the tempfile until rename finishes.
        Files.move(indexTempFile, indexFile, StandardCopyOption.REPLACE_EXISTING)
      }
    } catch (t: Throwable) {
      // Index rewrite failed → bail out without touching sidecars / PNGs. Leaves the on-disk
      // state untouched (since the index move never landed). Try to clean the tempfile.
      try {
        Files.deleteIfExists(indexTempFile)
      } catch (_: Throwable) {
        // ignore
      }
      System.err.println(
        "compose-ai-daemon: LocalFsHistorySource.prune: index rewrite failed " +
          "(${t.javaClass.simpleName}: ${t.message}); leaving archive untouched"
      )
      return PruneResult.EMPTY
    }

    // 2. Delete sidecars for every removed entry.
    for (entry in removedEntries) {
      val sanitised = PreviewIdSanitiser.sanitise(entry.previewId)
      val sidecar = historyDir.resolve(sanitised).resolve("${entry.id}.json")
      try {
        Files.deleteIfExists(sidecar)
      } catch (t: Throwable) {
        System.err.println(
          "compose-ai-daemon: LocalFsHistorySource.prune: sidecar delete failed for " +
            "$sidecar (${t.javaClass.simpleName}: ${t.message})"
        )
      }
    }

    // 3. Delete PNGs for removed entries — only when no surviving sidecar references the file.
    for (entry in removedEntries) {
      val sanitised = PreviewIdSanitiser.sanitise(entry.previewId)
      val key = sanitised to entry.pngPath
      if (key in survivingPngPaths) continue
      val pngFile = historyDir.resolve(sanitised).resolve(entry.pngPath)
      try {
        Files.deleteIfExists(pngFile)
      } catch (t: Throwable) {
        System.err.println(
          "compose-ai-daemon: LocalFsHistorySource.prune: PNG delete failed for " +
            "$pngFile (${t.javaClass.simpleName}: ${t.message})"
        )
      }
    }

    return PruneResult(
      removedEntryIds = removedEntries.map { it.id },
      freedBytes = freedBytes,
    )
  }

  /**
   * Walks the per-preview directory looking for the most recent (lex-sorted) sidecar whose pngHash
   * matches [hash]. Returns the relative PNG filename of the match, or null when no match.
   */
  private fun findMostRecentEntryWithHash(
    previewDir: Path,
    hash: String,
    exclude: String,
  ): String? {
    if (!Files.exists(previewDir)) return null
    val sidecars =
      try {
        Files.list(previewDir).use { stream ->
          stream
            .filter { it.fileName.toString().endsWith(".json") }
            .filter { it.fileName.toString().removeSuffix(".json") != exclude }
            .sorted(Comparator.reverseOrder())
            .toArray()
            .map { it as Path }
        }
      } catch (t: Throwable) {
        return null
      }
    for (sidecar in sidecars) {
      val text =
        try {
          sidecar.readText(StandardCharsets.UTF_8)
        } catch (_: Throwable) {
          continue
        }
      val parsed =
        try {
          JSON.decodeFromString(HistoryEntry.serializer(), text)
        } catch (_: Throwable) {
          continue
        }
      if (parsed.pngHash == hash) {
        // Verify the referenced PNG actually exists; if not, fall through.
        val pngPath = sidecar.parent.resolve(parsed.pngPath)
        if (Files.exists(pngPath)) return parsed.pngPath
      }
    }
    return null
  }

  /**
   * Locates a sidecar by [entryId]. Walks per-preview directories under [historyDir] until the
   * matching `<entryId>.json` is found.
   */
  private fun findSidecar(entryId: String): Path? {
    if (!Files.exists(historyDir)) return null
    return Files.list(historyDir).use { stream ->
      stream
        .filter { Files.isDirectory(it) }
        .map { it.resolve("$entryId.json") }
        .filter { Files.exists(it) }
        .findFirst()
        .orElse(null)
    }
  }

  private fun readIndexNewestFirst(indexFile: Path): List<HistoryEntry> {
    val lines = Files.readAllLines(indexFile, StandardCharsets.UTF_8)
    val parsed = ArrayList<HistoryEntry>(lines.size)
    for (line in lines) {
      val trimmed = line.trim()
      if (trimmed.isEmpty()) continue
      val entry =
        try {
          JSON.decodeFromString(HistoryEntry.serializer(), trimmed)
        } catch (t: Throwable) {
          System.err.println(
            "compose-ai-daemon: LocalFsHistorySource.list: skipping malformed index line " +
              "(${t.javaClass.simpleName}: ${t.message})"
          )
          continue
        }
      // Self-heal: drop entries whose sidecar isn't on disk. HISTORY.md § "Compatibility with
      // today's layout" / § "Provenance trust" — a missing sidecar is treated as corruption and
      // the entry is dropped from listings.
      val sanitised = PreviewIdSanitiser.sanitise(entry.previewId)
      val sidecar = historyDir.resolve(sanitised).resolve("${entry.id}.json")
      if (!Files.exists(sidecar)) {
        System.err.println(
          "compose-ai-daemon: LocalFsHistorySource.list: dropping index entry ${entry.id} " +
            "(sidecar missing at $sidecar)"
        )
        continue
      }
      parsed.add(entry)
    }
    parsed.reverse()
    return parsed
  }

  companion object {
    const val INDEX_FILENAME: String = "index.jsonl"
    /** Default limit for [HistoryFilter.limit]. Pinned in [HistoryFilters] for shared use. */
    const val DEFAULT_LIMIT: Int = HistoryFilters.DEFAULT_LIMIT
    /** Hard ceiling for [HistoryFilter.limit]. Pinned in [HistoryFilters] for shared use. */
    const val MAX_LIMIT: Int = HistoryFilters.MAX_LIMIT

    /**
     * JSON configuration shared across the LocalFs path. `encodeDefaults = false` keeps the sidecar
     * JSON minimal — null fields don't land on disk; readers tolerate their absence.
     * `ignoreUnknownKeys = true` keeps forward-compat: a v2 schema add doesn't break a v1 reader.
     */
    private val JSON: Json = Json {
      ignoreUnknownKeys = true
      encodeDefaults = false
      prettyPrint = false
    }

    /** Pinned ISO-8601 UTC formatter for prune-cutoff comparisons. */
    private val ISO_TS: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    /** SHA-256 hex of [bytes]. */
    fun sha256Hex(bytes: ByteArray): String {
      val digest = MessageDigest.getInstance("SHA-256")
      val hash = digest.digest(bytes)
      val sb = StringBuilder(hash.size * 2)
      for (b in hash) {
        val v = b.toInt() and 0xff
        sb.append(HEX_CHARS[v ushr 4])
        sb.append(HEX_CHARS[v and 0x0f])
      }
      return sb.toString()
    }

    private val HEX_CHARS = "0123456789abcdef".toCharArray()
  }
}
