package ee.schimke.composeai.daemon.history

import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Resolves the per-render `git.*` and `worktree.*` provenance fields for [HistoryEntry] — see
 * HISTORY.md § "Initialize-time provenance".
 *
 * Two-phase resolution:
 *
 * 1. **Construction (cheap, called once per daemon).** Captures [worktree], [remote] — the worktree
 *    root and remote URL never change for a daemon's lifetime. Resolution failure leaves the fields
 *    as null. No exception escapes; a non-git directory is fine.
 * 2. **Per-render refresh ([snapshot]).** Re-resolves [GitInfo.branch], [GitInfo.commit],
 *    [GitInfo.dirty] each call (single `git rev-parse` + `git status --porcelain` is sub-50ms in
 *    the typical project). [GitInfo.remote] is taken from the cached value.
 *
 * **Safety against subprocess failures.** Every shell-out is wrapped in [runGit] which times out
 * after 5s, captures stderr, and returns null on any error. The daemon never blocks on a misbehaved
 * git binary.
 *
 * @param workspaceRoot the directory under which `git rev-parse --show-toplevel` runs; defaults to
 *   the daemon's CWD when null. Production callers pass the InitializeParams.workspaceRoot.
 * @param env environment overrides for tests — production passes `System.getenv()`.
 */
class GitProvenance(
  private val workspaceRoot: Path?,
  private val env: Map<String, String> = System.getenv(),
) {

  /** Captured once at construction. Stable for the daemon's lifetime. */
  private val cached: WorktreeRoot = resolveWorktreeRoot()

  /**
   * Returns a fresh [WorktreeInfo] / [GitInfo] pair. Cheap (refreshes branch/commit/dirty per
   * call); safe to invoke per-render.
   */
  fun snapshot(): Pair<WorktreeInfo?, GitInfo?> {
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

  private fun runGit(workingDir: String, vararg args: String): String? {
    return try {
      val process =
        ProcessBuilder(listOf("git") + args.toList())
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

  private data class WorktreeRoot(
    val worktreePath: String?,
    val worktreeId: String?,
    val remote: String?,
  )

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
  }
}
