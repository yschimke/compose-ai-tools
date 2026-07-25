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
