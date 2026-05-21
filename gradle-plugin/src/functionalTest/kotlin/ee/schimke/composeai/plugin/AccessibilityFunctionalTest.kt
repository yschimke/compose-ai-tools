package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.discovery.*
import java.io.File
import kotlinx.serialization.json.Json
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Functional coverage for the per-extension-reports manifest pointer on the CMP / desktop path.
 *
 * The gradle-driven render path no longer produces a11y data products — that responsibility moved
 * entirely to the daemon (`:daemon:android`'s `RenderEngine`). Whether or not a build script / CLI
 * invocation requests a11y, the standalone `discoverPreviews` task writes an empty
 * `dataExtensionReports` map. The CLI / VS Code's "show a11y findings" surface routes through the
 * daemon and stamps a runtime pointer when there is data to point at.
 */
class AccessibilityFunctionalTest {

  @get:Rule val tempDir = TemporaryFolder()

  private val json = Json { ignoreUnknownKeys = true }

  private fun createTestProject(): File {
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
        rootProject.name = "test-a11y-wiring"
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

    File(projectDir, "gradle.properties").writeText("org.gradle.configuration-cache=true\n")

    val srcDir = File(projectDir, "src/main/kotlin/test")
    srcDir.mkdirs()
    File(srcDir, "Foo.kt")
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
        fun FooPreview() {
            Box(modifier = Modifier.size(100.dp).background(Color.Red))
        }
        """
          .trimIndent()
      )

    return projectDir
  }

  private fun File.readManifest(): PreviewManifest {
    val manifestFile = resolve("build/compose-previews/previews.json")
    assertThat(manifestFile.exists()).isTrue()
    return json.decodeFromString(PreviewManifest.serializer(), manifestFile.readText())
  }

  @Test
  fun `dataExtensionReports is empty on a stock invocation`() {
    val projectDir = createTestProject()

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("discoverPreviews")
        .withPluginClasspath()
        .build()

    assertThat(result.task(":discoverPreviews")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val manifest = projectDir.readManifest()
    assertThat(manifest.previews).hasSize(1)
    // No gradle-driven extension writes into this map any more — daemon-only.
    assertThat(manifest.dataExtensionReports).isEmpty()
  }

  @Test
  fun `dataExtensionReports stays empty even with a stale a11y opt-in property`() {
    // Older invocations may still pass `-PcomposePreview.previewExtensions.a11y.enableAllChecks
    // =true` (or the never-shipped `-PcomposePreview.activeExtensions=a11y` variant). Both are
    // now ignored by the gradle plugin — a11y is daemon-only. The discover task must not crash
    // and must not synthesise a pointer.
    val projectDir = createTestProject()

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments(
          "discoverPreviews",
          "-PcomposePreview.previewExtensions.a11y.enableAllChecks=true",
          "-PcomposePreview.activeExtensions=a11y",
        )
        .withPluginClasspath()
        .build()

    assertThat(result.task(":discoverPreviews")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    val manifest = projectDir.readManifest()
    assertThat(manifest.previews).hasSize(1)
    assertThat(manifest.dataExtensionReports).isEmpty()
  }
}
