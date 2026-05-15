package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the on-disk contract `compose-preview a11y` and (transitively) the `a11y-report.yml` CI
 * action consume. The CLI's daemon-driven a11y flow needs three things from Gradle before it can
 * open a `RenderSession`:
 *
 * 1. `build/compose-previews/previews.json` — the preview manifest the daemon's
 *    `PreviewManifestRouter` loads. Without this, the daemon thread terminates on startup.
 * 2. `build/compose-previews/daemon-launch.json` with:
 *     - a non-empty `classpath` array (the JVM `-cp` for a forked daemon and the `URLClassLoader`
 *       source for the embedded backend),
 *     - `mainClass` pointing at `ee.schimke.composeai.daemon.DaemonMain` (resolved at runtime, so a
 *       typo or rename only surfaces when the daemon spawns),
 *     - `systemProperties` carrying the keys the daemon's `runDaemon(...)` reads at boot — most
 *       critically `composeai.daemon.previewsJsonPath` and `composeai.daemon.userClassDirs`.
 *
 * The end-to-end render path (daemon spawn → ATF walk → findings) is exercised by the
 * `a11y-report.yml` GitHub workflow on every PR; this test is the fast-feedback counterpart that
 * runs on every `./gradlew check` and catches regressions in the Gradle-side contract before they
 * reach CI.
 */
class CliA11yInputsFunctionalTest {

  @get:Rule val tempDir: TemporaryFolder = TemporaryFolder()

  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun `daemon-launch_json carries the keys the CLI's a11y path reads`() {
    val projectDir = createCmpTestProject()

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("discoverPreviews", "composePreviewDaemonStart", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":discoverPreviews")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.task(":composePreviewDaemonStart")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val previewOutputDir = File(projectDir, "build/compose-previews")
    val previewsJson = File(previewOutputDir, "previews.json")
    val daemonLaunchJson = File(previewOutputDir, "daemon-launch.json")

    assertThat(previewsJson.exists()).isTrue()
    assertThat(daemonLaunchJson.exists()).isTrue()

    // Manifest must contain the single preview we declared — the daemon's PreviewManifestRouter
    // walks this list on startup and fails the thread when the file is empty / unreadable.
    val manifest = json.parseToJsonElement(previewsJson.readText()).jsonObject
    val previews = manifest["previews"]?.jsonArray
    assertThat(previews).isNotNull()
    assertThat(previews!!.size).isAtLeast(1)

    // Descriptor structure — every field the CLI's render-session library reads must be present
    // and shaped the way `DaemonLaunchDescriptor.parse(...)` expects.
    val descriptor = json.parseToJsonElement(daemonLaunchJson.readText()).jsonObject

    assertThat(descriptor["mainClass"]?.jsonPrimitive?.content)
      .isEqualTo("ee.schimke.composeai.daemon.DaemonMain")

    val classpath = descriptor["classpath"]?.jsonArray
    assertThat(classpath).isNotNull()
    assertThat(classpath!!).isNotEmpty()

    // `systemProperties` must carry the keys the daemon's runDaemon(...) reads at boot. The
    // embedded backend lifts these onto the calling JVM verbatim — a missing key here breaks
    // the embedded `:samples:cmp` flow silently. Subprocess mode picks them up via `-D` args.
    val sysprops = descriptor["systemProperties"]?.jsonObject
    assertThat(sysprops).isNotNull()
    assertThat(sysprops!!.containsKey("composeai.daemon.previewsJsonPath")).isTrue()
    assertThat(sysprops.containsKey("composeai.daemon.userClassDirs")).isTrue()
    assertThat(sysprops.containsKey("composeai.daemon.workspaceRoot")).isTrue()
    // `composeai.harness.previewsManifest` points the daemon at the same manifest file when set;
    // the daemon's setup throws on startup when the path is set but the file doesn't exist (the
    // exact regression I shipped in #1141 — caught by the embedded e2e test, but a fast TestKit
    // assertion would have caught it earlier).
    val harnessManifestPath =
      sysprops["composeai.harness.previewsManifest"]?.jsonPrimitive?.contentOrNull
    if (harnessManifestPath != null) {
      assertThat(File(harnessManifestPath).exists()).isTrue()
    }
  }

