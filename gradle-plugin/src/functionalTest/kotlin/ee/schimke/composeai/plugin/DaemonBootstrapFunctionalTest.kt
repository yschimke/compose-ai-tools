package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DaemonBootstrapFunctionalTest {

  @get:Rule val tempDir = TemporaryFolder()

  /**
   * `backgroundSandboxBoot` only does anything if it reaches the descriptor's `systemProperties` —
   * that map is the daemon JVM's whole view of the config, and `.github/ci/daemon-roundtrip.py`
   * reads the same key to size its `initialize` budget. An extension property that stops short of
   * it is silently inert: the build script looks configured, the daemon still boots the entire
   * eager pool, and nothing anywhere fails.
   *
   * Asserted here rather than in a `ProjectBuilder` unit test on purpose — querying
   * `systemProperties` resolves the `composePreviewDesktopDaemon` configuration, which needs a real
   * build with repositories. Only a real `composePreviewDaemonStart` proves the wiring end to end.
   */
  @Test
  fun `backgroundSandboxBoot opt-out in the daemon block reaches the descriptor`() {
    val projectDir =
      createCmpTestProject(
        extraBuildScript =
          """
          composePreview {
              daemon {
                  backgroundSandboxBoot = false
              }
          }
          """
            .trimIndent()
      )

    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments("composePreviewDaemonStart", "--stacktrace")
      .withPluginClasspath()
      .build()

    val descriptor = File(projectDir, "build/compose-previews/daemon-launch.json").readText()
    assertThat(backgroundSandboxBootIn(descriptor)).isEqualTo("false")
  }

  @Test
  fun `backgroundSandboxBoot defaults to true in the descriptor`() {
    // Default-on has to hold at the descriptor, not just the extension: this is what every plugin
    // consumer gets, and the descriptor is the daemon JVM's only view of it.
    val projectDir = createCmpTestProject()

    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments("composePreviewDaemonStart", "--stacktrace")
      .withPluginClasspath()
      .build()

    val descriptor = File(projectDir, "build/compose-previews/daemon-launch.json").readText()
    assertThat(backgroundSandboxBootIn(descriptor)).isEqualTo("true")
  }

  /**
   * Pulls the flag's value out of the emitted descriptor without pinning its JSON formatting — the
   * descriptor is written pretty-printed (`"key": "value"`), and an assertion that hard-codes the
   * spacing fails for a reason that has nothing to do with the wiring under test.
   */
  private fun backgroundSandboxBootIn(descriptorJson: String): String? =
    Regex("\"composeai\\.daemon\\.backgroundSandboxBoot\"\\s*:\\s*\"([^\"]*)\"")
      .find(descriptorJson)
      ?.groupValues
      ?.get(1)

  @Test
  fun `composePreviewDaemonStart is cached after source edit`() {
    val projectDir = createCmpTestProject()

    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments("composePreviewDaemonStart", "--build-cache", "--stacktrace")
      .withPluginClasspath()
      .build()

    File(projectDir, "src/main/kotlin/test/Previews.kt")
      .appendText(
        """

        internal const val EditedAfterDaemonBootstrap = "changed"
        """
          .trimIndent()
      )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDaemonStart", "--build-cache", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewDaemonStart")?.outcome)
      .isIn(listOf(TaskOutcome.UP_TO_DATE, TaskOutcome.FROM_CACHE))
  }

  @Test
  fun `discover and daemonStart in one invocation pass validation, discover first`() {
    // `previewsManifest` is an @InputFile pointing at composePreviewDiscover's `previews.json`, but
    // it's wired from a layout directory Provider that carries no build dependency. Gradle's strict
    // validation rejected the pair the moment both tasks were in one graph:
    //
    //   Task ':composePreviewDaemonStart' uses this output of task ':composePreviewDiscover'
    //   without declaring an explicit or implicit dependency.
    //
    // Only reproducible with both tasks requested together, which is why it survived until a
    // combined invocation happened to be run by hand.
    val projectDir = createCmpTestProject()

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDiscover", "composePreviewDaemonStart", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewDaemonStart")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val executed = result.tasks.map { it.path }
    assertThat(executed).containsAtLeast(":composePreviewDiscover", ":composePreviewDaemonStart")
    assertThat(executed.indexOf(":composePreviewDiscover"))
      .isLessThan(executed.indexOf(":composePreviewDaemonStart"))
  }

  @Test
  fun `daemonStart alone does not drag in discovery`() {
    // Guards the choice of `mustRunAfter` over `dependsOn`. The daemon has to be warmable before
    // anything has been discovered — that's why `previewsManifest` is @Optional (see its kdoc, and
    // DaemonMain's `manifestFile.isFile` check). "Fixing" the validation error with `dependsOn`
    // would satisfy Gradle and silently make every VS Code warm pay for a full discovery pass,
    // deleting the fresh-module path the optionality exists to serve. This fails if anyone does.
    val projectDir = createCmpTestProject()

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewDaemonStart", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewDaemonStart")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(result.task(":composePreviewDiscover")).isNull()
  }

  private fun createCmpTestProject(extraBuildScript: String = ""): File {
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
        rootProject.name = "daemon-bootstrap-test"
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
        $extraBuildScript
        """
          .trimIndent()
      )

    val daemonDesktopDir = File(projectDir, "daemon/desktop")
    daemonDesktopDir.mkdirs()
    File(daemonDesktopDir, "build.gradle.kts")
      .writeText(
        """
        plugins {
            java
        }
        """
          .trimIndent()
      )

    File(projectDir, "gradle.properties")
      .writeText(
        """
        org.gradle.configuration-cache=true
        """
          .trimIndent()
      )

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
