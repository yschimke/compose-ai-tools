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
 * Functional coverage for the accessibility data-product manifest wiring on the CMP / desktop path.
 *
 * Exercises the **config-time** half of the contract: when the consumer enables a11y via either
 * `composePreview { previewExtensions { a11y { enableAllChecks() } } }` or the
 * `-PcomposePreview.previewExtensions.a11y.enableAllChecks=true` Gradle property,
 * `discoverPreviews` writes `PreviewManifest.accessibilityReport = "accessibility.json"` so
 * downstream tools (CLI's `A11yCommand`, VS Code extension, MCP) know the module participates in
 * accessibility output. With the feature off, the pointer stays `null`.
 *
 * The renderer-side half — `AccessibilityChecker.analyze` running under Robolectric and producing
 * `a11y-atf.json` / `a11y-hierarchy.json` / `a11y-touchTargets.json` / `a11y-overlay.png` — needs
 * an Android + AGP + Robolectric synthetic project, which the functional-test classpath doesn't
 * currently scaffold. Filed as issue #733; that work pairs with this PR.
 */
class AccessibilityFunctionalTest {

  @get:Rule val tempDir = TemporaryFolder()

  private val json = Json { ignoreUnknownKeys = true }

  /**
   * Spins up a JVM Compose-Multiplatform synthetic project — same shape as [RenderFunctionalTest] —
   * with a single trivial `@Preview` so discovery has something to report. The
   * [composePreviewBlock] is interpolated verbatim inside the `composePreview { … }` extension
   * configuration to flip a11y on/off per test case.
   */
  private fun createTestProject(composePreviewBlock: String = ""): File {
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
        composePreview {
            $composePreviewBlock
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
  fun `default extension config leaves accessibilityReport null`() {
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
    assertThat(manifest.accessibilityReport).isNull()
  }

  @Test
  fun `enableAllChecks via DSL populates the accessibilityReport pointer`() {
    val projectDir =
      createTestProject(composePreviewBlock = "previewExtensions { a11y { enableAllChecks() } }")

    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments("discoverPreviews")
      .withPluginClasspath()
      .build()

    val manifest = projectDir.readManifest()
    // The plugin pins the relative pointer to a fixed filename; the absolute
    // path is resolved against the manifest's parent dir at consumer-side
    // (CLI / VS Code extension) so it tolerates `./gradlew clean`.
    assertThat(manifest.accessibilityReport).isEqualTo("accessibility.json")
  }

  @Test
  fun `enableAllChecks via Gradle property populates the accessibilityReport pointer`() {
    val projectDir = createTestProject()

    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments(
        "discoverPreviews",
        "-PcomposePreview.previewExtensions.a11y.enableAllChecks=true",
      )
      .withPluginClasspath()
      .build()

    val manifest = projectDir.readManifest()
    assertThat(manifest.accessibilityReport).isEqualTo("accessibility.json")
  }

  @Test
  fun `selecting a single a11y check via Gradle property populates the pointer`() {
    // `-PcomposePreview.previewExtensions.a11y.checks=atf` is the targeted-checks selector;
    // it should flip the manifest pointer the same way `enableAllChecks` does — the manifest
    // pointer is the binary "is the feature on?" gate, finer-grained selection happens later.
    val projectDir = createTestProject()

    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments("discoverPreviews", "-PcomposePreview.previewExtensions.a11y.checks=atf")
      .withPluginClasspath()
      .build()

    val manifest = projectDir.readManifest()
    assertThat(manifest.accessibilityReport).isEqualTo("accessibility.json")
  }

  @Test
  fun `unknown a11y check id leaves accessibilityReport null`() {
    // Defensive: typo'd check ids (`-Pcompose…a11y.checks=atfffff`) shouldn't silently flip the
    // feature on. Keeps the gate honest — only recognised check ids count.
    val projectDir = createTestProject()

    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments("discoverPreviews", "-PcomposePreview.previewExtensions.a11y.checks=atfffff")
      .withPluginClasspath()
      .build()

    val manifest = projectDir.readManifest()
    assertThat(manifest.accessibilityReport).isNull()
  }
}
