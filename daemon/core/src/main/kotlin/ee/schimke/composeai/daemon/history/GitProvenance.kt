package ee.schimke.composeai.daemon.history

import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Resolves the per-render `git.*` and `worktree.*` provenance fields for [HistoryEntry] — see
 * HISTORY.md § "Initialize-time provenance".
 *
 * Two-phase resolution:
 *
 * 1. **Construction (cheap, called once per daemon).** Captures the worktree root + remote URL —
 *    they never change for a daemon's lifetime. Resolution failure leaves the fields null; a
 *    non-git directory is fine.
 * 2. **Per-render refresh ([snapshot]).** Re-resolves branch / commit / dirty. A fresh resolution
 *    is three `git` subprocess spawns (`symbolic-ref`, `rev-parse HEAD`, `status --porcelain`), and
 *    `git status` scales with working-tree size — tens of ms on a large repo. Since history records
 *    on every render, a discovery pass that re-renders many previews would otherwise pay that cost
 *    N times back-to-back. So [snapshot] **caches** the resolved pair for [cacheTtlMs] (default
 *    [DEFAULT_CACHE_TTL_MS]): a render burst collapses to a single git fetch, while an interactive
 *    edit-loop (renders spaced wider than the TTL) still gets fresh provenance each time. branch /
 *    commit only change on checkout/commit; `dirty` may lag by at most the TTL, which is acceptable
 *    for best-effort provenance metadata. Set `-D[CACHE_TTL_PROP]=0` to disable caching (always
 *    fresh) — tests run with it `0` for deterministic, per-call provenance.
 *
 * **Safety against subprocess failures.** Every shell-out times out after 5s and returns null on
 * any error. The daemon never blocks on a misbehaved git binary.
 *
 * @param workspaceRoot the directory under which `git rev-parse --show-toplevel` runs; defaults to
 *   the daemon's CWD when null. Production callers pass the InitializeParams.workspaceRoot.
 * @param env environment overrides for tests — production passes `System.getenv()`.
 * @param cacheTtlMs per-render provenance cache window in ms; `0` disables caching. Defaults to the
 *   `[CACHE_TTL_PROP]` sysprop, else [DEFAULT_CACHE_TTL_MS].
 * @param nowNanos monotonic clock source (injected in tests); defaults to `System.nanoTime`.
 * @param gitRunner runs `git <args>` in a working dir and returns trimmed stdout (or null on
 *   failure); injected in tests to avoid spawning real subprocesses.
 */
