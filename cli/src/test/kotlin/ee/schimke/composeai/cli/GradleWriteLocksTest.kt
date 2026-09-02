package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner

/**
 * The Bitwarden shape (https://github.com/bitwarden/android): the build locks its buildscript
 * classpath with `lockAllConfigurations()` and `LockMode.STRICT`, so it rejects every module its
 * lock state does not name — and auto-inject's whole job is to add one:
 * ```
 * > Could not resolve all artifacts for configuration 'classpath'.
 *    > Resolved '...' which is not part of the dependency lock state
 * ```
 *
 * That fires while the buildscript classpath resolves, before discovery runs. The import hit it
 * with 51 previews it could never reach (yschimke/compose-preview-imports#30).
 *
 * These tests pin the mechanism rather than a bypass. `--write-locks` regenerates the lock state to
 * describe what was actually resolved, which is Gradle's own answer to "the classpath changed";
 * under STRICT mode it is also the only one that works, because deleting the lockfile makes a
 * strict build fail rather than relax.
 */
class GradleWriteLocksTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest fun cleanup() = tempDirs.forEach { it.deleteRecursively() }

  private fun tempDir(): File =
    Files.createTempDirectory("compose-preview-write-locks-").toFile().also { tempDirs += it }

  @Test
  fun `the flag is opt-in through the environment, never inferred`() {
    // --write-locks REWRITES files in the project. Doing that to a developer's working tree
    // because we noticed they lock is not ours to decide, so nothing about a lockfile on disk
    // turns this on — only the import pipeline's explicit environment variable does.
    assertEquals(emptyList(), gradleWriteLocksArgs { null })
    assertEquals(
      emptyList(),
      gradleWriteLocksArgs { if (it == "COMPOSE_PREVIEW_WRITE_LOCKS") "0" else null },
    )
    assertEquals(
      listOf("--write-locks"),
      gradleWriteLocksArgs { if (it == "COMPOSE_PREVIEW_WRITE_LOCKS") "1" else null },
    )
  }

  /**
   * A build locked exactly as bitwarden/android locks: strict, every configuration, empty state.
   */
  private fun createStrictlyLockedProject(): File {
    val root = tempDir()
    File(root, "settings.gradle.kts")
      .writeText(
        """
        dependencyResolutionManagement { repositories { mavenCentral() } }
        rootProject.name = "write-locks-repro"
        include(":app")
        """
          .trimIndent()
      )
    File(root, "build.gradle.kts")
      .writeText(
        """
        buildscript {
            repositories { mavenCentral() }
            dependencyLocking {
                lockAllConfigurations()
                lockMode.set(LockMode.STRICT)
            }
            // Stands in for the module auto-inject adds: something the lock state does not name.
            dependencies { classpath("com.squareup.okio:okio:3.9.0") }
        }
        """
          .trimIndent()
      )
    File(root, "buildscript-gradle.lockfile")
      .writeText("# This is a Gradle generated file for dependency locking.\nempty=classpath\n")
    File(root, "app").apply { mkdirs() }.also { File(it, "build.gradle.kts").writeText("") }
    return root
  }

  @Test
  fun `a strictly locked build fails on an unlocked classpath module`() {
    val project = createStrictlyLockedProject()
    val result =
      GradleRunner.create().withProjectDir(project).withArguments(":app:help").buildAndFail()
    assertTrue(
      result.output.contains("not part of the dependency lock state"),
      "expected the Bitwarden failure to reproduce; got:\n${result.output}",
    )
  }

  @Test
  fun `--write-locks clears it and rewrites the lock state to match what resolved`() {
    val project = createStrictlyLockedProject()
    GradleRunner.create()
      .withProjectDir(project)
      .withArguments(":app:help", *gradleWriteLocksArgs { "1" }.toTypedArray())
      .build()

    val lockfile = File(project, "buildscript-gradle.lockfile").readText()
    assertTrue(
      lockfile.contains("com.squareup.okio:okio:3.9.0=classpath"),
      "the lock state must now DESCRIBE what was resolved, not have been bypassed; got:\n$lockfile",
    )
  }
}
