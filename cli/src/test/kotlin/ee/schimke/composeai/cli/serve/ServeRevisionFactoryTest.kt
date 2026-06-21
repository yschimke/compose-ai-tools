package ee.schimke.composeai.cli.serve

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ServeRevisionFactoryTest {

  private fun tempDir(prefix: String): File =
    java.nio.file.Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

  /** A [GitRunner] that resolves any rev to [sha] and "checks out" a `.git`-marked worktree. */
  private class FakeGit(private val sha: String?) : GitRunner {
    override fun run(workdir: File, args: List<String>): GitResult =
      when {
        args.take(1) == listOf("rev-parse") -> sha?.let { GitResult(0, it) } ?: GitResult(1, "")
        args.take(2) == listOf("worktree", "add") -> {
          val dir = File(args[args.size - 2])
          dir.mkdirs()
          File(dir, ".git").writeText("x")
          GitResult(0, "")
        }
        else -> GitResult(0, "")
      }
  }

  private fun worktrees(sha: String?): GitWorktrees =
    GitWorktrees(repoRoot = tempDir("repo"), cacheRoot = tempDir("cache"), git = FakeGit(sha))

  private val module = ServeModuleRef(gradlePath = "samples:cmp", relativePath = "samples/cmp")

  @Test
  fun `create builds a session state labelled module at rev`() {
    val builder = RevisionBuilder { worktreeDir, m ->
      val moduleDir = File(worktreeDir, m.relativePath)
      BuiltRevision(
        moduleDir = moduleDir,
        descriptor = File(moduleDir, "build/compose-previews/daemon-launch.json"),
        previews = listOf(ServePreview("com.example.Red", "Red")),
      )
    }
    val factory = ServeRevisionFactory(worktrees("abcdef0"), builder, module)

    val state = assertNotNull(factory.create("HEAD"))
    assertEquals("samples:cmp@HEAD", state.label)
    assertEquals(listOf(ServePreview("com.example.Red", "Red")), state.previews)
    assertEquals("daemon-launch.json", state.descriptor.name)
  }

  @Test
  fun `create returns null when the revision cannot be checked out`() {
    val builder = RevisionBuilder { _, _ -> error("builder must not run for an unresolved rev") }
    assertNull(ServeRevisionFactory(worktrees(sha = null), builder, module).create("bogus"))
  }

  @Test
  fun `create returns null when the build fails`() {
    val builder = RevisionBuilder { _, _ -> null }
    assertNull(ServeRevisionFactory(worktrees("abcdef0"), builder, module).create("HEAD"))
  }

  @Test
  fun `create returns null for a blank revision`() {
    val builder = RevisionBuilder { _, _ -> error("builder must not run for a blank rev") }
    assertNull(ServeRevisionFactory(worktrees("abcdef0"), builder, module).create("  "))
  }
}
