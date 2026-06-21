package ee.schimke.composeai.cli.serve

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ServeRevisionFactoryTest {

  private fun tempDir(prefix: String): File =
    java.nio.file.Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

  private val module = ServeModuleRef(gradlePath = "samples:cmp", relativePath = "samples/cmp")

  @Test
  fun `project mode is failed closed before any checkout (RCE stopgap)`() {
    // create() must fail closed BEFORE resolving / checking out the client-supplied revision and
    // before invoking the builder — see ServeRevisionFactory's TODO("secure this"). When project
    // mode is hardened (revision allowlist + build isolation) this is replaced with real coverage.
    val gitInvoked = AtomicBoolean(false)
    val worktrees =
      GitWorktrees(
        repoRoot = tempDir("repo"),
        cacheRoot = tempDir("cache"),
        git = { _, _ ->
          gitInvoked.set(true)
          GitResult(0, "deadbeef")
        },
      )
    val builderInvoked = AtomicBoolean(false)
    val builder = RevisionBuilder { _, _ ->
      builderInvoked.set(true)
      null
    }

    assertFailsWith<NotImplementedError> {
      ServeRevisionFactory(worktrees, builder, module).create("HEAD")
    }
    assertFalse(gitInvoked.get(), "no revision is checked out while project mode is failed closed")
    assertFalse(builderInvoked.get(), "the builder must not run while failed closed")
  }
}
