package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.discovery.*
import java.io.File
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Functional coverage for `composePreviewRender` / `composePreviewRenderAll` wiring against a
 * synthetic Compose Desktop project. Real end-to-end rendering (the actual PNG produced by
 * `DesktopRendererMain`) is covered by the in-repo samples — `:samples:cmp:composePreviewRenderAll`
 * is the source-of-truth render smoke test — so these tests stay focused on the parts that don't
 * require resolving the published `ee.schimke.composeai:renderer-desktop` AAR through Maven Central
 * (which the synthetic-tempdir project can't see at functional-test time).
 */
class RenderFunctionalTest {

  @get:Rule val tempDir = TemporaryFolder()

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

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
        rootProject.name = "test-render"
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
    File(srcDir, "ColorBoxes.kt")
      .writeText(
        """
        package test

        import androidx.compose.ui.tooling.preview.Preview
        import androidx.compose.foundation.background
        import androidx.compose.foundation.layout.Box
        import androidx.compose.foundation.layout.size
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.graphics.Color
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

  @Test
  fun `composePreviewRenderAll fails loudly when render produces no PNGs for a non-empty manifest`() {
    val projectDir = createTestProject()

    // Discover first so `previews.json` exists with real entries; that's
    // the precondition for the post-condition check. We run discovery
    // directly rather than going through composePreviewRenderAll so no PNGs
    // get produced as a side-effect.
    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments("composePreviewDiscover")
      .withPluginClasspath()
      .build()

    val manifest = File(projectDir, "build/compose-previews/previews.json")
    assertThat(manifest.exists()).isTrue()

    // Force the failure mode: invoke `composePreviewRenderAll` but exclude the
    // render task. This mirrors the real-world regression where
    // `composePreviewRender` silently becomes NO-SOURCE — the aggregate task
    // still fires but no PNGs land on disk.
    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewRenderAll", "-x", "composePreviewRender", "--stacktrace")
        .withPluginClasspath()
        .buildAndFail()

    assertThat(result.output).contains("render produced no output file")
    assertThat(result.output).contains("NO-SOURCE")
  }

  @Test
  fun `composePreviewRenderAll fails loudly when render produces no data product output`() {
    val projectDir = createTestProject()

    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments("composePreviewDiscover")
      .withPluginClasspath()
      .build()

    val manifestFile = File(projectDir, "build/compose-previews/previews.json")
    val manifest = json.decodeFromString<PreviewManifest>(manifestFile.readText())
    val preview = manifest.previews.single()
    val captureOutput = preview.captures.single().renderOutput
    File(projectDir, "build/compose-previews/$captureOutput").also {
      it.parentFile.mkdirs()
      it.writeBytes(byteArrayOf(1, 2, 3))
    }
    manifestFile.writeText(
      json.encodeToString(
        manifest.copy(
          previews =
            listOf(
              preview.copy(
                dataProducts =
                  listOf(
                    PreviewDataProduct(
                      kind = "render/scroll/long",
                      output = "data/render-scroll-long/${preview.id}.png",
                      cost = SCROLL_LONG_COST,
                    )
                  )
              )
            )
        )
      )
    )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("composePreviewRenderAll", "-x", "composePreviewRender", "--stacktrace")
        .withPluginClasspath()
        .buildAndFail()

    assertThat(result.output).contains("render produced no output file")
    assertThat(result.output).contains(preview.id)
  }

  @Test
  fun `composePreviewRenderAll missing-renders=warn does not fail the build`() {
    val projectDir = createTestProject()

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments(
          "composePreviewRenderAll",
          "-x",
          "composePreviewRender",
          "-PcomposePreview.missingRenders=warn",
          "--stacktrace",
        )
        .withPluginClasspath()
        .build()

    // The warn line carries the same diagnostic the throw used to ship —
    // so consumers grepping CI logs still see the missing-output signal,
    // they just don't get gated on it.
    assertThat(result.output).contains("missing-renders policy=warn")
    assertThat(result.output).contains("render produced no output file")
    // Validation marker is still written under warn so downstream tasks
    // that wire off the marker (pixel-test gates, baselines) keep running.
    assertThat(File(projectDir, "build/compose-previews/composePreviewRenderAll.validated"))
      .exists()
  }

  @Test
  fun `composePreviewRenderAll missing-renders=ignore stays silent`() {
    val projectDir = createTestProject()

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments(
          "composePreviewRenderAll",
          "-x",
          "composePreviewRender",
          "-PcomposePreview.missingRenders=ignore",
          "--stacktrace",
        )
        .withPluginClasspath()
        .build()

    // Ignore should not surface the "render produced no output file"
    // string anywhere in the log — the whole point of the policy is to
    // suppress the diagnostic for projects that accept some missing
    // previews as the steady state.
    assertThat(result.output).doesNotContain("render produced no output file")
    assertThat(File(projectDir, "build/compose-previews/composePreviewRenderAll.validated"))
      .exists()
  }

  @Test
  fun `composePreviewRenderAll missing-renders=garbage falls back to fail`() {
    val projectDir = createTestProject()

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments(
          "composePreviewRenderAll",
          "-x",
          "composePreviewRender",
          "-PcomposePreview.missingRenders=bogus",
          "--stacktrace",
        )
        .withPluginClasspath()
        .buildAndFail()

    // Typos must not silently widen the policy — anything we don't
    // recognise resolves to `fail` so the historical hard error still
    // catches whole-module classpath misconfig.
    assertThat(result.output).contains("render produced no output file")
  }
}
