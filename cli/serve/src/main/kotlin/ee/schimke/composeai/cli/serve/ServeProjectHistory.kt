package ee.schimke.composeai.cli.serve

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The render-history timeline in **project mode** — computed from the local repository instead of
 * read from a published `history.json`.
 *
 * The hosted viewer has no repo, so CI precomputes [PreviewHistoryManifest] and the viewer fetches
 * it from `raw.githubusercontent.com`. `serve` against a local checkout is the mirror image: there
 * is no delivery branch to fetch from, but the delivery branch's *commits* are usually right there
 * in the clone (`compose-preview/main`, or its remote-tracking twin). So the same timeline is
 * derived on demand — [PreviewHistory.read] over the local objects, joined to preview ids through
 * the `baselines.json` that ships on that same ref — and inlined into the viewer page, which
 * `<cp-history-menu>` already prefers over the fetch.
 *
 * ### What a chip links to
 *
 * Hosted mode addresses an old render as `raw.githubusercontent.com/<repo>/<commit>/<path>`, which
 * a local checkout has no equivalent of. Rather than leave the entries non-navigable, this serves
 * the bytes itself, addressed by **blob sha** ([renderBytes], behind `/history/render/<sha>.png`):
 *
 * - the sha is already in the manifest — [PreviewHistoryManifest.ManifestVersion.blob] — so no
 *   commit+path resolution is needed at request time, and nothing about the URL can be steered by a
 *   path;
 * - it is content-addressed, so two commits carrying the same render share one URL and one cache
 *   entry;
 * - and it is trivially constrained: a sha not in [blobs] is refused, so the endpoint can only ever
 *   hand out renders this timeline already names — never an arbitrary object from the repository's
 *   store (a source file, a secret in an old commit).
 *
 * ### Cost
 *
 * One `git log --raw` over the delivery branch is ~1.6s at 770 commits, which is far too slow to
 * repeat per page view, so the whole manifest is computed once and memoised for [refreshMillis].
 * Re-reading rather than caching forever matters for the case this feature exists to serve: someone
 * fetches the delivery branch mid-session and expects the strip to pick the new publishes up.
 *
 * Everything that touches git is injected ([git], [readBlobBytes], [now]) so the resolution,
 * memoisation and gating rules are unit-testable without a repository.
 */
