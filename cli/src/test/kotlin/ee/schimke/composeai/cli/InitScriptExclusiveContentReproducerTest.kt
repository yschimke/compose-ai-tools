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
 * End-to-end reproducer for the Confetti shape (https://github.com/joreilly/Confetti `main`):
 * `pluginManagement.repositories` declares `exclusiveContent { ... }`, and Gradle 9.3+ then rejects
 * any project that *adds repositories to* `buildscript.repositories` with `When using exclusive
 * repository content in 'settings.pluginManagement.repositories', you cannot add repositories to
 * 'buildscript.repositories'.`
 *
 * Asserts that the rendered init script's `allprojects { buildscript { repositories { ... } } }`
 * sub-block is suppressed when the settings file matches this shape, so it never trips the
 * validation. For a module without its own `buildscript { repositories { ... } }`, the init script
 * does NOT add repositories or inject a raw coordinate (either would crash configuration); instead
 * it resolves the plugin classpath through the project's own settings-managed repositories via a
 * detached configuration and injects the resolved JARs as `files(...)` — landing the plugin on the
 * module's own buildscript classloader without touching `buildscript.repositories`. The `configures
 * cleanly` test pins the no-crash behavior; the `applies the plugin` test pins that the files()
 * path actually applies the plugin when the coordinate is resolvable.
 *
 * PR #1483 tried to dodge the validation with an `initscript { classpath ... }` load, but that
 * broke plugins-that-reference-AGP at runtime (`NoClassDefFoundError:
 * com/android/build/api/variant/AndroidComponentsExtension` — init-script-loaded plugins sit on a
 * sibling classloader of AGP). The current fix keeps the plugin on the project's OWN buildscript
 * classloader (via the resolved files()), which preserves AGP visibility.
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
    // whole Tooling API query (the 0.11.8 follow-up regression). The current fix neither adds
    // repos nor injects a raw coordinate for such modules: it resolves the plugin classpath via a
    // detached configuration and injects files(). Here the coordinate ("0.11.9") isn't published
    // to any repo the consumer declares, so resolution fails, is swallowed (runCatching), and the
    // branch degrades to a no-op — configuration still completes cleanly with no crash. The
    // `applies the plugin` test below covers the resolvable case.
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

  @Test
  fun `init script applies the plugin via detached-config files() in the exclusiveContent shape`() {
    // The Confetti :androidApp fix, end to end: in the exclusiveContent shape a module WITHOUT its
    // own buildscript repos can't add to buildscript.repositories, so the init script resolves the
    // plugin classpath through the project's settings-managed repos via a detached configuration
    // and injects files(). This proves that, when the coordinate IS resolvable, the plugin actually
    // applies to the module — not just that configuration doesn't crash. Two stub plugins are
    // published to a local maven repo: `ee.schimke.composeai.preview` (prints a marker on apply)
    // and a fake `com.android.application` (the withPlugin host id that triggers auto-inject).
    val repo = tempDir("compose-preview-repro-repo-")
    publishStubPlugins(repo)

    val root = tempDir()
    // Confetti shape: exclusiveContent shared into pluginManagement + DRM; the stub repo in both so
    // the plugins-DSL (com.android.application) AND the detached-config resolution find the stubs.
    // `:app` declares NO buildscript repositories of its own — the exact repo-less shape.
    File(root, "settings.gradle.kts")
      .writeText(
        """
        pluginManagement {
            repositories {
                maven { url = uri("${repo.toURI()}") }
                mavenCentral()
                exclusiveContent {
                    forRepository { maven { url = uri("https://example.com/m2") } }
                    filter { includeVersionByRegex("com.example.snap", ".*", ".*SNAPSHOT.*") }
                }
                gradlePluginPortal()
            }
        }
        dependencyResolutionManagement {
            repositories {
                maven { url = uri("${repo.toURI()}") }
                exclusiveContent {
                    forRepository { maven { url = uri("https://example.com/m2") } }
                    filter { includeVersionByRegex("com.example.snap", ".*", ".*SNAPSHOT.*") }
                }
            }
        }
        rootProject.name = "confetti-repro-apply"
        include(":app")
        """
          .trimIndent()
      )
    File(root, "build.gradle.kts").writeText("// root build script — intentionally empty\n")
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

    assertFalse(
      result.output.contains("exclusive repository content"),
      "init script tripped the exclusiveContent validation; full output:\n${result.output}",
    )
    assertEquals(
      TaskOutcome.SUCCESS,
      result.task(":app:help")?.outcome,
      "expected :app:help to succeed; got:\n${result.output}",
    )
    assertTrue(
      result.output.contains("COMPOSE-PREVIEW-APPLIED to :app"),
      "expected the compose-preview plugin to be auto-injected and applied to :app via the " +
        "detached-config files() path; full output:\n${result.output}",
    )
  }

  /**
   * Publishes two stub Gradle plugins to [repo] via a nested TestKit build: the compose-preview
   * plugin (id `ee.schimke.composeai.preview`, prints a marker on apply) and a fake
   * `com.android.application` (the withPlugin host id that triggers auto-inject). Kept as separate
   * subprojects so distinct implementation artifacts are published — resolving the compose-preview
   * marker must not drag in the fake-AGP descriptor.
   */
  private fun publishStubPlugins(repo: File) {
    val build = tempDir("compose-preview-repro-stubs-")
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