class GitProvenance(
  private val workspaceRoot: Path?,
  private val env: Map<String, String> = System.getenv(),
  private val cacheTtlMs: Long = resolveGitProvenanceTtlMs(),
  private val nowNanos: () -> Long = System::nanoTime,
  private val gitRunner: (workingDir: String, args: List<String>) -> String? = ::runGitProcess,
) {

  /** Captured once at construction. Stable for the daemon's lifetime. */
  private val cached: WorktreeRoot = resolveWorktreeRoot()

  /** Most recent ([snapshot]) result + the nanos it was resolved at; null until the first call. */
  private val snapshotCache = AtomicReference<CachedSnapshot?>(null)

  /**
   * Returns a fresh-or-cached [WorktreeInfo] / [GitInfo] pair. Within [cacheTtlMs] of the previous
   * call the cached value is returned without spawning git; otherwise it re-resolves and re-caches.
   */
  fun snapshot(): Pair<WorktreeInfo?, GitInfo?> {
    if (cacheTtlMs > 0L) {
      val hit = snapshotCache.get()
      if (hit != null && nowNanos() - hit.atNanos < cacheTtlMs * NANOS_PER_MS) {
        return hit.value
      }
    }
    val fresh = computeSnapshot()
    if (cacheTtlMs > 0L) {
      snapshotCache.set(CachedSnapshot(nowNanos(), fresh))
    }
    return fresh
  }

  private fun computeSnapshot(): Pair<WorktreeInfo?, GitInfo?> {
    val worktreePath = cached.worktreePath
    val worktreeInfo =
      WorktreeInfo(
        path = worktreePath,
        id = cached.worktreeId,
        agentId = env[ENV_AGENT_ID]?.takeIf { it.isNotEmpty() },
      )
    if (worktreePath == null) {
      // Not a git working tree — emit only the agentId/id label fields.
      val anyPopulated =
        worktreeInfo.path != null || worktreeInfo.id != null || worktreeInfo.agentId != null
      return Pair(if (anyPopulated) worktreeInfo else null, null)
    }
    val branch = runGit(worktreePath, "symbolic-ref", "--short", "HEAD")?.takeIf { it.isNotEmpty() }
    val commit = runGit(worktreePath, "rev-parse", "HEAD")?.takeIf { it.isNotEmpty() }
    val dirty = runGit(worktreePath, "status", "--porcelain")?.let { it.isNotEmpty() }
    val gitInfo =
      GitInfo(
        branch = branch,
        commit = commit,
        shortCommit = commit?.take(7),
        dirty = dirty,
        remote = cached.remote,
      )
    return Pair(worktreeInfo, gitInfo)
  }

  /** Resolves the worktree root + remote once at construction. Returns null fields on failure. */
  private fun resolveWorktreeRoot(): WorktreeRoot {
    val cwd = workspaceRoot?.toAbsolutePath()?.toFile() ?: File(".").absoluteFile
    val worktreePath =
      runGit(cwd.absolutePath, "rev-parse", "--show-toplevel")?.takeIf { it.isNotEmpty() }
    val remote =
      worktreePath?.let { runGit(it, "remote", "get-url", "origin") }?.takeIf { it.isNotEmpty() }
    val worktreeId =
      env[ENV_WORKTREE_ID]?.takeIf { it.isNotEmpty() }
        ?: worktreePath?.let { File(it).name.takeIf { name -> name.isNotEmpty() } }
    return WorktreeRoot(worktreePath = worktreePath, worktreeId = worktreeId, remote = remote)
  }

  private fun runGit(workingDir: String, vararg args: String): String? =
    gitRunner(workingDir, args.toList())

  private data class WorktreeRoot(
    val worktreePath: String?,
    val worktreeId: String?,
    val remote: String?,
  )

  private data class CachedSnapshot(val atNanos: Long, val value: Pair<WorktreeInfo?, GitInfo?>)

  companion object {
    /**
     * Environment variable populated by the harness / agent supervisor — HISTORY.md § "Agent
     * attribution".
     */
    const val ENV_AGENT_ID: String = "COMPOSEAI_AGENT_ID"

    /**
     * Environment variable that overrides the worktree dir basename — HISTORY.md § "Worktree IDs".
     */
    const val ENV_WORKTREE_ID: String = "COMPOSEAI_WORKTREE_ID"

    /** Sysprop overriding the per-render provenance cache TTL (ms). `0` disables caching. */
    const val CACHE_TTL_PROP: String = "composeai.history.gitProvenanceTtlMs"

    /** Default cache window — collapses a render burst's repeated provenance fetches into one. */
    const val DEFAULT_CACHE_TTL_MS: Long = 1000L

    private const val NANOS_PER_MS: Long = 1_000_000L
  }
}

private fun resolveGitProvenanceTtlMs(): Long =
  System.getProperty(GitProvenance.CACHE_TTL_PROP)?.toLongOrNull()?.coerceAtLeast(0L)
    ?: GitProvenance.DEFAULT_CACHE_TTL_MS

private fun runGitProcess(workingDir: String, args: List<String>): String? {
  return try {
    val process =
      ProcessBuilder(listOf("git") + args)
        .directory(File(workingDir))
        .redirectErrorStream(false)
        .start()
    val finished = process.waitFor(5, TimeUnit.SECONDS)
    if (!finished) {
      process.destroyForcibly()
      return null
    }
    if (process.exitValue() != 0) return null
    process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
  } catch (t: Throwable) {
    // Swallow — non-git workspace, missing git binary, exotic file-system permission errors.
    // History still works without git provenance; the daemon log captures the cause.
    null
  }
}
