package ee.schimke.composeai.cli.serve

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ServeRevisionFactoryTest {

  private fun tempDir(prefix: String): File =
    java.nio.file.Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

  private val module = ServeModuleRef(gradlePath = "samples:cmp", relativePath = "samples/cmp")

  /**
   * A [GitWorktrees] whose git both resolves any rev to [sha] and reports it reachable from the
   * given [allowedRefs], so the allowlist gate passes and a worktree is created — letting us drive
   * the factory's orchestration without a real repo.
   */
  private fun allowingWorktrees(sha: String = "deadbeef"): GitWorktrees =
    GitWorktrees(
      repoRoot = tempDir("repo"),
      cacheRoot = tempDir("cache"),
      allowedRefs = listOf("main"),
      git = { _, args ->
        when {
          args.take(1) == listOf("rev-parse") -> GitResult(0, "$sha\n")
          args.take(2) == listOf("merge-base", "--is-ancestor") -> GitResult(0, "")
          args.take(2) == listOf("worktree", "add") -> {
            File(args[args.size - 2]).mkdirs()
            File(File(args[args.size - 2]), ".git").writeText("gitdir: x")
            GitResult(0, "")
          }
          else -> GitResult(0, "")
        }
      },
    )

  /** A [GitWorktrees] that refuses everything (empty allowlist, fails closed). */
  private fun refusingWorktrees(): GitWorktrees =
    GitWorktrees(
      repoRoot = tempDir("repo"),
      cacheRoot = tempDir("cache"),
      allowedRefs = emptyList(),
      git = { _, _ -> GitResult(0, "deadbeef\n") },
    )

  @Test
  fun `create builds an allowed revision and marks it security-checked`() {
    val securityFlag = AtomicReference<Boolean?>(null)
    val builder = RevisionBuilder { worktreeDir, _, isSecurityChecked ->
      securityFlag.set(isSecurityChecked)
      BuiltRevision(
        moduleDir = File(worktreeDir, module.relativePath),
        descriptor = File(worktreeDir, "daemon-launch.json"),
        previews = listOf(ServePreview(id = "p1", label = "P1")),
      )
    }

    val state =
      assertNotNull(ServeRevisionFactory(allowingWorktrees(), builder, module).create("HEAD"))
    assertEquals(true, securityFlag.get(), "the builder is told the rev cleared the allowlist")
    assertEquals("samples:cmp@HEAD", state.label)
    assertEquals(listOf("p1"), state.previews.map { it.id })
  }

  @Test
  fun `create returns null and never builds a revision refused by the allowlist`() {
    val builderInvoked = AtomicBoolean(false)
    val builder = RevisionBuilder { _, _, _ ->
      builderInvoked.set(true)
      null
    }

    assertNull(ServeRevisionFactory(refusingWorktrees(), builder, module).create("HEAD"))
    assertFalse(builderInvoked.get(), "the builder must not run for a disallowed revision")
  }

  @Test
  fun `create returns null when the builder fails`() {
    val builder = RevisionBuilder { _, _, _ -> null }
    assertNull(ServeRevisionFactory(allowingWorktrees(), builder, module).create("HEAD"))
  }

  @Test
  fun `create returns null for a blank session id without touching git`() {
    val builderInvoked = AtomicBoolean(false)
    val builder = RevisionBuilder { _, _, _ ->
      builderInvoked.set(true)
      null
    }
    assertNull(ServeRevisionFactory(refusingWorktrees(), builder, module).create("   "))
    assertFalse(builderInvoked.get())
  }
}