class ServeProjectHistory(
  private val repoRoot: File,
  /**
   * The delivery branch to read. Tried as given and then as `origin/<branch>`: a clone that
   * publishes baselines from CI normally has only the remote-tracking ref, never a local branch.
   */
  private val branch: String = DEFAULT_BRANCH,
  private val refreshMillis: Long = DEFAULT_REFRESH_MILLIS,
  private val git: GitRunner = GitWorktrees.RealGitRunner,
  private val readBlobBytes: (File, String) -> ByteArray? = ::gitCatFileBlob,
  private val now: () -> Long = System::currentTimeMillis,
) {

  private val lock = ReentrantLock()
  private var cached: Snapshot? = null
  private var cachedAt = 0L

  /**
   * A computed timeline for the whole branch. [manifest] is null when there is nothing to show (no
   * such ref in this clone, no `baselines.json` on it, no render history) — cached like any other
   * answer, so a repo without a delivery branch shells out once per [refreshMillis] rather than
   * once per page view.
   */
  private class Snapshot(
    val manifest: PreviewHistoryManifest.Manifest?,
    /** Every render blob the manifest names — the allowlist [renderBytes] gates on. */
    val blobs: Set<String>,
  )

  /**
   * A one-preview manifest payload for [previewId], ready to inline into its viewer page, or null
   * when this preview has no timeline worth drawing.
   *
   * Scoped to the single preview rather than shipping the whole branch's manifest (531 previews on
   * `compose-preview/main`) into every page. Versions below two are dropped here for the same
   * reason `<cp-history-menu>` refuses to draw them: one version is not a timeline, so carrying the
   * payload would cost bytes for a strip that never appears.
   */
  fun timelineJsonFor(previewId: String): String? {
    val manifest = snapshot().manifest ?: return null
    val timeline = manifest.previews[previewId] ?: return null
    if (timeline.versions.size < 2) return null
    return PreviewHistoryManifest.encode(manifest.copy(previews = mapOf(previewId to timeline)))
  }

  /**
   * The render bytes for [blobSha], or null when it isn't a blob this timeline names.
   *
   * Three gates, in order: the sha must be well-formed hex, it must appear in the current
   * snapshot's [Snapshot.blobs], and the object must actually look like a PNG. The middle one is
   * the load-bearing one — without it this would be a "read any object in the repository by sha"
   * endpoint, which is not what a render timeline needs to be.
   */
  fun renderBytes(blobSha: String): ByteArray? {
    val sha = blobSha.trim()
    if (!sha.matches(BLOB_SHA)) return null
    if (sha !in snapshot().blobs) return null
    val bytes = readBlobBytes(repoRoot, sha) ?: return null
    return bytes.takeIf { it.isPng() }
  }

  /** The memoised branch snapshot, recomputed once [refreshMillis] has elapsed. */
  private fun snapshot(): Snapshot = lock.withLock {
    val current = cached
    if (current != null && now() - cachedAt < refreshMillis) return current
    val fresh = compute()
    cached = fresh
    cachedAt = now()
    fresh
  }

  private fun compute(): Snapshot {
    val sha = resolveRef() ?: return EMPTY
    // Read baselines from the same commit the history is walked from, so the path→id join can never
    // be against a different snapshot than the timelines it keys.
    val baselines = show(sha, PreviewHistoryManifest.BASELINES_FILE_NAME) ?: return EMPTY
    val pathToPreviewId = PreviewHistoryManifest.renderPathsToPreviewIds(baselines)
    if (pathToPreviewId.isEmpty()) return EMPTY
    val timelines = runCatching { PreviewHistory.read(repoRoot, sha, RENDERS_DIR, git) }.getOrNull()
    if (timelines.isNullOrEmpty()) return EMPTY
    val manifest = PreviewHistoryManifest.build(timelines, pathToPreviewId, generatedFrom = sha)
    if (manifest.previews.isEmpty()) return EMPTY
    val blobs = manifest.previews.values.flatMapTo(HashSet()) { it.versions.map { v -> v.blob } }
    return Snapshot(manifest, blobs)
  }

  /**
   * Resolve the configured branch to a commit, falling back to its remote-tracking form. Null when
   * neither exists, which is the ordinary case for a checkout that never fetched the branch — and
   * the signal to leave the timeline out entirely rather than draw an empty one.
   */
  private fun resolveRef(): String? {
    val candidates = buildList {
      add(branch)
      if (!branch.startsWith("refs/") && !branch.startsWith("origin/")) add("origin/$branch")
    }
    for (candidate in candidates) {
      val res = git.run(repoRoot, listOf("rev-parse", "--verify", "--quiet", "$candidate^{commit}"))
      val sha = res.stdout.trim()
      if (res.ok && sha.isNotEmpty()) return sha
    }
    return null
  }

  /** `git show <sha>:<path>`, or null when the ref carries no such file. */
  private fun show(sha: String, path: String): String? {
    val res = git.run(repoRoot, listOf("show", "$sha:$path"))
    return res.stdout.takeIf { res.ok && it.isNotBlank() }
  }

  companion object {
    /** Where `compose-preview`'s own baseline publisher writes. */
    const val DEFAULT_BRANCH: String = "compose-preview/main"

    /** The delivery branch's render tree — the pathspec the history walk is scoped to. */
    const val RENDERS_DIR: String = "renders"

    /**
     * How long a computed branch snapshot is reused. A minute is long enough that a burst of viewer
     * loads costs one `git log`, and short enough that fetching the delivery branch shows up in the
     * strip without restarting the server.
     */
    const val DEFAULT_REFRESH_MILLIS: Long = 60_000

    private val BLOB_SHA = Regex("[0-9a-f]{40}")

    private val EMPTY = Snapshot(manifest = null, blobs = emptySet())

    private val PNG_MAGIC =
      byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

    private fun ByteArray.isPng(): Boolean =
      size > PNG_MAGIC.size && PNG_MAGIC.indices.all { this[it] == PNG_MAGIC[it] }
  }
}

/**
 * Read one git object's raw bytes. Separate from [GitRunner] on purpose: that decodes stdout as
 * text, which would corrupt a PNG beyond recognition.
 *
 * Reads one byte past the cap so an over-sized object is *detected* rather than silently truncated
 * into a corrupt render.
 */
private fun gitCatFileBlob(repoRoot: File, sha: String): ByteArray? {
  return runCatching {
    val process =
      ProcessBuilder("git", "cat-file", "blob", sha)
        .directory(repoRoot)
        .redirectErrorStream(false)
        .start()
    val bytes = process.inputStream.use { it.readNBytes(MAX_CAT_FILE_BYTES + 1) }
    // Drained so a failing `cat-file` can't block on a full stderr pipe and hit the timeout.
    process.errorStream.use { it.readBytes() }
    if (!process.waitFor(CAT_FILE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      null
    } else if (process.exitValue() != 0 || bytes.isEmpty() || bytes.size > MAX_CAT_FILE_BYTES) {
      null
    } else {
      bytes
    }
  }
    .getOrNull()
}

private const val MAX_CAT_FILE_BYTES = 32 * 1024 * 1024
private const val CAT_FILE_TIMEOUT_SECONDS = 30L
