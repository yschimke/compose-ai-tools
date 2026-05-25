package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import org.gradle.testkit.runner.GradleRunner

/**
 * End-to-end reproducer for the Confetti shape (https://github.com/joreilly/Confetti `main`):
 * `pluginManagement.repositories` declares `exclusiveContent { ... }`, and Gradle 9.3+ then rejects
 * any project that *adds repositories to* `buildscript.repositories` with `When using exclusive
 * repository content in 'settings.pluginManagement.repositories', you cannot add repositories to
 * 'buildscript.repositories'.`
 *
 * Asserts that the rendered init script's `allprojects { buildscript { repositories { ... } } }`
 * sub-block is suppressed when the settings file matches this shape, so the build configures
 * cleanly. The classpath dependency and apply hooks are intentionally kept — if the consumer's
 * existing buildscript repositories can resolve the plugin coordinate, auto-inject still works.
 *
 * PR #1483 tried to dodge this with an `initscript { classpath ... }` load, but that broke
 * plugins-that-reference-AGP at runtime (`NoClassDefFoundError:
 * com/android/build/api/variant/AndroidComponentsExtension` — init-script-loaded plugins sit on a
 * sibling classloader of AGP). The current fix keeps the plugin on the project's buildscript
 * classloader and detects the settings shape via text scan in [renderInitScript].
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
   * Gradle actually evaluate the buildscript dependency injection (an empty build script
   * short-circuits before the validation can fire, so the regression doesn't reproduce on the
   * empty-app fixture). A plain Kotlin JVM plugin is fine; the regression is repo-side, not
   * plugin-side.
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
    // The plugins {} block forces Gradle to evaluate buildscript classpath — that's where our
    // init script's `allprojects { buildscript { repositories { ... } } }` injection tries to
    // add gradlePluginPortal()/mavenCentral()/google() and (without the exclusiveContent skip)
    // trips Gradle 9.3+'s validation. An empty :app build file would short-circuit before any
    // of that runs and the regression wouldn't reproduce.
    File(app, "build.gradle.kts").writeText("""plugins { kotlin("jvm") version "2.2.21" }""")
    return root
  }

  @Test
  fun `rendered init script does not trip exclusiveContent validation against the Confetti shape`() {
    // Pre-fix: without the repositories skip, this build fails with
    //   "When using exclusive repository content in 'settings.pluginManagement.repositories',
    //    you cannot add repositories to 'buildscript.repositories'."
    // Post-fix: our buildscript injection skips the repositories add. The classpath dep is
    // still added and resolution is still attempted — if the consumer has their own
    // `buildscript { repositories { ... } }` declaring a repo that hosts the plugin, the
    // plugin resolves and auto-inject works. In this test fixture the consumer has no
    // buildscript repos, so Gradle fails with a clear "Cannot resolve external dependency
    // ... because no repositories are defined" — that's the intended escape hatch, and
    // crucially it is NOT the exclusiveContent validation. The user's documented fallback
    // is to apply the plugin manually via `plugins { id("ee.schimke.composeai.preview")
    // version "X" }`.
    val project = createConfettiShapedProject()
    val initScript = materializeInitScript(tempDir(), "0.11.7")

    // `:app:help` forces configuration of the :app subproject (its `plugins { kotlin("jvm") }`
    // block triggers buildscript evaluation, which is what surfaces the exclusiveContent
    // validation against our init script's allprojects-level buildscript injection). A bare
    // `:help` on root wouldn't configure :app and the regression would silently not reproduce.
    val output =
      try {
        GradleRunner.create()
          .withProjectDir(project)
          .withArguments(":app:help", "--init-script", initScript.absolutePath, "--stacktrace")
          .forwardOutput()
          .build()
          .output
      } catch (e: org.gradle.testkit.runner.UnexpectedBuildFailure) {
        e.buildResult.output
      }

    assertFalse(
      output.contains("exclusive repository content"),
      "init script tripped the exclusiveContent validation — the repositories sub-block of " +
        "the buildscript injection isn't being suppressed for the Confetti shape; full " +
        "output:\n$output",
    )
  }

  @Test
  fun `rendered init script applies the buildscript classpath injection when settings has no exclusiveContent`() {
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

    val initScript = materializeInitScript(tempDir(), "0.11.7")

    val runner =
      GradleRunner.create()
        .withProjectDir(root)
        .withArguments("help", "--init-script", initScript.absolutePath)
        .forwardOutput()

    // Either path is acceptable for *this* assertion; we only care that the failure (if any) is
    // not the exclusiveContent validation. `build()` / `buildAndFail()` both return a
    // `BuildResult` we can read `.output` from.
    val output =
      try {
        runner.build().output
      } catch (e: Exception) {
        // BuildResult-bearing runtime exceptions surface stdout/stderr in their message.
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
