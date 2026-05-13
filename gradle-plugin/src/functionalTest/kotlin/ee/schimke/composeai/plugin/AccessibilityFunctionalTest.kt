package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.serialization.json.Json
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Functional coverage for the accessibility manifest pointer on the CMP / desktop path. A11y is
 * always-on now (no DSL, no Gradle property, no opt-in), so every module's `previews.json` is
 * expected to carry `accessibilityReport = "accessibility.json"`.
 *
 * The renderer-side half — `AccessibilityChecker.analyze` running under Robolectric and producing
 * sidecar artefacts — needs an Android + AGP + Robolectric synthetic project; covered by
 * [AccessibilityAndroidFunctionalTest].
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
  fun `accessibilityReport pointer is always populated`() {
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
    // The plugin pins the relative pointer to a fixed filename; the absolute path is resolved
    // against the manifest's parent dir at consumer-side (CLI / VS Code extension) so it tolerates
    // `./gradlew clean`.
    assertThat(manifest.accessibilityReport).isEqualTo("accessibility.json")
  }
}
