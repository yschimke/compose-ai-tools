package ee.schimke.composeai.daemon.history

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json

/**
 * Read-only [HistorySource] backed by a git ref (e.g. `refs/heads/preview/main`).
 *
 * **Storage convention** (HISTORY.md § "GitRefHistorySource"):
 * ```
 * <ref>'s tree
 * ├── <sanitisedPreviewId>/
 * │   ├── <entryId>.png
 * │   └── <entryId>.json
 * ├── ...
 * └── _index.jsonl                ← aggregate of every entry on this ref's HEAD commit
 * ```
 *
 * **Implementation strategy.** Shells out to `git` via [ProcessBuilder] (mirrors [GitProvenance]'s
 * pattern). No JGit dependency. The shell-out cost is fine for read operations and we cache results
 * keyed by the ref's HEAD commit-sha so repeated calls during a single session don't re-extract
 * blobs.
 *
 * **Ref-missing behaviour.** When `git rev-parse --verify <ref>` fails, [list] / [read] return
 * empty / null and emit a one-time warn-level notification via [warnEmitter] explaining how to
 * populate the ref. The daemon doesn't fail; the consumer just sees "no main-history available."
 *
 * **Source rewriting.** The on-ref sidecars carry whatever `source` field they were written with —
 * but we know the source is *us* now. Both [list] and [read] rewrite `entry.source` to a `kind:
 * "git"` shape stamped with this source's id, the ref name, and the ref's HEAD commit sha.
 *
 * **PNG extraction.** [read] writes the PNG blob into a daemon-managed cache dir
 * (`<historyDir>/.git-ref-cache/`) keyed by `(refCommit, entryId)`. Subsequent reads of the same
 * blob hit the cache. Tempfiles are cleaned up on JVM shutdown via [Runtime.addShutdownHook].
 *
 * @param repoRoot the working tree (or bare repo) the ref lives in.
 * @param ref the full ref name (e.g. `refs/heads/preview/main`); use full form to avoid ambiguity
 *   between heads, remotes, and tags.
 * @param displayId stable identifier for `entry.source.id` rewriting; defaults to `git:$ref`.
 * @param cacheDir where extracted PNG blobs land; defaults to
 *   `<repoRoot>/.compose-preview-history/.git-ref-cache`.
 * @param gitExecutable git binary; defaults to `git` on PATH.
 * @param warnEmitter logger for the ref-missing case. The production daemon passes
 *   [JsonRpcServer]'s log emitter; tests pass a buffer-capturing lambda.
 */
