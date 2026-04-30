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

    val canonicalEntry = if (entry.pngPath == effectivePngPath) entry else entry.copy(pngPath = effectivePngPath)
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
    val matched = allEntries.filter { matchesFilter(it, filter) }
    val totalCount = matched.size

    // Cursor is an opaque base64 of "<timestamp>|<id>"; we drop entries until we pass it.
    val afterCursor =
      if (filter.cursor != null) {
        val decoded = decodeCursor(filter.cursor) ?: return HistoryListPage(emptyList(), totalCount = totalCount)
        matched.dropWhile { it.timestamp != decoded.timestamp || it.id != decoded.id }.drop(1)
      } else {
        matched
      }

    val limit = (filter.limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
    val page = afterCursor.take(limit)
    val nextCursor =
      if (afterCursor.size > limit) {
        val last = page.last()
        encodeCursor(last.timestamp, last.id)
      } else {
        null
      }
    return HistoryListPage(entries = page, nextCursor = nextCursor, totalCount = totalCount)
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
   * Walks the per-preview directory looking for the most recent (lex-sorted) sidecar whose
   * pngHash matches [hash]. Returns the relative PNG filename of the match, or null when no match.
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

  private fun matchesFilter(entry: HistoryEntry, f: HistoryFilter): Boolean {
    if (f.previewId != null && entry.previewId != f.previewId) return false
    if (f.since != null && entry.timestamp < f.since) return false
    if (f.until != null && entry.timestamp > f.until) return false
    if (f.branch != null && entry.git?.branch != f.branch) return false
    if (f.branchPattern != null) {
      val branch = entry.git?.branch ?: return false
      if (!Regex(f.branchPattern).matches(branch)) return false
    }
    if (f.commit != null) {
      val git = entry.git ?: return false
      if (git.commit != f.commit && git.shortCommit != f.commit) return false
    }
    if (f.worktreePath != null && entry.worktree?.path != f.worktreePath) return false
    if (f.agentId != null && entry.worktree?.agentId != f.agentId) return false
    if (f.sourceKind != null && entry.source.kind != f.sourceKind) return false
    if (f.sourceId != null && entry.source.id != f.sourceId) return false
    return true
  }

  companion object {
    const val INDEX_FILENAME: String = "index.jsonl"
    const val DEFAULT_LIMIT: Int = 50
    const val MAX_LIMIT: Int = 500

    /**
     * JSON configuration shared across the LocalFs path. `encodeDefaults = false` keeps the
     * sidecar JSON minimal — null fields don't land on disk; readers tolerate their absence.
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

    private fun encodeCursor(timestamp: String, id: String): String {
      val raw = "$timestamp|$id".toByteArray(Charsets.UTF_8)
      return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    private data class CursorPosition(val timestamp: String, val id: String)

    private fun decodeCursor(cursor: String): CursorPosition? {
      return try {
        val raw = java.util.Base64.getUrlDecoder().decode(cursor)
        val text = String(raw, Charsets.UTF_8)
        val sep = text.indexOf('|')
        if (sep < 0) return null
        CursorPosition(timestamp = text.substring(0, sep), id = text.substring(sep + 1))
      } catch (_: Throwable) {
        null
      }
    }
  }
}

