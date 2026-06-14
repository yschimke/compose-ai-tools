package ee.schimke.composeai.daemon.history

import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.SemanticsDiff
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * [HistorySource] backed by a git ref (the **reporting branch**, e.g. `refs/heads/preview/main`).
 *
 * Implements the "git-as-the-log" contract in docs/daemon/REPORTING-BRANCH.md: stable, overwritten
 * per-preview paths, one commit per *changed* render, history reconstructed from git rather than an
 * append index.
 *
 * **On-ref layout:**
 *
 * ```
 * <ref>'s tree
 * ├── manifest.json                       ← current-state pointer (formatVersion + previews[])
 * └── <sanitisedPreviewId>/
 *     ├── render.png                       ← overwritten each render
 *     ├── entry.json                       ← the full HistoryEntry sidecar
 *     ├── semantics.json                   ← optional, when entry.semantics is non-null
 *     ├── a11y.json                        ← optional, a11y/hierarchy
 *     ├── a11y-atf.json                    ← optional, a11y/atf
 *     ├── a11y-touch-targets.json          ← optional, a11y/touchTargets
 *     └── theme.json                       ← optional, compose/theme
 * ```
 *
 * **Sync modes** ([SyncMode]):
 * - [SyncMode.READ_ONLY] (default) — read the ref, never write. Used to mirror a branch populated
 *   elsewhere (a remote fetch, CI).
 * - [SyncMode.WRITE_LOCAL] — also commit each changed render onto the ref via git plumbing. No
 *   push. `WRITE_PUSH` (also pushing the ref) is a follow-up (issue #1870).
 *
 * **Working-tree safety.** Writes go through a throwaway temporary index plus `hash-object` /
 * `read-tree` / `update-index` / `write-tree` / `commit-tree` / `update-ref` — the daemon runs
 * inside the user's live repo, so it must never `checkout` / `add` or otherwise touch the working
 * tree or the checked-out branch. Only the reporting ref and the object database are modified.
 *
 * **Skip-if-no-diff.** When the freshly built tree is byte-identical to the ref's current tree, no
 * commit is made and [write] returns [WriteResult.SKIPPED_DUPLICATE]. Dedup is free.
 *
 * **Read.** Returns the *current* state of the branch (one entry per preview, read from each
 * `<dir>/entry.json`). The full commit-walk timeline read is a follow-up (issue #1868).
 *
 * @param repoRoot the working tree (or bare repo) the ref lives in.
 * @param ref the full ref name (e.g. `refs/heads/preview/main`).
 * @param syncMode read-only vs write-local; see [SyncMode].
 * @param displayId stable identifier for `entry.source.id` rewriting; defaults to `git:$ref`.
 * @param cacheDir where extracted PNG blobs and the throwaway write-index land.
 * @param gitExecutable git binary; defaults to `git` on PATH.
 * @param warnEmitter logger for the ref-missing read case.
 */
class GitRefHistorySource(
  private val repoRoot: Path,
  private val ref: String,
  private val syncMode: SyncMode = SyncMode.READ_ONLY,
  displayId: String = "git:$ref",
  private val cacheDir: Path =
    repoRoot.resolve(".compose-preview-history").resolve(".git-ref-cache"),
  private val gitExecutable: String = "git",
  private val warnEmitter: (String) -> Unit = { System.err.println(it) },
) : HistorySource {

  override val id: String = displayId
  override val kind: String = "git"

  override fun supportsWrites(): Boolean = syncMode == SyncMode.WRITE_LOCAL

  /**
   * Materialises this render into the reporting ref's tree and commits it (when the tree changed).
   * Never throws: any git failure degrades to [WriteResult.SKIPPED_DUPLICATE] with a logged warning
   * so history recording can't break the render (history is observation, not state).
   */
  override fun write(entry: HistoryEntry, png: ByteArray): WriteResult {
    if (syncMode != SyncMode.WRITE_LOCAL) {
      error("GitRefHistorySource(ref=$ref) is $syncMode; writes go to a writable source.")
    }
    return try {
      writeLocal(entry, png)
    } catch (t: Throwable) {
      System.err.println(
        "compose-ai-daemon: GitRefHistorySource.write($ref, ${entry.id}) failed " +
          "(${t.javaClass.simpleName}: ${t.message}); skipping the reporting-branch commit"
      )
      WriteResult.SKIPPED_DUPLICATE
    }
  }

  private fun writeLocal(entry: HistoryEntry, png: ByteArray): WriteResult {
    Files.createDirectories(cacheDir)
    // A non-warning probe (writers create the ref on first render; refHeadCommit() warns, which is
    // a read-side concern). Null = the ref doesn't exist yet → first commit creates it.
    val parent = runGit("rev-parse", "--verify", "--quiet", ref)?.takeIf { it.isNotEmpty() }

    val dir = PreviewIdSanitiser.sanitise(entry.previewId)
    // entry.json mirrors the sidecar but points pngPath at the branch's stable filename.
    val branchEntry = entry.copy(pngPath = RENDER_FILENAME)

    // Skip-if-no-diff (free dedup): if the preview's current branch entry has byte-identical render
    // content — same pixels AND the same data-product snapshots — this render adds nothing, so make
    // no commit. We compare render *content*, not the entry id/timestamp (which churn every render)
    // or the manifest's generatedAt — otherwise an unchanged re-render would still commit.
    if (parent != null) {
      val current =
        catFile(parent, "$dir/entry.json")?.let {
          runCatching { JSON.decodeFromString(HistoryEntry.serializer(), it) }.getOrNull()
        }
      if (current != null && renderContentUnchanged(current, branchEntry)) {
        return WriteResult.SKIPPED_DUPLICATE
      }
    }

    val indexPath = cacheDir.resolve("write-index-${UUID.randomUUID()}.tmp")
    val baseEnv = mapOf("GIT_INDEX_FILE" to indexPath.toAbsolutePath().toString())
    try {
      // Seed the throwaway index from the parent tree (empty index when the ref is new).
      if (parent != null && !plumbingOk(baseEnv, "read-tree", parent))
        return WriteResult.SKIPPED_DUPLICATE

      // Overwrite the preview's directory wholesale: drop every prior path under it so a render
      // that no longer produces a given data product doesn't leave a stale file behind.
      if (parent != null) {
        val existing = runGit("ls-tree", "-r", "--name-only", parent, "--", "$dir/")
        if (existing != null) {
          for (p in existing.split('\n').map { it.trim() }.filter { it.isNotEmpty() }) {
            plumbingOk(baseEnv, "update-index", "--force-remove", p)
          }
        }
      }

      for ((path, bytes) in projectFiles(dir, branchEntry, png)) {
        val blob = hashObject(baseEnv, bytes) ?: return WriteResult.SKIPPED_DUPLICATE
        if (!plumbingOk(baseEnv, "update-index", "--add", "--cacheinfo", "100644,$blob,$path")) {
          return WriteResult.SKIPPED_DUPLICATE
        }
      }

      // Refresh the manifest (read prior, upsert this preview).
      val manifestBytes = buildManifest(parent, branchEntry, dir)
      val manifestBlob = hashObject(baseEnv, manifestBytes) ?: return WriteResult.SKIPPED_DUPLICATE
      if (
        !plumbingOk(
          baseEnv,
          "update-index",
          "--add",
          "--cacheinfo",
          "100644,$manifestBlob,$MANIFEST_FILENAME",
        )
      ) {
        return WriteResult.SKIPPED_DUPLICATE
      }

      val tree = plumbing(baseEnv, "write-tree")?.trim() ?: return WriteResult.SKIPPED_DUPLICATE

      val message =
        "compose-preview history: ${entry.previewId}\n\n" +
          "render: ${entry.id}\n" +
          "produced-from: ${entry.git?.commit ?: "unknown"}\n"
      val commitArgs = buildList {
        add("commit-tree")
        add(tree)
        if (parent != null) {
          add("-p")
          add(parent)
        }
        add("-m")
        add(message)
      }
      val commit = plumbing(commitEnv(baseEnv), *commitArgs.toTypedArray())?.trim()
      if (commit.isNullOrEmpty()) return WriteResult.SKIPPED_DUPLICATE

      val updated =
        if (parent != null) plumbingOk(baseEnv, "update-ref", ref, commit, parent)
        else plumbingOk(baseEnv, "update-ref", ref, commit)
      if (!updated) return WriteResult.SKIPPED_DUPLICATE

      cachedEntries.set(null) // invalidate the read cache so the new commit is visible.
      return WriteResult.WRITTEN
    } finally {
      try {
        Files.deleteIfExists(indexPath)
      } catch (_: Throwable) {
        // best-effort cleanup
      }
    }
  }

  /** The set of `(path, bytes)` this render contributes under the preview's directory. */
  private fun projectFiles(
    dir: String,
    branchEntry: HistoryEntry,
    png: ByteArray,
  ): List<Pair<String, ByteArray>> = buildList {
    add("$dir/$RENDER_FILENAME" to png)
    add("$dir/entry.json" to JSON.encodeToString(HistoryEntry.serializer(), branchEntry).toBytes())
    branchEntry.semantics?.let { add("$dir/semantics.json" to encodeJson(it)) }
    branchEntry.a11yHierarchy?.let { add("$dir/a11y.json" to encodeJson(it)) }
    branchEntry.a11yAtf?.let { add("$dir/a11y-atf.json" to encodeJson(it)) }
    branchEntry.a11yTouchTargets?.let { add("$dir/a11y-touch-targets.json" to encodeJson(it)) }
    branchEntry.theme?.let { add("$dir/theme.json" to encodeJson(it)) }
  }

  /**
   * True when [candidate] adds no new render content over the branch's [current] entry. Uses the
   * **same** criteria as `LocalFsHistorySource`'s tier-1 dedup — same pixels and a structurally
   * unchanged `compose/semantics` tree — deliberately and explicitly NOT the a11y/theme snapshots.
   *
   * Matching LocalFs is the point: if the two writable sources disagreed on what counts as a
   * duplicate (e.g. GitRef wrote a same-pixel render because a11y changed while LocalFs skipped
   * it), `HistoryManager.list` would de-dup the pair by `(previewId, pngHash)`, keep the older
   * LocalFs entry, and the git-only entry would vanish from the default listing. Keeping the
   * predicates aligned means both sources make the same skip decision, so no entry is silently
   * hidden. Comparison ignores id / timestamp / provenance churn so an unchanged re-render makes no
   * commit.
   */
  private fun renderContentUnchanged(current: HistoryEntry, candidate: HistoryEntry): Boolean =
    current.pngHash == candidate.pngHash &&
      !semanticsChanged(current.semantics, candidate.semantics)

  /** Mirrors `LocalFsHistorySource.semanticsChanged`: structural (SemanticsDiff), null-tolerant. */
  private fun semanticsChanged(old: JsonElement?, new: JsonElement?): Boolean {
    if (old == null || new == null) return false
    return try {
      val base = JSON.decodeFromJsonElement(ComposeSemanticsPayload.serializer(), old)
      val head = JSON.decodeFromJsonElement(ComposeSemanticsPayload.serializer(), new)
      !SemanticsDiff.diff(base, head).isEmpty
    } catch (_: Throwable) {
      false
    }
  }

  /**
   * Reads the prior manifest off [parent] (when present), upserts this preview, returns the bytes.
   */
  private fun buildManifest(parent: String?, branchEntry: HistoryEntry, dir: String): ByteArray {
    val prior: List<ReportingBranchPreview> =
      parent
        ?.let { catFile(it, MANIFEST_FILENAME) }
        ?.let {
          try {
            MANIFEST_JSON.decodeFromString(ReportingBranchManifest.serializer(), it).previews
          } catch (_: Throwable) {
            emptyList()
          }
        } ?: emptyList()

    val dataProducts = buildList {
      branchEntry.semantics?.let { add("compose/semantics") }
      branchEntry.a11yHierarchy?.let { add("a11y/hierarchy") }
      branchEntry.a11yAtf?.let { add("a11y/atf") }
      branchEntry.a11yTouchTargets?.let { add("a11y/touchTargets") }
      branchEntry.theme?.let { add("compose/theme") }
    }
    val mine =
      ReportingBranchPreview(
        previewId = branchEntry.previewId,
        module = branchEntry.module,
        dir = dir,
        pngHash = branchEntry.pngHash,
        dataProducts = dataProducts,
      )
    val previews =
      (prior.filterNot { it.previewId == branchEntry.previewId } + mine).sortedBy { it.previewId }
    val manifest =
      ReportingBranchManifest(
        formatVersion = REPORTING_BRANCH_FORMAT_VERSION,
        generatedAt = Instant.now().toString(),
        commit = branchEntry.git?.commit,
        sourceBranch = ref.removePrefix("refs/heads/preview/").removePrefix("refs/heads/"),
        previews = previews,
      )
    return MANIFEST_JSON.encodeToString(ReportingBranchManifest.serializer(), manifest).toBytes()
  }

  override fun list(filter: HistoryFilter): HistoryListPage {
    val refCommit = refHeadCommit() ?: return emptyPage()
    val entries = listEntries(refCommit).map { rewriteSource(it, refCommit) }
    val matched = entries.filter { HistoryFilters.matches(it, filter) }
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
    val match = listEntries(refCommit).firstOrNull { it.id == entryId } ?: return null
    val full = rewriteSource(match, refCommit)
    val dir = PreviewIdSanitiser.sanitise(match.previewId)
    // New layout stores the PNG at <dir>/render.png; the legacy format at <dir>/<entryId>.png. The
    // entry's own pngPath carries whichever was written, so resolve relative to it.
    val pngRel = match.pngPath.takeIf { it.isNotBlank() } ?: RENDER_FILENAME
    val pngFile = extractBlobToCache(refCommit, "$dir/$pngRel") ?: return null
    val bytes = if (includeBytes) Files.readAllBytes(pngFile) else null
    return HistoryReadResult(
      entry = full,
      previewMetadata = full.previewMetadata,
      pngPath = pngFile.toAbsolutePath().toString(),
      pngBytes = bytes,
    )
  }

  // -------------------------------------------------------------------------
  // Internals
  // -------------------------------------------------------------------------

  /** Cache of `(refCommit, entries)`; invalidated when the ref HEAD shifts or a write lands. */
  private val cachedEntries: AtomicReference<CachedEntries?> = AtomicReference(null)
  private val refMissingWarned = AtomicBoolean(false)

  private data class CachedEntries(val refCommit: String, val entries: List<HistoryEntry>)

  init {
    try {
      Files.createDirectories(cacheDir)
    } catch (_: Throwable) {
      // ignore — recreated on demand
    }
  }

  private fun rewriteSource(entry: HistoryEntry, refCommit: String): HistoryEntry =
    entry.copy(source = HistorySourceInfo(kind = "git", id = "$id@${refCommit.take(7)}"))

  /** Returns the ref's HEAD commit sha or null if the ref is missing. Emits one warn on miss. */
  private fun refHeadCommit(): String? {
    val out = runGit("rev-parse", "--verify", ref)
    if (out == null) {
      if (refMissingWarned.compareAndSet(false, true)) {
        val branch = ref.removePrefix("refs/heads/")
        warnEmitter(
          "GitRefHistorySource: ref '$ref' is not present locally.\n" +
            "  Hint: populate it by fetching from a remote (e.g. `git fetch origin $ref:$ref`)\n" +
            "  or enable WRITE_LOCAL so the daemon records render history on $branch.\n" +
            "  Until then, main-history comparison will not be available."
        )
      }
      return null
    }
    return out.takeIf { it.isNotEmpty() }
  }

  /** Reads the current branch state: every `<dir>/entry.json`, newest-first by timestamp. */
  private fun listEntries(refCommit: String): List<HistoryEntry> {
    cachedEntries.get()?.let { if (it.refCommit == refCommit) return it.entries }
    val tree = runGit("ls-tree", "-r", "--name-only", refCommit)
    if (tree == null) {
      cachedEntries.set(CachedEntries(refCommit, emptyList()))
      return emptyList()
    }
    val entries =
      tree
        .split('\n')
        .map { it.trim() }
        .filter { it.endsWith("/entry.json") }
        .mapNotNull { path ->
          catFile(refCommit, path)?.let { text ->
            try {
              JSON.decodeFromString(HistoryEntry.serializer(), text)
            } catch (t: Throwable) {
              System.err.println(
                "compose-ai-daemon: GitRefHistorySource.list($ref): malformed $path " +
                  "(${t.javaClass.simpleName}: ${t.message})"
              )
              null
            }
          }
        }
        .sortedByDescending { it.timestamp }
    // Backward compatibility: a ref written under the legacy read-only format (an aggregate
    // `_index.jsonl` + `<entryId>.{png,json}` per preview dir, the layout the old read-only
    // GitRefHistorySource documented) has no `entry.json` files, so the scan above is empty.
    // Fall back to parsing `_index.jsonl` so existing preview/* refs keep reading.
    val resolved = entries.ifEmpty { readLegacyIndex(refCommit) }
    cachedEntries.set(CachedEntries(refCommit, resolved))
    return resolved
  }

  /**
   * Legacy reader: parse the aggregate `_index.jsonl`, tolerating truncated lines; newest-first.
   */
  private fun readLegacyIndex(refCommit: String): List<HistoryEntry> {
    val text = catFile(refCommit, LEGACY_INDEX_FILENAME) ?: return emptyList()
    val parsed =
      text.split('\n').mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@mapNotNull null
        try {
          JSON.decodeFromString(HistoryEntry.serializer(), trimmed)
        } catch (_: Throwable) {
          null // truncated / malformed line — skip
        }
      }
    // _index.jsonl is append-order (oldest first); reverse for newest-first listing.
    return parsed.asReversed()
  }

  private fun catFile(refCommit: String, path: String): String? = runGit("show", "$refCommit:$path")

  private fun extractBlobToCache(refCommit: String, path: String): Path? {
    val safeName = "${refCommit.take(7)}-${path.replace('/', '_')}"
    val target = cacheDir.resolve(safeName)
    if (Files.exists(target)) return target
    try {
      Files.createDirectories(cacheDir)
    } catch (t: Throwable) {
      return null
    }
    val tmp =
      try {
        Files.createTempFile(cacheDir, "extract-", ".tmp")
      } catch (t: Throwable) {
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
      try {
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      } catch (_: Throwable) {
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
      }
      return target
    } catch (t: IOException) {
      Files.deleteIfExists(tmp)
      return null
    } catch (t: Throwable) {
      Files.deleteIfExists(tmp)
      throw t
    }
  }

  /** Read-side git: stdout text trimmed of a trailing newline, or null on non-zero/timeout. */
  private fun runGit(vararg args: String): String? = plumbing(emptyMap(), *args)

  /**
   * Runs git with optional [env] (e.g. `GIT_INDEX_FILE`) and optional [stdin]; returns stdout or
   * null on failure. Used for the write plumbing as well as reads.
   */
  private fun plumbing(
    env: Map<String, String>,
    vararg args: String,
    stdin: ByteArray? = null,
    timeoutSec: Long = 30,
  ): String? {
    return try {
      val pb =
        ProcessBuilder(listOf(gitExecutable) + args.toList())
          .directory(repoRoot.toFile())
          .redirectErrorStream(false)
      if (env.isNotEmpty()) pb.environment().putAll(env)
      val process = pb.start()
      if (stdin != null) process.outputStream.use { it.write(stdin) }
      else process.outputStream.close()
      val out = process.inputStream.readBytes()
      val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
      if (!finished) {
        process.destroyForcibly()
        return null
      }
      if (process.exitValue() != 0) return null
      String(out, StandardCharsets.UTF_8).trimEnd('\n')
    } catch (_: Throwable) {
      null
    }
  }

  private fun plumbingOk(env: Map<String, String>, vararg args: String): Boolean =
    plumbing(env, *args) != null

  private fun hashObject(env: Map<String, String>, bytes: ByteArray): String? =
    plumbing(env, "hash-object", "-w", "--stdin", stdin = bytes)?.trim()?.takeIf { it.isNotEmpty() }

  private fun commitEnv(base: Map<String, String>): Map<String, String> =
    base +
      mapOf(
        "GIT_AUTHOR_NAME" to GIT_IDENTITY_NAME,
        "GIT_AUTHOR_EMAIL" to GIT_IDENTITY_EMAIL,
        "GIT_COMMITTER_NAME" to GIT_IDENTITY_NAME,
        "GIT_COMMITTER_EMAIL" to GIT_IDENTITY_EMAIL,
      )

  private fun encodeJson(element: JsonElement): ByteArray =
    JSON.encodeToString(JsonElement.serializer(), element).toBytes()

  private fun String.toBytes(): ByteArray = toByteArray(StandardCharsets.UTF_8)

  private fun emptyPage(): HistoryListPage =
    HistoryListPage(entries = emptyList(), nextCursor = null, totalCount = 0)

  /** Read-only vs write-local for a reporting ref. `WRITE_PUSH` is a follow-up (issue #1870). */
  enum class SyncMode {
    READ_ONLY,
    WRITE_LOCAL,
  }

  companion object {
    /** Stable filename for the overwritten-per-render PNG on the ref. */
    const val RENDER_FILENAME: String = "render.png"

    /** Current-state pointer at the ref root. docs/daemon/REPORTING-BRANCH.md § manifest.json. */
    const val MANIFEST_FILENAME: String = "manifest.json"

    /** Aggregate index of the legacy read-only format; read as a fallback for old refs. */
    const val LEGACY_INDEX_FILENAME: String = "_index.jsonl"

    /** Bumped on incompatible reporting-branch layout changes. */
    const val REPORTING_BRANCH_FORMAT_VERSION: Int = 1

    /**
     * Comma/semicolon-separated list of refs (e.g.
     * `refs/heads/preview/main,refs/heads/preview/agent/foo`).
     */
    const val GIT_REF_HISTORY_PROP: String = "composeai.daemon.gitRefHistory"

    /** Sync mode for the reporting refs: `READ_ONLY` (default) or `WRITE_LOCAL`. */
    const val SYNC_MODE_PROP: String = "composeai.daemon.gitRefHistorySyncMode"

    // Synthetic identity for daemon-authored reporting-branch commits. These are machine-generated
    // history commits in the *consumer's* repo (not this project's git history), so they carry a
    // clear non-human author rather than borrowing the developer's identity.
    private const val GIT_IDENTITY_NAME: String = "compose-preview history"
    private const val GIT_IDENTITY_EMAIL: String = "compose-preview-history@users.noreply.localhost"

    private val JSON: Json = Json {
      ignoreUnknownKeys = true
      encodeDefaults = false
    }

    /** Manifest is human-/git-diff-friendly: pretty-printed, defaults encoded (formatVersion). */
    private val MANIFEST_JSON: Json = Json {
      ignoreUnknownKeys = true
      encodeDefaults = true
      prettyPrint = true
    }

    fun parseRefsSysprop(
      propValue: String? = System.getProperty(GIT_REF_HISTORY_PROP)
    ): List<String> =
      propValue?.split(',', ';')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    /** Parses [SYNC_MODE_PROP]; unknown / unset → [SyncMode.READ_ONLY]. */
    fun parseSyncModeSysprop(propValue: String? = System.getProperty(SYNC_MODE_PROP)): SyncMode =
      when (propValue?.trim()?.uppercase()) {
        "WRITE_LOCAL" -> SyncMode.WRITE_LOCAL
        else -> SyncMode.READ_ONLY
      }

    fun defaultCacheDir(historyDir: Path): Path = historyDir.resolve(".git-ref-cache")
  }
}

/** Current-state manifest written at the reporting ref root. docs/daemon/REPORTING-BRANCH.md. */
@Serializable
data class ReportingBranchManifest(
  val formatVersion: Int = 1,
  val generatedAt: String,
  val commit: String? = null,
  val sourceBranch: String? = null,
  val previews: List<ReportingBranchPreview> = emptyList(),
)

@Serializable
data class ReportingBranchPreview(
  val previewId: String,
  val module: String,
  val dir: String,
  val pngHash: String,
  val dataProducts: List<String> = emptyList(),
)