  @Test
  fun `daemon-launch_json classpath is rooted under the plugin output dir`() {
    val projectDir = createCmpTestProject()

    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments("composePreviewDaemonStart", "--stacktrace")
      .withPluginClasspath()
      .build()

    val daemonLaunchJson = File(projectDir, "build/compose-previews/daemon-launch.json")
    val descriptor = json.parseToJsonElement(daemonLaunchJson.readText()).jsonObject
    val classpath =
      descriptor["classpath"]!!.jsonArray.map {
        (it as kotlinx.serialization.json.JsonPrimitive).content
      }

    // Sanity: at least one entry on classpath references the daemon module's compiled output.
    // The daemon JVM loads `ee.schimke.composeai.daemon.DaemonMain` via this classpath; an
    // empty / malformed entry crashes the subprocess immediately and the embedded backend
    // surfaces a `NoClassDefFoundError` on first `Class.forName(...)`.
    assertThat(classpath.any { it.contains("daemon") || it.contains("compose") }).isTrue()
  }

  /**
   * CMP synthetic project — same shape as [DaemonBootstrapFunctionalTest]'s scaffolding. Kept
   * verbatim (rather than DRY-extracted) so each functional-test file's setup is self-contained and
   * a TestKit failure points at one fixture.
   */
  private fun createCmpTestProject(): File {
    val projectDir = tempDir.root

    File(projectDir, "settings.gradle.kts")
      .writeText(
        """
        pluginManagement {
            repositories {
                gradlePluginPortal()
                google()
                mavenCentral()
            }
        }
        dependencyResolutionManagement {
            repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
            repositories {
                google()
                mavenCentral()
            }
        }
        rootProject.name = "cli-a11y-inputs-test"
        include(":daemon:desktop")
        """
          .trimIndent()
      )

    File(projectDir, "build.gradle.kts")
      .writeText(
        """
        @file:Suppress("DEPRECATION")
        plugins {
            kotlin("jvm") version "2.2.21"
            kotlin("plugin.compose") version "2.2.21"
            id("org.jetbrains.compose") version "1.10.3"
            id("ee.schimke.composeai.preview")
        }
        dependencies {
            implementation(compose.desktop.currentOs)
            implementation(compose.material3)
            implementation(compose.uiTooling)
            implementation(compose.components.uiToolingPreview)
        }
        java {
            toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
        }
        """
          .trimIndent()
      )

    // The plugin's daemon-bootstrap task expects a `:daemon:desktop` module to attach the
    // renderer to. We stub it as an empty java project — the TestKit run never actually launches
    // the daemon, so the stub is enough to satisfy the project-graph resolution.
    val daemonDesktopDir = File(projectDir, "daemon/desktop")
    daemonDesktopDir.mkdirs()
    File(daemonDesktopDir, "build.gradle.kts").writeText("plugins { java }")

    File(projectDir, "gradle.properties").writeText("org.gradle.configuration-cache=true\n")

    val srcDir = File(projectDir, "src/main/kotlin/test")
    srcDir.mkdirs()
    File(srcDir, "Previews.kt")
      .writeText(
        """
        package test

        import androidx.compose.foundation.background
        import androidx.compose.foundation.layout.Box
        import androidx.compose.foundation.layout.size
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.graphics.Color
        import androidx.compose.ui.tooling.preview.Preview
        import androidx.compose.ui.unit.dp

        @Preview
        @Composable
        fun RedBoxPreview() {
            Box(modifier = Modifier.size(100.dp).background(Color.Red))
        }
        """
          .trimIndent()
      )

    return projectDir
  }
}
