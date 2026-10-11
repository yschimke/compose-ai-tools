package ee.schimke.composeai.cli.serve

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Runs a `git` subcommand in [workdir]. Injected so [GitWorktrees] is testable without a real repo.
 */
public fun interface GitRunner {
  public fun run(workdir: File, args: List<String>): GitResult
}

public data class GitResult(val exitCode: Int, val stdout: String) {
  val ok: Boolean
    get() = exitCode == 0
}

/**
 * Manages git worktrees for serving multiple revisions of one repo: each resolved commit gets one
 * detached worktree at `<cacheRoot>/<sha>`, reused afterwards. Resolution and add are serialised.
 * Worktrees share the object store and outlive the hosts built from them; cleaned up on [close] /
 * `git worktree prune`.
 */
public class GitWorktrees(
  private val repoRoot: File,
  private val cacheRoot: File,
  /**
   * Refs a requested revision must be an ancestor of to be served; empty allows nothing (fails
   * closed), so arbitrary fetched PR/fork commits are never checked out. Short names are qualified
   * to `refs/heads/…` / `refs/remotes/…` ([qualify]) so a same-named tag can't satisfy the
   * allowlist; tags must be given as `refs/tags/<name>`.
   */
  private val allowedRefs: List<String> = emptyList(),
  private val git: GitRunner = RealGitRunner,
  private val onLog: (String) -> Unit = {},
) : AutoCloseable {

  private val lock = ReentrantLock()

  // Reference count per worktree: different revisions can resolve to the same `<cacheRoot>/<sha>`,
  // so it is only removed when the last holder releases it.
  private val prepared = HashMap<File, Int>()

  /**
   * Resolve [rev] and ensure a worktree exists, returning its directory, or null when unresolvable,
   * disallowed, or uncreatable. Registers a reference; balance with [remove] (or [close]).
   */
  public fun prepare(rev: String): File? = lock.withLock {
    val sha = resolve(rev) ?: return null
    if (!isAllowed(sha)) {
      onLog("serve: revision '$rev' ($sha) is not reachable from an allowed ref; refusing")
      return null
    }
    val dir = File(cacheRoot, sha)
    // Already a valid checkout (same commit via another revision, or a previous run); reuse it.
    if (File(dir, ".git").exists()) {
      prepared.merge(dir, 1, Int::plus)
      return dir
    }
    cacheRoot.mkdirs()
    val add =
      git.run(repoRoot, listOf("worktree", "add", "--detach", "--force", dir.absolutePath, sha))
    if (!add.ok) {
      onLog("serve: 'git worktree add' failed for $sha")
      return null
    }
    prepared.merge(dir, 1, Int::plus)
    dir
  }

  /** True when [sha] is reachable from (an ancestor of, or equal to) at least one allowed ref. */
  private fun isAllowed(sha: String): Boolean = allowedRefs.any { ref ->
    val qualified = qualify(ref) ?: return@any false
    git.run(repoRoot, listOf("merge-base", "--is-ancestor", sha, "$qualified^{commit}")).ok
  }

  /**
   * Qualify an allowlist [ref] unambiguously, or null if it doesn't exist. `refs/…` is verified
   * as-is; a short name is tried as a branch, then a remote-tracking branch — never a tag.
   */
  private fun qualify(ref: String): String? {
    val candidates =
      if (ref.startsWith("refs/")) listOf(ref) else listOf("refs/heads/$ref", "refs/remotes/$ref")
    return candidates.firstOrNull { exists(it) }
  }

  /** True when [fullRef] resolves to a commit. */
  private fun exists(fullRef: String): Boolean =
    git.run(repoRoot, listOf("rev-parse", "--verify", "--quiet", "$fullRef^{commit}")).ok

  /** Resolve [rev] to a full commit sha, or null when it isn't a valid revision. */
  private fun resolve(rev: String): String? {
    val res = git.run(repoRoot, listOf("rev-parse", "--verify", "--quiet", "$rev^{commit}"))
    val sha = res.stdout.trim()
    return if (res.ok && sha.isNotEmpty()) sha else null
  }

  /**
   * Release one reference on a worktree this instance prepared; `git worktree remove` only at zero,
   * so another live session on the same commit keeps its checkout. No-op for directories not
   * prepared here. Best-effort; `git worktree prune` on [close] cleans up.
   */
  public fun remove(dir: File): Unit = lock.withLock {
    val refs = prepared[dir] ?: return@withLock
    if (refs > 1) {
      prepared[dir] = refs - 1
      return@withLock
    }
    prepared.remove(dir)
    onLog("serve: reclaiming worktree ${dir.name}")
    runCatching { git.run(repoRoot, listOf("worktree", "remove", "--force", dir.absolutePath)) }
  }

  /** Remove the worktrees this instance created and prune stale registrations. Best-effort. */
  override fun close() {
    val dirs = lock.withLock { prepared.keys.toList().also { prepared.clear() } }
    dirs.forEach { dir ->
      runCatching { git.run(repoRoot, listOf("worktree", "remove", "--force", dir.absolutePath)) }
    }
    runCatching { git.run(repoRoot, listOf("worktree", "prune")) }
  }

  /** Default [GitRunner] backed by the `git` CLI. */
  public object RealGitRunner : GitRunner {
    override fun run(workdir: File, args: List<String>): GitResult {
      return try {
        val process =
          ProcessBuilder(listOf("git") + args).directory(workdir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
          process.destroyForcibly()
          GitResult(exitCode = -1, stdout = output)
        } else {
          GitResult(exitCode = process.exitValue(), stdout = output)
        }
      } catch (e: Exception) {
        GitResult(exitCode = -1, stdout = e.message ?: "")
      }
    }

    private const val GIT_TIMEOUT_SECONDS = 120L
  }
}
