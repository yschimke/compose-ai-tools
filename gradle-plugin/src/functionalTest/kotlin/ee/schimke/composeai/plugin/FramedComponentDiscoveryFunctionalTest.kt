package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.discovery.ComponentRecordFile
import java.io.File
import kotlinx.serialization.json.Json
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A component the preview reaches through the project's own composables is still the preview's.
 *
 * `PreviewTargetInference.inferComponents` read the preview body plus one hop through Compose
 * singleton lambdas and kept only calls into a component library. Anything behind a project
 * composable was invisible, and catalogs factor their stickers: m3-catalog's record carried
 * `DateRangePicker` and neither `TimePicker` nor `DatePicker`, and wear-m3-catalog's
 * `:remote-catalog` collapsed 49 catalog entries into the two sticker composables wrapping them.
 *
 * This builds a real project in each of the shapes that matters and reads the written
 * `components.json`, because the claim is about bytecode: an in-process assertion about the walker
 * would not have caught that the one-hop rule was the whole story.
 *
 * **The bound is asserted too.** A walk with no depth limit would pass every positive case here
 * while making discovery's cost a function of how deeply a project factors its UI, so the test
 * names a component too deep to claim and insists it is absent. Without that this test would pass
 * just as well against an unbounded walk, which is not the change that was made.
 */
class FramedComponentDiscoveryFunctionalTest {

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
        rootProject.name = "test-framed-components"
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
    // Four shapes, written the way a catalog writes them rather than for this test:
    //   Direct    — the component in the preview body, which always worked.
    //   OneFrame  — `Sticker { Frame { Component() } }`, the m3-catalog picker shape.
    //   TwoFrames — a frame wrapping a frame, which a catalog with a themed frame reaches.
    //   TooDeep   — one level past the bound, which must NOT be claimed.
    File(srcDir, "Framed.kt")
      .writeText(
        """
        package test

        import androidx.compose.material3.Button
        import androidx.compose.material3.Card
        import androidx.compose.material3.Checkbox
        import androidx.compose.material3.Slider
        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.tooling.preview.Preview

        @Composable
        fun Sticker(content: @Composable () -> Unit) { content() }

        @Composable
        fun ButtonFrame() { Button(onClick = {}) { Text("ok") } }

        @Composable
        fun CardFrame() { Card { Text("card") } }

        @Composable
        fun OuterFrame() { CheckboxFrame() }

        @Composable
        fun CheckboxFrame() { Checkbox(checked = true, onCheckedChange = null) }

        @Composable
        fun DeepA() { DeepB() }

        @Composable
        fun DeepB() { DeepC() }

        @Composable
        fun DeepC() { DeepD() }

        @Composable
        fun DeepD() { Slider(value = 0.5f, onValueChange = {}) }

        @Preview
        @Composable
        fun DirectPreview() { Text("direct") }

        @Preview
        @Composable
        fun OneFramePreview() { Sticker { ButtonFrame() } }

        @Preview
        @Composable
        fun TwoFramesPreview() { Sticker { OuterFrame() } }

        @Preview
        @Composable
        fun TooDeepPreview() { Sticker { DeepA() } }
        """
          .trimIndent()
      )
    return projectDir
  }

  private fun runGradle(projectDir: File, vararg arguments: String) =
    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments(*arguments)
      .withPluginClasspath()
      .build()

  @Test
  fun `a component behind the project's own composables is discovered, up to the bound`() {
    val projectDir = createTestProject()
    val discover = runGradle(projectDir, "composePreviewDiscover")
    assertThat(discover.task(":composePreviewDiscover")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

    val componentsFile = File(projectDir, "build/compose-previews/components.json")
    assertThat(componentsFile.exists()).isTrue()
    val record = json.decodeFromString(ComponentRecordFile.serializer(), componentsFile.readText())
    val symbols = record.components.map { it.symbol.name }.toSet()

    // The vacuity guard: a walker that found nothing would satisfy every "is absent" claim below.
    assertThat(symbols).contains("Text")

    // One frame — the shape m3-catalog's pickers are in, and every component wear-m3-catalog's
    // `:remote-catalog` publishes.
    assertThat(symbols).contains("Button")
    // Two frames — a frame wrapping a frame.
    assertThat(symbols).contains("Checkbox")

    // And the bound. `Slider` sits four project composables deep; claiming it would make this a
    // walk over the project's whole call graph rather than a rule about stickers.
    assertThat(symbols).doesNotContain("Slider")
  }
}
