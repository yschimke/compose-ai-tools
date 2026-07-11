package ee.schimke.composeai.cli.serve

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitWorktreesTest {

  private fun tempDir(prefix: String): File =
    java.nio.file.Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

  /**
   * Records git invocations and simulates the three calls [GitWorktrees] makes:
   * - `rev-parse <rev>^{commit}` resolves a requested revision to [resolveSha];
   * - `rev-parse <refs/…>^{commit}` (ref qualification) succeeds only for refs in [existingRefs];
   * - `merge-base --is-ancestor <sha> <ref>^{commit}` succeeds only for refs in [ancestorRefs].
   *
   * Refs are modelled fully-qualified so the qualification gate (short name → `refs/heads`/
   * `refs/remotes`, never `refs/tags`) can be exercised without a real repo.
   */
  private class FakeGit(
    var resolveSha: String? = "abc123def",
    val existingRefs: Set<String> = setOf("refs/heads/main", "refs/heads/release"),
    val ancestorRefs: Set<String> = emptySet(),
  ) : GitRunner {
    val calls = CopyOnWriteArrayList<List<String>>()

    fun count(prefix: List<String>): Int = calls.count { it.take(prefix.size) == prefix }

    override fun run(workdir: File, args: List<String>): GitResult {
      calls.add(args)
      return when {
        args.take(1) == listOf("rev-parse") -> {
          val token = args.last().removeSuffix("^{commit}")
          if (token.startsWith("refs/")) {
            if (token in existingRefs) GitResult(0, "$token\n") else GitResult(1, "")
          } else {
            resolveSha?.let { GitResult(0, "$it\n") } ?: GitResult(1, "")
          }
        }
        args.take(2) == listOf("merge-base", "--is-ancestor") -> {
          val ref = args[3].removeSuffix("^{commit}")
          if (ref in ancestorRefs) GitResult(0, "") else GitResult(1, "")
        }
        args.take(2) == listOf("worktree", "add") -> {
          val dir = File(args[args.size - 2])
          dir.mkdirs()
          File(dir, ".git").writeText("gitdir: elsewhere")
          GitResult(0, "")
        }
        else -> GitResult(0, "")
      }
    }
  }

  @Test
  fun `prepare resolves the commit and adds a worktree once, reusing it after`() {
    val git = FakeGit(ancestorRefs = setOf("refs/heads/main"))
    val cache = tempDir("wt-cache")
    GitWorktrees(
        repoRoot = tempDir("repo"),
        cacheRoot = cache,
        allowedRefs = listOf("main"),
        git = git,
      )
      .use { wt ->
        val dir = assertNotNull(wt.prepare("HEAD"))
        assertEquals(File(cache, "abc123def"), dir)
        assertTrue(File(dir, ".git").exists())
        assertEquals(1, git.count(listOf("worktree", "add")))

        // Second request for the same revision reuses the existing worktree — no second add.
        val again = assertNotNull(wt.prepare("HEAD"))
        assertEquals(dir, again)
        assertEquals(1, git.count(listOf("worktree", "add")), "an existing worktree is reused")
      }
  }

  @Test
  fun `prepare returns null when the revision cannot be resolved`() {
    val git = FakeGit(resolveSha = null, ancestorRefs = setOf("refs/heads/main"))
    GitWorktrees(
        repoRoot = tempDir("repo"),
        cacheRoot = tempDir("wt-cache"),
        allowedRefs = listOf("main"),
        git = git,
      )
      .use { wt ->
        assertNull(wt.prepare("does-not-exist"))
        assertEquals(
          0,
          git.count(listOf("worktree", "add")),
          "no worktree added for a bad revision",
        )
      }
  }

  @Test
  fun `prepare refuses a revision not reachable from any allowed ref`() {
    // Resolves fine, but the sha is reachable only from 'feature', which is not in the allowlist.
    val git = FakeGit(ancestorRefs = setOf("refs/heads/feature"))
    GitWorktrees(
        repoRoot = tempDir("repo"),
        cacheRoot = tempDir("wt-cache"),
        allowedRefs = listOf("main", "release"),
        git = git,
      )
      .use { wt ->
        assertNull(wt.prepare("deadbeef"))
        assertEquals(0, git.count(listOf("worktree", "add")), "no checkout for a disallowed rev")
      }
  }

  @Test
  fun `prepare fails closed when no refs are allowed`() {
    // Even a resolvable sha that is reachable from refs is refused when the allowlist is empty.
    val git = FakeGit(ancestorRefs = setOf("refs/heads/main"))
    GitWorktrees(
        repoRoot = tempDir("repo"),
        cacheRoot = tempDir("wt-cache"),
        allowedRefs = emptyList(),
        git = git,
      )
      .use { wt ->
        assertNull(wt.prepare("HEAD"))
        assertEquals(0, git.count(listOf("worktree", "add")))
      }
  }

  @Test
  fun `prepare allows a revision reachable from any one of several allowed refs`() {
    val git = FakeGit(ancestorRefs = setOf("refs/heads/release"))
    GitWorktrees(
        repoRoot = tempDir("repo"),
        cacheRoot = tempDir("wt-cache"),
        allowedRefs = listOf("main", "release"),
        git = git,
      )
      .use { wt -> assertNotNull(wt.prepare("HEAD")) }
  }

  @Test
  fun `prepare does not let a same-named tag satisfy a short branch allowlist`() {
    // Only a TAG 'main' exists (no refs/heads/main), and the sha is reachable from it. A short
    // allowlist entry 'main' must qualify to a branch/remote only, so this is refused — closing the
    // tag-shadowing hole where gitrevisions resolves refs/tags/<name> before the branch.
    val git =
      FakeGit(existingRefs = setOf("refs/tags/main"), ancestorRefs = setOf("refs/tags/main"))
    GitWorktrees(
        repoRoot = tempDir("repo"),
        cacheRoot = tempDir("wt-cache"),
        allowedRefs = listOf("main"),
        git = git,
      )
      .use { wt ->
        assertNull(wt.prepare("deadbeef"))
        assertEquals(0, git.count(listOf("worktree", "add")))
      }
  }

  @Test
  fun `prepare honours an explicitly fully-qualified tag ref`() {
    // An operator can still opt a tag in deliberately by qualifying it as refs/tags/<name>.
    val git = FakeGit(existingRefs = setOf("refs/tags/v1"), ancestorRefs = setOf("refs/tags/v1"))
    GitWorktrees(
        repoRoot = tempDir("repo"),
        cacheRoot = tempDir("wt-cache"),
        allowedRefs = listOf("refs/tags/v1"),
        git = git,
      )
      .use { wt -> assertNotNull(wt.prepare("deadbeef")) }
  }

  @Test
  fun `close removes the worktrees it created`() {
    val git = FakeGit(ancestorRefs = setOf("refs/heads/main"))
    GitWorktrees(
        repoRoot = tempDir("repo"),
        cacheRoot = tempDir("wt-cache"),
        allowedRefs = listOf("main"),
        git = git,
      )
      .use { wt -> wt.prepare("HEAD") }
    assertEquals(1, git.count(listOf("worktree", "remove")))
    assertEquals(1, git.count(listOf("worktree", "prune")))
  }

  @Test
  fun `remove prunes a single prepared worktree and close does not remove it again`() {
    val git = FakeGit(ancestorRefs = setOf("refs/heads/main"))
    GitWorktrees(
        repoRoot = tempDir("repo"),
        cacheRoot = tempDir("wt-cache"),
        allowedRefs = listOf("main"),
        git = git,
      )
      .use { wt ->
        val dir = assertNotNull(wt.prepare("HEAD"))
        wt.remove(dir)
        assertEquals(1, git.count(listOf("worktree", "remove")), "remove pruned the worktree")
      }
    // close() must not remove the already-reclaimed worktree a second time (it was dropped from
    // `prepared`); only the terminal `git worktree prune` runs.
    assertEquals(1, git.count(listOf("worktree", "remove")), "no double remove on close")
    assertEquals(1, git.count(listOf("worktree", "prune")))
  }

  @Test
  fun `a worktree shared by two revisions is pruned only after both reclaim`() {
    // Both revisions resolve to the same commit (FakeGit's single resolveSha), so prepare() hands
    // back the same <cacheRoot>/<sha> dir. GC of the first alias must NOT delete the worktree the
    // second alias is still using (issue #2022 review) — only the last reclaim prunes it.
    val git = FakeGit(ancestorRefs = setOf("refs/heads/main"))
    GitWorktrees(
        repoRoot = tempDir("repo"),
        cacheRoot = tempDir("wt-cache"),
        allowedRefs = listOf("main"),
        git = git,
      )
      .use { wt ->
        val a = assertNotNull(wt.prepare("HEAD"))
        val b = assertNotNull(wt.prepare("main"))
        assertEquals(a, b, "both revisions share one worktree")
        assertEquals(1, git.count(listOf("worktree", "add")), "shared worktree added once")

        wt.remove(a)
        assertEquals(
          0,
          git.count(listOf("worktree", "remove")),
          "the first reclaim only drops a reference — the worktree is still in use",
        )
        wt.remove(b)
        assertEquals(
          1,
          git.count(listOf("worktree", "remove")),
          "the last reclaim prunes the now-unused worktree",
        )
      }
    // close() must not remove the already-pruned worktree again.
    assertEquals(1, git.count(listOf("worktree", "remove")))
  }

  @Test
  fun `remove is a no-op for a worktree this instance did not prepare`() {
    val git = FakeGit(ancestorRefs = setOf("refs/heads/main"))
    GitWorktrees(
        repoRoot = tempDir("repo"),
        cacheRoot = tempDir("wt-cache"),
        allowedRefs = listOf("main"),
        git = git,
      )
      .use { wt ->
        wt.remove(File("/some/unrelated/worktree"))
        assertEquals(
          0,
          git.count(listOf("worktree", "remove")),
          "an unprepared path is never git-removed",
        )
      }
  }
}
