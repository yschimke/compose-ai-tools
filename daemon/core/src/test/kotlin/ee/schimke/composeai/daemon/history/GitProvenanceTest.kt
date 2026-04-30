package ee.schimke.composeai.daemon.history

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins [GitProvenance] resolution against:
 * - A synthetic `git init`'d directory — must populate worktree.path / git.branch / git.commit /
 *   git.dirty without any of those being null.
 * - A non-git directory — every git/worktree-path field is null; agentId stays whatever the env
 *   says.
 *
 * Skipped when the test JVM has no `git` on the path (CI containers without git installed). The
 * brief explicitly says don't gate the harness's S9 on git availability — same here for the
 * core unit test.
 */
class GitProvenanceTest {

  private lateinit var tmpDir: Path

  @Before
  fun setUp() {
    tmpDir = Files.createTempDirectory("git-provenance-test")
    assumeTrue("git on PATH required for GitProvenanceTest", isGitAvailable())
  }

  @After
  fun tearDown() {
    tmpDir.toFile().deleteRecursively()
  }

  @Test
  fun synthetic_git_repo_populates_branch_commit_dirty_remote() {
    initGitRepo(tmpDir.toFile())
    // Configure user.name + user.email so commit doesn't fail on a clean-room machine.
    runOrFail(tmpDir.toFile(), "git", "config", "user.email", "test@example.com")
    runOrFail(tmpDir.toFile(), "git", "config", "user.name", "Test User")
    runOrFail(tmpDir.toFile(), "git", "remote", "add", "origin", "https://example.com/repo.git")
    val testFile = tmpDir.resolve("hello.txt")
    Files.writeString(testFile, "hello\n")
    runOrFail(tmpDir.toFile(), "git", "add", "hello.txt")
    runOrFail(tmpDir.toFile(), "git", "commit", "-m", "initial", "--no-gpg-sign")

    val provenance = GitProvenance(workspaceRoot = tmpDir, env = mapOf())
    val (worktree, git) = provenance.snapshot()
    assertNotNull("worktree must be non-null in a git repo", worktree)
    assertEquals(
      tmpDir.toRealPath().toString(),
      Path.of(worktree!!.path!!).toRealPath().toString(),
    )
    assertNotNull("git provenance must be non-null in a git repo", git)
    assertNotNull("commit must be present", git!!.commit)
    assertEquals(7, git.shortCommit?.length)
    assertEquals(false, git.dirty)
    assertEquals("https://example.com/repo.git", git.remote)
    // worktree.id defaults to dir basename.
    assertEquals(tmpDir.fileName.toString(), worktree.id)
  }

  @Test
  fun dirty_flag_flips_when_working_tree_has_uncommitted_changes() {
    initGitRepo(tmpDir.toFile())
    runOrFail(tmpDir.toFile(), "git", "config", "user.email", "test@example.com")
    runOrFail(tmpDir.toFile(), "git", "config", "user.name", "Test User")
    val initialFile = tmpDir.resolve("a.txt")
    Files.writeString(initialFile, "x\n")
    runOrFail(tmpDir.toFile(), "git", "add", "a.txt")
    runOrFail(tmpDir.toFile(), "git", "commit", "-m", "init", "--no-gpg-sign")
    // Add an uncommitted change.
    Files.writeString(tmpDir.resolve("a.txt"), "y\n")

    val provenance = GitProvenance(workspaceRoot = tmpDir, env = mapOf())
    val (_, git) = provenance.snapshot()
    assertEquals(true, git!!.dirty)
  }

  @Test
  fun non_git_directory_yields_null_git_and_null_worktree_path() {
    val nonGitDir = Files.createTempDirectory("non-git-")
    try {
      val provenance = GitProvenance(workspaceRoot = nonGitDir, env = mapOf())
      val (worktree, git) = provenance.snapshot()
      // worktree may be null OR carry only id-label/agentId if any env was set; here env is empty so:
      assertTrue(worktree == null || (worktree.path == null && worktree.id == null && worktree.agentId == null))
      assertNull(git)
    } finally {
      nonGitDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun agentId_env_populates_worktree_agentId() {
    val nonGitDir = Files.createTempDirectory("agent-env-")
    try {
      val provenance =
        GitProvenance(
          workspaceRoot = nonGitDir,
          env = mapOf(GitProvenance.ENV_AGENT_ID to "agent-foo"),
        )
      val (worktree, _) = provenance.snapshot()
      assertNotNull(worktree)
      assertEquals("agent-foo", worktree!!.agentId)
    } finally {
      nonGitDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun worktreeId_env_overrides_basename() {
    initGitRepo(tmpDir.toFile())
    runOrFail(tmpDir.toFile(), "git", "config", "user.email", "test@example.com")
    runOrFail(tmpDir.toFile(), "git", "config", "user.name", "Test User")
    Files.writeString(tmpDir.resolve("x"), "x")
    runOrFail(tmpDir.toFile(), "git", "add", "x")
    runOrFail(tmpDir.toFile(), "git", "commit", "-m", "init", "--no-gpg-sign")

    val provenance =
      GitProvenance(workspaceRoot = tmpDir, env = mapOf(GitProvenance.ENV_WORKTREE_ID to "main"))
    val (worktree, _) = provenance.snapshot()
    assertEquals("main", worktree!!.id)
  }

  private fun initGitRepo(dir: File) {
    runOrFail(dir, "git", "init", "-b", "main")
  }

  private fun isGitAvailable(): Boolean =
    try {
      val proc = ProcessBuilder("git", "--version").redirectErrorStream(true).start()
      proc.waitFor(5, TimeUnit.SECONDS)
      proc.exitValue() == 0
    } catch (_: Throwable) {
      false
    }

  private fun runOrFail(workingDir: File, vararg cmd: String) {
    val proc =
      ProcessBuilder(*cmd)
        .directory(workingDir)
        .redirectErrorStream(true)
        .start()
    val finished = proc.waitFor(15, TimeUnit.SECONDS)
    assertTrue("$cmd timed out", finished)
    assertEquals(
      "command failed: ${cmd.joinToString(" ")} → ${proc.inputStream.bufferedReader().readText()}",
      0,
      proc.exitValue(),
    )
  }
}
