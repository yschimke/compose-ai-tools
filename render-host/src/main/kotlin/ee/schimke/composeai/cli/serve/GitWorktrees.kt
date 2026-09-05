package ee.schimke.composeai.cli.serve

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Runs a `git` subcommand in [workdir]. Injected so [GitWorktrees] is testable without a real repo.
 */
fun interface GitRunner {
  fun run(workdir: File, args: List<String>): GitResult
}

data class GitResult(val exitCode: Int, val stdout: String) {
  val ok: Boolean
    get() = exitCode == 0
}

/**
 * Manages git **worktrees** for serving multiple revisions of one repo from a single server. Each
 * resolved commit gets one detached worktree under [cacheRoot] (`<cacheRoot>/<sha>`), reused on
 * later requests — so a revision is checked out at most once. Resolution + add are serialised so
 * two concurrent first-requests for the same revision don't race to add the same worktree.
 *
 * Worktrees share the repo's object store, so they're cheap; they outlive the [ServeRenderHost]s
 * built from them (the registry evicts hosts, not checkouts) and are cleaned up on [close] / `git
 * worktree prune`.
 */
class GitWorktrees(
  private val repoRoot: File,
  private val cacheRoot: File,
  /**
   * Refs whose history a requested revision must be reachable from to be served. Empty = nothing is
   * allowed (project mode fails closed): a client-supplied `?session=<rev>` is only checked out
   * when it's an ancestor of one of these operator-trusted refs, so arbitrary fetched PR/fork
   * commits can't be materialized or (downstream) built.
   *
   * A short name like `main` or `origin/main` is **qualified** to `refs/heads/…` / `refs/remotes/…`
   * before the ancestry check (see [qualify]): gitrevisions resolves an ambiguous `<name>` as
   * `refs/tags/<name>` *before* the branch, so a same-named malicious tag could otherwise satisfy
   * the allowlist for commits reachable only from that tag. Tags are never auto-matched — to allow
   * one, pass it fully qualified as `refs/tags/<name>`.
   */
  private val allowedRefs: List<String> = emptyList(),
  private val git: GitRunner = RealGitRunner,
  private val onLog: (String) -> Unit = {},
) : AutoCloseable {

  private val lock = ReentrantLock()

  // Reference count per worktree directory, NOT a plain set: two different revisions (e.g. a branch
  // name and its SHA, or a revision session and a same-ref catalog session) resolve to the SAME
  // `<cacheRoot>/<sha>` directory, so a shared worktree must survive until *every* holder has
  // reclaimed it (issue #2022 review). Each [prepare] increments; each [remove] decrements and only
  // `git worktree remove`s at zero.
  private val prepared = HashMap<File, Int>()

  /**
   * Resolve [rev] to a commit and ensure a worktree for it exists; returns the worktree directory,
   * or `null` when the revision can't be resolved, isn't allowed by policy, or can't be created.
   * Registers a reference on the worktree — balance it with a [remove] (or a terminal [close]).
   */
  fun prepare(rev: String): File? = lock.withLock {
    val sha = resolve(rev) ?: return null
    if (!isAllowed(sha)) {
      onLog("serve: revision '$rev' ($sha) is not reachable from an allowed ref; refusing")
      return null
    }
    val dir = File(cacheRoot, sha)
    // A `.git` file/dir in the worktree means it's already a valid checkout — reuse it (another
    // revision that resolved to the same commit, or a survivor from an earlier run).
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
   * Qualify an allowlist [ref] to an unambiguous fully-qualified ref, or null if it doesn't exist.
   * A `refs/…` ref is verified as-is (so a tag can be allowed explicitly via `refs/tags/<name>`); a
   * short name is tried as a branch then a remote-tracking branch only — never a tag — so a
   * same-named tag can't hijack the allowlist.
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
   * Release one reference on a worktree this instance prepared — the second-level GC of a long-idle
   * revision session (issue #2022). The worktree is only `git worktree remove`d once its **last**
   * reference is released, so GC of one revision alias can't delete a `<cacheRoot>/<sha>` directory
   * another still-live session (a same-commit alias, or a same-ref catalog session) is resuming or
   * rendering from. A no-op for a [dir] this instance didn't prepare, so a stray reclaim can't `git
   * worktree remove` an unrelated path. Best-effort; a later `git worktree prune` (on [close]) mops
   * up any residue. A subsequent [prepare] of the same revision re-adds the worktree from the
   * shared object store.
   */
  fun remove(dir: File) = lock.withLock {
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
  object RealGitRunner : GitRunner {
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
