package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Real-Gradle proof that applying ONLY the configuration-only plugin
 * (`ee.schimke.composeai.preview.config`) makes a module discoverable via the
 * `composePreviewApplied` marker — with no rendering runtime on the build, and under configuration
 * cache + Isolated Projects.
 *
 * The `java-gradle-plugin` plugin wires `withPluginClasspath()` onto the `test` task automatically,
 * so this lives in the normal test sourceset. The marker's `pluginVersion` is read from the baked
 * `config-plugin-version.properties` resource at task-configuration time — so a green run here also
 * proves that resource is generated and on the classpath.
 */
class ComposePreviewConfigPluginFunctionalTest {

  @get:Rule val tempDir = TemporaryFolder()

  private val json = Json { ignoreUnknownKeys = true }

  @Serializable
  private data class AppliedMarker(
    val schema: String,
    val pluginVersion: String,
    val modulePath: String,
    val moduleName: String,
  )

  private fun createConfigOnlyProject(): File {
    val projectDir = tempDir.root

    File(projectDir, "settings.gradle.kts")
      .writeText(
        """
        pluginManagement {
            repositories { gradlePluginPortal() }
        }
        rootProject.name = "config-only-test"
        """
          .trimIndent()
      )

    // Note: NO `kotlin("jvm")`, NO Android, NO Compose plugin — the config-only plugin must stand
    // entirely on its own. A bare build that only configures previews must not require any of the
    // rendering runtime to be present.
    File(projectDir, "build.gradle.kts")
      .writeText(
        """
        plugins {
            id("ee.schimke.composeai.preview.config")
        }

        composePreview {
            variant.set("release")
        }
        """
          .trimIndent()
      )

    // Configuration cache + Isolated Projects on by default — the config plugin must be clean under
    // both (it does no cross-project configuration, unlike the auto-inject init script).
    File(projectDir, "gradle.properties")
      .writeText(
        """
        org.gradle.configuration-cache=true
        org.gradle.unsafe.isolated-projects=true
        """
          .trimIndent()
      )

    return projectDir
  }

  @Test
  fun `config-only plugin writes the applied marker on a runtime-free build`() {
    val projectDir = createConfigOnlyProject()

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewApplied", "--stacktrace")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":composePreviewApplied")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val marker = File(projectDir, "build/compose-previews/applied.json")
    assertThat(marker.exists()).isTrue()

    val parsed = json.decodeFromString<AppliedMarker>(marker.readText())
    assertThat(parsed.schema).isEqualTo("compose-preview-applied/v1")
    assertThat(parsed.modulePath).isEqualTo(":")
    assertThat(parsed.moduleName).isEqualTo("config-only-test")
    assertThat(parsed.pluginVersion).isNotEmpty()
  }

  @Test
  fun `config-only plugin does not register render tasks`() {
    val projectDir = createConfigOnlyProject()

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("tasks", "--all")
        .withPluginClasspath()
        .build()

    assertThat(result.output).contains("composePreviewApplied")
    // The rendering runtime is the CLI-injected plugin's job; the config-only plugin must never
    // surface render/discovery tasks.
    assertThat(result.output).doesNotContain("composePreviewRender")
    assertThat(result.output).doesNotContain("composePreviewDiscover")
  }
}
