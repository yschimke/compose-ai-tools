package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which build the CLI drives (issue #5031).
 *
 * The old rule — "the nearest ancestor with a `gradlew`" — walked straight past a nested build that
 * borrows its parent repository's wrapper, and silently drove the enclosing build instead. These
 * pin the rule that replaced it: **the nearest ancestor with a settings file**, with the wrapper
 * search kept only as a fallback for a build that has no settings file at all.
 */
class GradleBuildRootTest {

  private val tmp: File = Files.createTempDirectory("build-root").toFile()

  @AfterTest
  fun cleanUp() {
    tmp.deleteRecursively()
  }

  private fun dir(path: String): File = File(tmp, path).apply { mkdirs() }

  private fun touch(dir: File, name: String): File =
    File(dir, name).apply {
      parentFile.mkdirs()
      writeText("")
    }

  @Test
  fun `a nested build with its own settings and no wrapper is the root`() {
    val repo = dir("repo").also { touch(it, "gradlew") }
    touch(repo, "settings.gradle.kts")
    val components = dir("repo/components").also { touch(it, "settings.gradle.kts") }

    assertEquals(components, findGradleProjectRoot(components))
    // …and the wrapper that drives it is still the repository's.
    assertEquals(repo, findGradleWrapperRoot(components))
  }

  @Test
  fun `a subproject resolves to the build that contains it`() {
    val repo = dir("repo").also { touch(it, "gradlew") }
    touch(repo, "settings.gradle.kts")
    val app = dir("repo/app").also { touch(it, "build.gradle.kts") }

    assertEquals(repo, findGradleProjectRoot(app))
  }

  @Test
  fun `a groovy settings file counts`() {
    val repo = dir("groovy").also { touch(it, "settings.gradle") }
    assertEquals(repo, findGradleProjectRoot(dir("groovy/lib")))
  }

  @Test
  fun `falls back to the wrapper when nothing declares settings`() {
    val repo = dir("wrapper-only").also { touch(it, "gradlew") }
    assertEquals(repo, findGradleProjectRoot(dir("wrapper-only/app")))
  }

  @Test
  fun `null when there is neither settings nor a wrapper`() {
    assertNull(findGradleProjectRoot(dir("bare/deep")))
  }
}
