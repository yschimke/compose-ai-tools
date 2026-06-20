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

  /** Records git invocations and simulates rev-parse + worktree add (creating a `.git` marker). */
  private class FakeGit(var resolveSha: String? = "abc123def") : GitRunner {
    val calls = CopyOnWriteArrayList<List<String>>()

    fun count(prefix: List<String>): Int = calls.count { it.take(prefix.size) == prefix }

    override fun run(workdir: File, args: List<String>): GitResult {
      calls.add(args)
      return when {
        args.take(1) == listOf("rev-parse") ->
          resolveSha?.let { GitResult(0, "$it\n") } ?: GitResult(1, "")
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
    val git = FakeGit()
    val cache = tempDir("wt-cache")
    GitWorktrees(repoRoot = tempDir("repo"), cacheRoot = cache, git = git).use { wt ->
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
    val git = FakeGit(resolveSha = null)
    GitWorktrees(repoRoot = tempDir("repo"), cacheRoot = tempDir("wt-cache"), git = git).use { wt ->
      assertNull(wt.prepare("does-not-exist"))
      assertEquals(0, git.count(listOf("worktree", "add")), "no worktree added for a bad revision")
    }
  }

  @Test
  fun `close removes the worktrees it created`() {
    val git = FakeGit()
    GitWorktrees(repoRoot = tempDir("repo"), cacheRoot = tempDir("wt-cache"), git = git).use { wt ->
      wt.prepare("HEAD")
    }
    assertEquals(1, git.count(listOf("worktree", "remove")))
    assertEquals(1, git.count(listOf("worktree", "prune")))
  }
}
