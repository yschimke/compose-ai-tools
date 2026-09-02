package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

/**
 * End-to-end reproducer for the Bitwarden shape (https://github.com/bitwarden/android `main`): the
 * build locks its buildscript classpath — `buildscript { configurations.classpath {
 * resolutionStrategy.activateDependencyLocking() } }` plus a checked-in
 * `buildscript-gradle.lockfile` — so Gradle rejects ANY module the lock state does not name:
 * ```
 * A problem occurred configuring root project 'Bitwarden'.
 * > Could not resolve all artifacts for configuration 'classpath'.
 *    > LockOutOfDateException: Resolved 'org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0'
 *      which is not part of the dependency lock state
 * ```
 *
 * Auto-inject adds the compose-preview plugin to exactly that configuration, so on a locked build
 * it fails during configuration — before discovery, before any render. The import of
 * bitwarden/android hit this on its first build (yschimke/compose-preview-imports#30): 51 previews,
 * none of them reachable.
 *
 * Locking is a property of somebody else's build and not ours to edit — the checkout is theirs and
 * the lockfile is deliberate. What the init script may do is stop locking applying to the ONE
 * configuration it injects into, for the duration of this render: a build we drive to draw pictures
 * has no stake in the lock state of its own buildscript classpath. That is what
 * `deactivateDependencyLocking()` does here, and these tests pin both halves — the locked build
 * configures and the plugin actually applies, and an unlocked build is left exactly as it was.
 *
 * Modelled on [InitScriptExclusiveContentReproducerTest]: same TestKit shape, same stub-plugin
 * publishing, so the two read as a pair.
 */
class InitScriptDependencyLockingReproducerTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
  }

  private fun tempDir(prefix: String = "compose-preview-locking-"): File =
    Files.createTempDirectory(prefix).toFile().also { tempDirs += it }

  /**
   * A project whose root buildscript classpath is locked to an EMPTY lock state, which is the
   * strictest form of the Bitwarden shape: every module the init script injects is "not part of the
   * dependency lock state". `:app` applies the stub `com.android.application`, the host id that
   * triggers auto-inject.
   */
  private fun createLockedProject(repo: File): File {
    val root = tempDir()
    File(root, "settings.gradle.kts")
      .writeText(
        """
        pluginManagement {
            repositories {
                maven { url = uri("${repo.toURI()}") }
                gradlePluginPortal()
            }
        }
        dependencyResolutionManagement {
            repositories { maven { url = uri("${repo.toURI()}") } }
        }
        rootProject.name = "locking-repro"
        include(":app")
        """
          .trimIndent()
      )
    File(root, "build.gradle.kts")
      .writeText(
        """
        buildscript {
            repositories { maven { url = uri("${repo.toURI()}") } }
            configurations.classpath { resolutionStrategy.activateDependencyLocking() }
        }
        """
          .trimIndent()
      )
    // Gradle's own lockfile format. `empty=classpath` states that `classpath` resolved to nothing,
    // so any dependency added to it is out of date — the Bitwarden failure, minimised.
    File(root, "buildscript-gradle.lockfile")
      .writeText(
        """
        # This is a Gradle generated file for dependency locking.
        # Manual edits can break the build and are not advised.
        # This file is expected to be part of source control.
        empty=classpath
        """
          .trimIndent()
      )
    val app = File(root, "app").apply { mkdirs() }
    File(app, "build.gradle.kts")
      .writeText("""plugins { id("com.android.application") version "1.0" }""")
    return app.parentFile
  }

  @Test
  fun `init script configures cleanly against a build whose buildscript classpath is locked`() {
    val repo = tempDir("compose-preview-locking-repo-")
    publishStubPlugins(repo)
    val project = createLockedProject(repo)
    val initScript = materializeInitScript(tempDir(), "1.0")

    val result =
      GradleRunner.create()
        .withProjectDir(project)
        .withArguments(":app:help", "--init-script", initScript.absolutePath, "--stacktrace")
        .forwardOutput()
        .build()

    assertFalse(
      result.output.contains("not part of the dependency lock state"),
      "auto-inject added the plugin to a locked buildscript classpath — configuration fails " +
        "before discovery runs; full output:\n${result.output}",
    )
    assertEquals(
      TaskOutcome.SUCCESS,
      result.task(":app:help")?.outcome,
      "expected :app:help to succeed against a lock-shaped project; got:\n${result.output}",
    )
  }

  @Test
  fun `init script still applies the plugin when the buildscript classpath is locked`() {
    // Configuring cleanly is not enough: an injection silently skipped would also pass the test
    // above, and would publish an empty catalog — which is how bitwarden-android failed in the
    // first place. This pins that the plugin REACHES the module.
    val repo = tempDir("compose-preview-locking-repo-")
    publishStubPlugins(repo)
    val project = createLockedProject(repo)
    val initScript = materializeInitScript(tempDir(), "1.0")

    val result =
      GradleRunner.create()
        .withProjectDir(project)
        .withArguments(":app:help", "--init-script", initScript.absolutePath, "--stacktrace")
        .forwardOutput()
        .build()

    assertTrue(
      result.output.contains("COMPOSE-PREVIEW-APPLIED to :app"),
      "expected the compose-preview plugin to be auto-injected and applied to :app on a build " +
        "with a locked buildscript classpath; full output:\n${result.output}",
    )
  }

  @Test
  fun `init script leaves dependency locking alone on a build that does not lock`() {
    // The guard must be narrow: deactivating locking is a change to somebody else's build, so it
    // may only happen where the alternative is failing outright. A build with no locking must be
    // untouched — no deactivation, no behaviour change, just the ordinary auto-inject path.
    val repo = tempDir("compose-preview-locking-repo-")
    publishStubPlugins(repo)
    val root = tempDir()
    File(root, "settings.gradle.kts")
      .writeText(
        """
        pluginManagement {
            repositories {
                maven { url = uri("${repo.toURI()}") }
                gradlePluginPortal()
            }
        }
        rootProject.name = "unlocked"
        include(":app")
        """
          .trimIndent()
      )
    // Same buildscript repositories as the locked fixture, minus the locking — so the only
    // difference between the two projects is the thing under test.
    File(root, "build.gradle.kts")
      .writeText(
        """
        buildscript {
            repositories { maven { url = uri("${repo.toURI()}") } }
        }
        """
          .trimIndent()
      )
    val app = File(root, "app").apply { mkdirs() }
    File(app, "build.gradle.kts")
      .writeText("""plugins { id("com.android.application") version "1.0" }""")

    val initScript = materializeInitScript(tempDir(), "1.0")
    val result =
      GradleRunner.create()
        .withProjectDir(root)
        .withArguments(":app:help", "--init-script", initScript.absolutePath, "--stacktrace")
        .forwardOutput()
        .build()

    assertTrue(
      result.output.contains("COMPOSE-PREVIEW-APPLIED to :app"),
      "the unlocked happy path must keep working; full output:\n${result.output}",
    )
  }

  /**
   * Publishes the two stub plugins this test needs to a local maven repo: the compose-preview
   * plugin (prints a marker on apply) and a fake `com.android.application` (the withPlugin host id
   * that triggers auto-inject). Same approach as [InitScriptExclusiveContentReproducerTest], kept
   * local so neither test can break the other by editing a shared fixture.
   */
  private fun publishStubPlugins(repo: File) {
    val build = tempDir("compose-preview-locking-stubs-")
    File(build, "settings.gradle.kts")
      .writeText("rootProject.name = \"stubs\"\ninclude(\":preview\", \":agp\")\n")
    File(build, "build.gradle.kts").writeText("")

    fun stub(
      module: String,
      group: String,
      pluginId: String,
      pkg: String,
      cls: String,
      msg: String,
    ) {
      val dir = File(build, module).apply { mkdirs() }
      File(dir, "build.gradle.kts")
        .writeText(
          """
          plugins {
              `java-gradle-plugin`
              `maven-publish`
          }
          group = "$group"
          version = "1.0"
          gradlePlugin {
              plugins {
                  create("$module") {
                      id = "$pluginId"
                      implementationClass = "$pkg.$cls"
                  }
              }
          }
          publishing { repositories { maven { url = uri("${repo.toURI()}") } } }
          """
            .trimIndent()
        )
      val src = File(dir, "src/main/java/$pkg").apply { mkdirs() }
      File(src, "$cls.java")
        .writeText(
          """
          package $pkg;
          import org.gradle.api.Plugin;
          import org.gradle.api.Project;
          public class $cls implements Plugin<Project> {
              public void apply(Project p) { System.out.println("$msg to " + p.getPath()); }
          }
          """
            .trimIndent()
        )
    }

    stub(
      module = "preview",
      group = "ee.schimke.composeai.preview",
      pluginId = "ee.schimke.composeai.preview",
      pkg = "cp",
      cls = "PreviewPlugin",
      msg = "COMPOSE-PREVIEW-APPLIED",
    )
    stub(
      module = "agp",
      group = "com.android.tools.build",
      pluginId = "com.android.application",
      pkg = "agp",
      cls = "FakeAgpPlugin",
      msg = "FAKE-AGP-APPLIED",
    )

    GradleRunner.create().withProjectDir(build).withArguments("publish", "--stacktrace").build()
  }
}
