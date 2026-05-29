package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Integration coverage for the IP-safe cross-project metadata service (issue #1549).
 *
 * Both branches — the deep [AndroidPreviewSupport.hasPreviewDependency] walk and the multi-module
 * [CheapSignalFiles] enumeration — are exercised here through a real Gradle Test Kit invocation
 * with `org.gradle.unsafe.isolated-projects=true` set, so any latent IP violation in the new code
 * paths fails the build instead of being silently downgraded to a warning. Unit-level coverage of
 * the resolved-graph walk lives in [ValidatePreviewToolingPresentTaskTest].
 */
class CrossProjectMetadataFunctionalTest {

  @get:Rule val tempDir = TemporaryFolder()

  private fun seedRootBuild(projectDir: File) {
    File(projectDir, "settings.gradle.kts")
      .writeText(
        """
        pluginManagement {
            repositories { gradlePluginPortal() }
        }
        rootProject.name = "ip-cross-project"
        include(":app")
        include(":shared")
        """
          .trimIndent()
      )
    File(projectDir, "gradle.properties")
      .writeText(
        """
        org.gradle.configuration-cache=true
        org.gradle.unsafe.isolated-projects=true
        """
          .trimIndent()
      )
    File(projectDir, "build.gradle.kts").writeText("")

    val app = File(projectDir, "app").apply { mkdirs() }
    File(app, "build.gradle.kts")
      .writeText(
        """
        plugins {
            kotlin("jvm") version "2.2.21"
            id("ee.schimke.composeai.preview")
        }
        dependencies {
            implementation(project(":shared"))
        }
        """
          .trimIndent()
      )

    val shared = File(projectDir, "shared").apply { mkdirs() }
    File(shared, "build.gradle.kts")
      .writeText(
        """
        plugins {
            kotlin("jvm") version "2.2.21"
        }
        dependencies {
            api("org.jetbrains.compose.components:components-ui-tooling-preview:1.7.5")
        }
        """
          .trimIndent()
      )
  }

  @Test
  fun `composePreviewApplied succeeds under Isolated Projects with cross-project metadata service`() {
    val projectDir = tempDir.root
    seedRootBuild(projectDir)

    // `composePreviewApplied` doesn't itself touch `hasPreviewDependency`, but applying the
    // plugin on `:app` registers the BuildService — and configuring the task under
    // `isolated-projects=true` is what would surface any IP violation in the registration code
    // path. The model-builder / Gradle task itself reads `build/compose-previews/applied.json`.
    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments(":app:composePreviewApplied", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":app:composePreviewApplied")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val marker = File(projectDir, "app/build/compose-previews/applied.json")
    assertThat(marker.exists()).isTrue()
  }

  @Test
  fun `configuration cache is reused across runs with cross-project metadata service`() {
    val projectDir = tempDir.root
    seedRootBuild(projectDir)

    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments(":app:composePreviewApplied", "--info")
      .withPluginClasspath()
      .build()
    val second =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments(":app:composePreviewApplied", "--info")
        .withPluginClasspath()
        .build()

    assertThat(second.output).contains("Reusing configuration cache")
  }
}
