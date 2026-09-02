package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner

/**
 * End-to-end reproducer for the Mullvad shape (https://github.com/mullvad/mullvadvpn-app
 * `android/`): the build ships a checked-in `gradle/verification-metadata.xml` with
 * `<verify-metadata>true</verify-metadata>`, so Gradle refuses any artifact the file does not carry
 * a checksum for. Auto-inject resolves a plugin that file has never heard of, so the render fails
 * before it starts:
 * ```
 * Dependency verification failed for configuration ':classpath'
 * One artifact failed verification: ee.schimke.composeai.preview.gradle.plugin-1.0.pom
 * ```
 *
 * That is not a bug in their build — a VPN client verifying every artifact it pulls is the whole
 * point of the file, and their checkout is not ours to edit. What the CLI can do is ask Gradle to
 * report verification failures instead of failing on them, for the one throwaway run it drives to
 * draw pictures, and say so on stderr rather than doing it quietly.
 *
 * Unlike the locking shape ([InitScriptDependencyLockingReproducerTest]), injecting the plugin as
 * `files(...)` does not help here: the resolution that produces those files is itself verified, so
 * the failure just moves. The lever has to be the Gradle invocation, which is why this test drives
 * [autoInjectInitScriptArgs] — the CLI-level seam every command shares — rather than only
 * [materializeInitScript].
 */
class InitScriptDependencyVerificationReproducerTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
  }

  private fun tempDir(prefix: String = "compose-preview-verification-"): File =
    Files.createTempDirectory(prefix).toFile().also { tempDirs += it }

  /**
   * A project that verifies metadata for every artifact and trusts nothing — the strictest form of
   * the Mullvad shape, and the one that makes the injected plugin unverifiable by construction.
   */
  private fun createVerifyingProject(repo: File): File {
    val root = tempDir()
    File(root, "settings.gradle.kts")
      .writeText(
        """
        pluginManagement {
            repositories { maven { url = uri("${repo.toURI()}") } }
        }
        dependencyResolutionManagement {
            repositories { maven { url = uri("${repo.toURI()}") } }
        }
        rootProject.name = "verification-repro"
        include(":app")
        """
          .trimIndent()
      )
    File(root, "build.gradle.kts")
      .writeText(
        """
        buildscript {
            repositories { maven { url = uri("${repo.toURI()}") } }
        }
        """
          .trimIndent()
      )
    File(root, "gradle").mkdirs()
    File(root, "gradle/verification-metadata.xml")
      .writeText(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <verification-metadata>
           <configuration>
              <verify-metadata>true</verify-metadata>
              <verify-signatures>false</verify-signatures>
           </configuration>
           <components/>
        </verification-metadata>
        """
          .trimIndent()
      )
    val app = File(root, "app").apply { mkdirs() }
    File(app, "build.gradle.kts")
      .writeText("""plugins { id("com.android.application") version "1.0" }""")
    return root
  }

  @Test
  fun `auto-inject reaches a build that verifies every dependency`() {
    val repo = tempDir("compose-preview-verification-repo-")
    publishStubPlugins(repo)
    val project = createVerifyingProject(repo)

    val notes = mutableListOf<String>()
    val injectArgs =
      autoInjectInitScriptArgs(
        args = emptyList(),
        pluginVersion = "1.0",
        storageDir = tempDir(),
        env = { null },
        projectRoot = project,
        stderr = { notes += it },
      )

    val result =
      GradleRunner.create()
        .withProjectDir(project)
        .withArguments(listOf(":app:help") + injectArgs + "--stacktrace")
        .forwardOutput()
        .build()

    assertTrue(
      result.output.contains("COMPOSE-PREVIEW-APPLIED to :app"),
      "expected the compose-preview plugin to be auto-injected and applied to :app on a build " +
        "that verifies its dependencies; full output:\n${result.output}",
    )
    assertTrue(
      notes.any { it.contains("verification") },
      "relaxing somebody else's dependency verification must be said out loud on stderr, not " +
        "done quietly; notes were: $notes",
    )
  }

  @Test
  fun `a build with no verification metadata is invoked exactly as before`() {
    // The relaxation must be scoped to builds that actually verify: a project without the file
    // must get the plain init-script args and nothing else.
    val repo = tempDir("compose-preview-verification-repo-")
    val root = tempDir()
    File(root, "settings.gradle.kts").writeText("rootProject.name = \"unverified\"\n")

    val injectArgs =
      autoInjectInitScriptArgs(
        args = emptyList(),
        pluginVersion = "1.0",
        storageDir = tempDir(),
        env = { null },
        projectRoot = root,
        stderr = {},
      )

    assertTrue(
      injectArgs.none { it.contains("dependency-verification") },
      "a build that does not verify must not be told anything about verification; got $injectArgs",
    )
    repo.delete()
  }

  /**
   * Publishes the two stub plugins this test needs: the compose-preview plugin (prints a marker on
   * apply) and a fake `com.android.application` (the withPlugin host id that triggers auto-inject).
   * Same approach as the sibling reproducers, kept local so neither can break the other.
   */
  private fun publishStubPlugins(repo: File) {
    val build = tempDir("compose-preview-verification-stubs-")
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
      "preview",
      "ee.schimke.composeai.preview",
      "ee.schimke.composeai.preview",
      "cp",
      "PreviewPlugin",
      "COMPOSE-PREVIEW-APPLIED",
    )
    stub(
      "agp",
      "com.android.tools.build",
      "com.android.application",
      "agp",
      "FakeAgpPlugin",
      "FAKE-AGP-APPLIED",
    )

    GradleRunner.create().withProjectDir(build).withArguments("publish", "--stacktrace").build()
  }
}
