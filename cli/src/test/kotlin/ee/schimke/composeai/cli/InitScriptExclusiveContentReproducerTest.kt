package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

/**
 * End-to-end reproducer for the Confetti shape (https://github.com/joreilly/Confetti `main`):
 * `pluginManagement.repositories` declares `exclusiveContent { ... }`, and Gradle 9.3+ then rejects
 * any project that *adds repositories to* `buildscript.repositories` with `When using exclusive
 * repository content in 'settings.pluginManagement.repositories', you cannot add repositories to
 * 'buildscript.repositories'.`
 *
 * Asserts that the rendered init script's `allprojects { buildscript { repositories { ... } } }`
 * sub-block is suppressed when the settings file matches this shape AND that the classpath
 * dependency injection is also skipped for modules without their own `buildscript { repositories {
 * ... } }` — adding the dep there would crash configuration with `Cannot resolve external
 * dependency ... because no repositories are defined`, short-circuiting the entire Tooling API
 * query (the 0.11.8 regression; the bug filed against the v0.11.8 follow-up to PRs #1483/#1490).
 *
 * PR #1483 tried to dodge the validation with an `initscript { classpath ... }` load, but that
 * broke plugins-that-reference-AGP at runtime (`NoClassDefFoundError:
 * com/android/build/api/variant/AndroidComponentsExtension` — init-script-loaded plugins sit on a
 * sibling classloader of AGP). The current fix keeps the plugin on the project's buildscript
 * classloader and forks per-project: modules with their own buildscript repos get the classpath dep
 * injected (resolution can succeed via those); modules without are skipped entirely.
 *
 * Uses TestKit's default Gradle (the wrapper version) so the test fires the same validation that
 * production users hit; older Gradle wouldn't see the validation at all.
 */
class InitScriptExclusiveContentReproducerTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
  }

  private fun tempDir(prefix: String = "compose-preview-confetti-"): File =
    Files.createTempDirectory(prefix).toFile().also { tempDirs += it }

  /**
   * Sets up a minimal project that mirrors Confetti's settings shape: `exclusiveContent` declared
   * inside `pluginManagement { listOf(repositories, dependencyResolutionManagement.repositories)
   * .forEach { ... } }`. The `:app` subproject applies a `plugins { }` block — required to make
   * Gradle actually evaluate the buildscript classpath, which is where our injection fires.
   * Crucially `:app/build.gradle.kts` does NOT declare its own `buildscript { repositories { ... }
   * }` — that matches the realistic Confetti shape where modules route everything through
   * pluginManagement / dependencyResolutionManagement.
   */
  private fun createConfettiShapedProject(): File {
    val root = tempDir()
    File(root, "settings.gradle.kts")
      .writeText(
        """
        pluginManagement {
            listOf(repositories, dependencyResolutionManagement.repositories).forEach {
                it.apply {
                    google()
                    mavenCentral()
                    exclusiveContent {
                        forRepository { it.maven("https://example.com/m2") }
                        filter { includeVersionByRegex("com.example.snapshots", ".*", ".*SNAPSHOT.*") }
                    }
                }
            }
        }
        rootProject.name = "confetti-repro"
        include(":app")
        """
          .trimIndent()
      )
    File(root, "build.gradle.kts").writeText("// root build script — intentionally empty\n")
    val app = File(root, "app").apply { mkdirs() }
    File(app, "build.gradle.kts").writeText("""plugins { kotlin("jvm") version "2.2.21" }""")
    return root
  }

  @Test
  fun `init script configures cleanly against a Confetti-shaped project with no module buildscript repos`() {
    // Original 0.11.7 regression: `allprojects { buildscript { repositories { ... } } }`
    // tripped Gradle 9.3+'s
    //   "When using exclusive repository content in 'settings.pluginManagement.repositories',
    //    you cannot add repositories to 'buildscript.repositories'."
    // 0.11.8 fix sidestepped that validation but still injected the classpath dep, which then
    // failed with "Cannot resolve external dependency ... because no repositories are defined"
    // for any module without its own buildscript { repositories { ... } } — that crashed the
    // whole Tooling API query (the 0.11.8 follow-up regression). The current fix skips both
    // the repos add and the classpath dep injection on modules that have no buildscript repos
    // of their own, so configuration completes cleanly. Modules silently miss the plugin in
    // this branch — auto-inject is meant to be invisible.
    val project = createConfettiShapedProject()
    val initScript = materializeInitScript(tempDir(), "0.11.9")

    val result =
      GradleRunner.create()
        .withProjectDir(project)
        .withArguments(":app:help", "--init-script", initScript.absolutePath, "--stacktrace")
        .forwardOutput()
        .build()

    assertFalse(
      result.output.contains("exclusive repository content"),
      "init script tripped the exclusiveContent validation — the repositories sub-block of " +
        "the buildscript injection isn't being suppressed; full output:\n${result.output}",
    )
    assertFalse(
      result.output.contains("Cannot resolve external dependency"),
      "init script tried to inject the classpath dep on a module without buildscript " +
        "repositories — would crash the whole Tooling API query; full output:\n${result.output}",
    )
    assertEquals(
      TaskOutcome.SUCCESS,
      result.task(":app:help")?.outcome,
      "expected :app:help to succeed against a Confetti-shaped project; got:\n${result.output}",
    )
    assertFalse(
      result.output.contains("settings.gradle.kts declares exclusiveContent"),
      "init script should not emit lifecycle logs nudging the user to apply the plugin; got:\n" +
        result.output,
    )
  }

  @Test
  fun `init script still injects buildscript classpath when settings has no exclusiveContent`() {
    // Sanity check that the guard is narrow — a settings file WITHOUT exclusiveContent must still
    // see the buildscript injection (the auto-inject happy path the CLI is built around; issue
    // #305 and friends). We can't easily verify successful plugin resolution end-to-end here
    // without spinning up mavenLocal with our SNAPSHOT, so the assertion is structural: run the
    // build, and whether it succeeds or fails (the plugin coordinate may not resolve), the
    // failure must NOT be the exclusiveContent validation. A false positive in the scanner that
    // suppresses injection on a vanilla project would silently regress auto-inject for every
    // consumer.
    val root = tempDir()
    File(root, "settings.gradle.kts")
      .writeText(
        """
        pluginManagement { repositories { gradlePluginPortal() } }
        dependencyResolutionManagement { repositories { mavenCentral(); google() } }
        rootProject.name = "no-exclusive-content"
        """
          .trimIndent()
      )
    File(root, "build.gradle.kts").writeText("// intentionally empty\n")

    val initScript = materializeInitScript(tempDir(), "0.11.9")

    val runner =
      GradleRunner.create()
        .withProjectDir(root)
        .withArguments("help", "--init-script", initScript.absolutePath)
        .forwardOutput()

    val output =
      try {
        runner.build().output
      } catch (e: Exception) {
        e.message.orEmpty()
      }
    assertFalse(
      output.contains("exclusive repository content"),
      "exclusiveContent validation fired on a settings file that doesn't declare it — " +
        "scanner false-positive would silently disable auto-inject for vanilla consumers; " +
        "output:\n$output",
    )
  }
}
