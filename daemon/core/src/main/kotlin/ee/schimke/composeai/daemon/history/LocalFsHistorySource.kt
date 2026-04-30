package ee.schimke.composeai.daemon.history

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
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
 * **Dedup-by-hash, two tiers:**
 *
 * 1. **Skip-on-most-recent-match.** When [write] is called with bytes whose SHA-256 matches the
 *    most-recent existing entry for the same `previewId`, [write] returns
 *    [WriteResult.SKIPPED_DUPLICATE] and writes nothing — no PNG, no sidecar, no index line. The
 *    consumer's UI doesn't see a redundant entry for save-loops that produce identical pixels
 *    (e.g. comment-only edits, sandbox warm-up renders against the same composition). Distinct
 *    from tier 2 below: the rule is the *most recent* match, not any match — render history
 *    A → B → A still keeps the third entry because going-back-to-A is a meaningful event.
 *
 * 2. **Pointer-on-any-match (fallback for non-consecutive matches).** When the new bytes don't
 *    match the most-recent entry but DO match an earlier one (the A → B → A case), the new
 *    sidecar's `pngPath` points at the older PNG and we don't re-write the bytes. The sidecar +
 *    index line still land so the provenance entry exists.
 *
 * Both tiers reduce on-disk PNG copies; tier 1 additionally suppresses sidecar churn. HISTORY.md
 * § "What this PR lands § H1" + § "Dedup".
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

  override fun write(entry: HistoryEntry, png: ByteArray): WriteResult {
    val sanitisedDir = PreviewIdSanitiser.sanitise(entry.previewId)
    val previewDir = historyDir.resolve(sanitisedDir)
    Files.createDirectories(previewDir)

    // Tier 1 — skip-on-most-recent-match. If the absolute newest sidecar for this preview already
    // has the same hash, this render is redundant from the consumer's perspective; skip everything.
    // Different from tier 2 below: rule is "most recent", not "any match" — A → B → A keeps three
    // entries (the third is a meaningful "we went back to A" event), but A → A → A keeps one.
    val mostRecentHash = findMostRecentEntryHash(previewDir, exclude = entry.id)
    if (mostRecentHash != null && mostRecentHash == entry.pngHash) {
      return WriteResult.SKIPPED_DUPLICATE
    }

    val pngFileName = "${entry.id}.png"
    val sidecarFileName = "${entry.id}.json"
    val pngFile = previewDir.resolve(pngFileName)
    val sidecarFile = previewDir.resolve(sidecarFileName)

    // Tier 2 — pointer-on-any-match. The bytes don't match the most-recent entry but DO match an
    // earlier one (e.g. A → B → A). Point the new sidecar's `pngPath` at the older PNG so we
    // don't write a duplicate file; the sidecar + index line still land because the entry itself
    // is meaningful provenance.
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
    return WriteResult.WRITTEN
  }

  /**
   * Returns the [HistoryEntry.pngHash] of the absolute newest sidecar in [previewDir], or null
   * when the dir is empty or all sidecars are unreadable. Used by tier 1 of the dedup ladder.
   *
   * Sidecar filenames lead with a UTC timestamp in `yyyyMMdd-HHmmss-<hash>` shape, so reverse
   * lex sort = newest first. We don't need to parse the timestamp — string compare is enough.
   */
  private fun findMostRecentEntryHash(previewDir: Path, exclude: String): String? {
    if (!Files.exists(previewDir)) return null
    val newest =
      try {
        Files.list(previewDir).use { stream ->
          stream
            .filter { it.fileName.toString().endsWith(".json") }
            .filter { it.fileName.toString().removeSuffix(".json") != exclude }
            .max(Comparator.comparing { it.fileName.toString() })
            .orElse(null)
        }
      } catch (_: Throwable) {
        return null
      } ?: return null
    val text =
      try {
        newest.readText(StandardCharsets.UTF_8)
      } catch (_: Throwable) {
        return null
      }
    val parsed =
      try {
        JSON.decodeFromString(HistoryEntry.serializer(), text)
      } catch (_: Throwable) {
        return null
      }
    return parsed.pngHash
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