class GitRefHistorySource(
  private val repoRoot: Path,
  private val ref: String,
  displayId: String = "git:$ref",
  private val cacheDir: Path =
    repoRoot.resolve(".compose-preview-history").resolve(".git-ref-cache"),
  private val gitExecutable: String = "git",
  private val warnEmitter: (String) -> Unit = { System.err.println(it) },
) : HistorySource {

  override val id: String = displayId
  override val kind: String = "git"

  override fun supportsWrites(): Boolean = false

  override fun write(entry: HistoryEntry, png: ByteArray): WriteResult {
    error("GitRefHistorySource is read-only (ref=$ref); writes go to a writable source.")
  }

  /**
   * Cache of `(refCommit, _index.jsonl-bytes)`. Invalidated when [refHeadCommit] returns a
   * different sha. Populated lazily on first [list] / [read].
   */
  private val cachedIndex: AtomicReference<CachedIndex?> = AtomicReference(null)

  /** One-shot guard so a recurring "ref missing" doesn't flood the log. */
  private val refMissingWarned = AtomicBoolean(false)

  private data class CachedIndex(val refCommit: String, val entries: List<HistoryEntry>)

  init {
    // Best-effort: ensure the cache dir exists if the parent already does. Failures here don't
    // fail the source — read() recreates as needed.
    try {
      Files.createDirectories(cacheDir)
    } catch (_: Throwable) {
      // ignore — we'll retry in read()
    }
  }

  override fun list(filter: HistoryFilter): HistoryListPage {
    val refCommit = refHeadCommit() ?: return emptyPage()
    val entries = loadIndex(refCommit) ?: return emptyPage()
    val rewritten = entries.map { rewriteSource(it, refCommit) }
    val matched = rewritten.filter { HistoryFilters.matches(it, filter) }
    val totalCount = matched.size
    val slice = HistoryFilters.paginate(matched, filter)
    return HistoryListPage(
      entries = slice.entries,
      nextCursor = slice.nextCursor,
      totalCount = totalCount,
    )
  }

  override fun read(entryId: String, includeBytes: Boolean): HistoryReadResult? {
    val refCommit = refHeadCommit() ?: return null
    val entries = loadIndex(refCommit) ?: return null
    val match = entries.firstOrNull { it.id == entryId } ?: return null
    val rewritten = rewriteSource(match, refCommit)

    // Resolve the sidecar path on the ref. The on-disk layout uses a sanitised previewId dir, and
    // entry.pngPath is "<entryId>.png" (relative to that dir). We re-fetch the *full* sidecar so
    // we get the previewMetadata snapshot back.
    val sanitised = PreviewIdSanitiser.sanitise(match.previewId)
    val sidecarPath = "$sanitised/$entryId.json"
    val sidecarText = catFile(refCommit, sidecarPath) ?: return null
    val fullEntry =
      try {
        JSON.decodeFromString(HistoryEntry.serializer(), sidecarText)
      } catch (t: Throwable) {
        System.err.println(
          "compose-ai-daemon: GitRefHistorySource.read($entryId): malformed sidecar at " +
            "$ref:$sidecarPath (${t.javaClass.simpleName}: ${t.message})"
        )
        return null
      }
    val rewrittenFull = rewriteSource(fullEntry, refCommit)

    // Extract PNG blob to the cache dir.
    val pngRel = "$sanitised/$entryId.png"
    val pngFile = extractBlobToCache(refCommit, pngRel) ?: return null
    val bytes = if (includeBytes) Files.readAllBytes(pngFile) else null

    return HistoryReadResult(
      entry = rewrittenFull,
      previewMetadata = rewrittenFull.previewMetadata,
      pngPath = pngFile.toAbsolutePath().toString(),
      pngBytes = bytes,
    )
  }

  // -------------------------------------------------------------------------
  // Internals
  // -------------------------------------------------------------------------

  private fun rewriteSource(entry: HistoryEntry, refCommit: String): HistoryEntry =
    entry.copy(
      source =
        HistorySourceInfo(
          kind = "git",
          // We embed the commit + ref into the id so cross-source dedup ("same render visible in
          // both LocalFs and GitRef") can compare ids cheaply.
          id = "$id@${refCommit.take(7)}",
        )
    )

  /** Returns the ref's HEAD commit sha or null if the ref is missing. Emits one warn on miss. */
  private fun refHeadCommit(): String? {
    val out = runGit("rev-parse", "--verify", ref)
    if (out == null) {
      if (refMissingWarned.compareAndSet(false, true)) {
        val branch = ref.removePrefix("refs/heads/")
        warnEmitter(
          "GitRefHistorySource: ref '$ref' is not present locally.\n" +
            "  Hint: populate it by fetching from a remote (e.g. `git fetch origin $ref:$ref`)\n" +
            "  or set up CI to push render history on each merge to $branch.\n" +
            "  Until then, main-history comparison will not be available."
        )
      }
      return null
    }
    return out.takeIf { it.isNotEmpty() }
  }

  /**
   * Loads the `_index.jsonl` from the ref's tree, parsing line-by-line and tolerating truncated
   * lines (skip + warn). Cached by ref-commit-sha; invalidated when the sha shifts.
   */
  private fun loadIndex(refCommit: String): List<HistoryEntry>? {
    val cached = cachedIndex.get()
    if (cached != null && cached.refCommit == refCommit) return cached.entries

    val text = catFile(refCommit, INDEX_FILENAME)
    if (text == null) {
      // No index — empty ref. Cache the empty result so we don't re-shell for nothing.
      cachedIndex.set(CachedIndex(refCommit, emptyList()))
      return emptyList()
    }
    val parsed = ArrayList<HistoryEntry>()
    for (line in text.split('\n')) {
      val trimmed = line.trim()
      if (trimmed.isEmpty()) continue
      val entry =
        try {
          JSON.decodeFromString(HistoryEntry.serializer(), trimmed)
        } catch (t: Throwable) {
          System.err.println(
            "compose-ai-daemon: GitRefHistorySource.list($ref): skipping malformed index line " +
              "(${t.javaClass.simpleName}: ${t.message})"
          )
          continue
        }
      parsed.add(entry)
    }
    // _index.jsonl is append-order (oldest first); reverse for newest-first listing.
    parsed.reverse()
    val toCache = CachedIndex(refCommit, parsed)
    cachedIndex.set(toCache)
    return parsed
  }

  /**
   * Returns the contents of `<refCommit>:<path>` as text, or null if the path isn't present in the
   * tree. Uses `git show` with `--`. Trailing newline is preserved verbatim — the index parser
   * handles blank lines.
   */
  private fun catFile(refCommit: String, path: String): String? = runGit("show", "$refCommit:$path")

  /**
   * Extracts a binary blob from `<refCommit>:<path>` into [cacheDir]. Returns the cached file path
   * or null on failure. Idempotent: subsequent reads with the same key see the existing file.
   */
  private fun extractBlobToCache(refCommit: String, path: String): Path? {
    val safeName = "${refCommit.take(7)}-${path.replace('/', '_')}"
    val target = cacheDir.resolve(safeName)
    if (Files.exists(target)) return target

    try {
      Files.createDirectories(cacheDir)
    } catch (t: Throwable) {
      System.err.println(
        "compose-ai-daemon: GitRefHistorySource.extractBlobToCache: failed to create cache dir " +
          "$cacheDir (${t.javaClass.simpleName}: ${t.message})"
      )
      return null
    }

    // Use `git -C <repoRoot> show <refCommit>:<path>` and write stdout to the target tempfile via
    // ProcessBuilder.redirectOutput. Avoids holding the full PNG in JVM memory.
    val tmp =
      try {
        Files.createTempFile(cacheDir, "extract-", ".tmp")
      } catch (t: Throwable) {
        System.err.println(
          "compose-ai-daemon: GitRefHistorySource.extractBlobToCache: tempfile create failed " +
            "(${t.javaClass.simpleName}: ${t.message})"
        )
        return null
      }
    try {
      val process =
        ProcessBuilder(listOf(gitExecutable, "show", "$refCommit:$path"))
          .directory(repoRoot.toFile())
          .redirectErrorStream(false)
          .redirectOutput(tmp.toFile())
          .start()
      val finished = process.waitFor(30, TimeUnit.SECONDS)
      if (!finished) {
        process.destroyForcibly()
        Files.deleteIfExists(tmp)
        return null
      }
      if (process.exitValue() != 0) {
        Files.deleteIfExists(tmp)
        return null
      }
      // Atomic rename so concurrent reads don't see a half-written cache file. ATOMIC_MOVE may not
      // be supported on all FSes — fall back to REPLACE_EXISTING when not.
      try {
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      } catch (_: Throwable) {
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
      }
      return target
    } catch (t: IOException) {
      System.err.println(
        "compose-ai-daemon: GitRefHistorySource.extractBlobToCache: IO failure " +
          "(${t.javaClass.simpleName}: ${t.message})"
      )
      Files.deleteIfExists(tmp)
      return null
    } catch (t: Throwable) {
      Files.deleteIfExists(tmp)
      throw t
    }
  }

  private fun runGit(vararg args: String): String? {
    return try {
      val process =
        ProcessBuilder(listOf(gitExecutable) + args.toList())
          .directory(repoRoot.toFile())
          .redirectErrorStream(false)
          .start()
      val finished = process.waitFor(10, TimeUnit.SECONDS)
      if (!finished) {
        process.destroyForcibly()
        return null
      }
      if (process.exitValue() != 0) return null
      process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }.trimEnd('\n')
    } catch (_: Throwable) {
      null
    }
  }

  private fun emptyPage(): HistoryListPage =
    HistoryListPage(entries = emptyList(), nextCursor = null, totalCount = 0)

  companion object {
    /** Aggregate index filename on the ref's tree. HISTORY.md § "GitRefHistorySource". */
    const val INDEX_FILENAME: String = "_index.jsonl"

    /**
     * Sysprop name — comma-separated list of refs (e.g.
     * `refs/heads/preview/main,refs/heads/preview/agent/foo`). Wired by each per-target
     * [DaemonMain] alongside [LocalFsHistorySource].
     */
    const val GIT_REF_HISTORY_PROP: String = "composeai.daemon.gitRefHistory"

    private val JSON: Json = Json {
      ignoreUnknownKeys = true
      encodeDefaults = false
    }

    /**
     * Parses the [GIT_REF_HISTORY_PROP] sysprop (or [propValue] when explicit) into a list of
     * non-blank ref strings. Returns an empty list when the property is unset.
     */
    fun parseRefsSysprop(
      propValue: String? = System.getProperty(GIT_REF_HISTORY_PROP)
    ): List<String> =
      propValue?.split(',', ';')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    /**
     * Default cache dir for a given history dir. Mirrors the in-source default but exposed so
     * [DaemonMain] callers can pin the cache adjacent to the local-fs history dir for symmetric
     * lifecycle management.
     */
    fun defaultCacheDir(historyDir: Path): Path = historyDir.resolve(".git-ref-cache")
  }
}
