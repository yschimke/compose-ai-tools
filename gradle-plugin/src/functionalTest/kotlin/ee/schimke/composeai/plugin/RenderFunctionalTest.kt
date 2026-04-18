package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class RenderFunctionalTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private fun createTestProject(): File {
        val projectDir = tempDir.root

        File(projectDir, "settings.gradle.kts").writeText(
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
            """.trimIndent()
        )

        File(projectDir, "build.gradle.kts").writeText(
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
                toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
            }
            """.trimIndent()
        )

        File(projectDir, "gradle.properties").writeText(
            "org.gradle.configuration-cache=true\n"
        )

        val srcDir = File(projectDir, "src/main/kotlin/test")
        srcDir.mkdirs()
        File(srcDir, "ColorBoxes.kt").writeText(
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
            """.trimIndent()
        )

        return projectDir
    }

    @Test
    fun `renderAllPreviews produces PNG files`() {
        val projectDir = createTestProject()

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("renderAllPreviews", "--stacktrace")
            .withPluginClasspath()
            .build()

        assertThat(result.task(":renderPreviews")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

        val rendersDir = File(projectDir, "build/compose-previews/renders")
        assertThat(rendersDir.exists()).isTrue()

        val pngFiles = rendersDir.listFiles { f -> f.extension == "png" } ?: emptyArray()
        assertThat(pngFiles).isNotEmpty()
    }

    @Test
    fun `rendered PNG has correct dimensions`() {
        val projectDir = createTestProject()

        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("renderAllPreviews")
            .withPluginClasspath()
            .build()

        val rendersDir = File(projectDir, "build/compose-previews/renders")
        val pngFile = rendersDir.listFiles { f -> f.extension == "png" }?.firstOrNull()
        assertThat(pngFile).isNotNull()

        val img: BufferedImage = ImageIO.read(pngFile!!)
        // Default dimensions: 400x800 dp at DEFAULT_DENSITY (2.625x, matching the
        // xxhdpi-class default Android Studio uses for `@Preview` without a device)
        // = 1050x2100 px.
        assertThat(img.width).isEqualTo(1050)
        assertThat(img.height).isEqualTo(2100)
    }

    @Test
    fun `renderAllPreviews fails loudly when render produces no PNGs for a non-empty manifest`() {
        val projectDir = createTestProject()

        // Discover first so `previews.json` exists with real entries; that's
        // the precondition for the post-condition check. We run discovery
        // directly rather than going through renderAllPreviews so no PNGs
        // get produced as a side-effect.
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("discoverPreviews")
            .withPluginClasspath()
            .build()

        val manifest = File(projectDir, "build/compose-previews/previews.json")
        assertThat(manifest.exists()).isTrue()

        // Force the failure mode: invoke `renderAllPreviews` but exclude the
        // render task. This mirrors the real-world regression where
        // `renderPreviews` silently becomes NO-SOURCE — the aggregate task
        // still fires but no PNGs land on disk.
        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("renderAllPreviews", "-x", "renderPreviews", "--stacktrace")
            .withPluginClasspath()
            .buildAndFail()

        assertThat(result.output).contains("render produced no PNG")
        assertThat(result.output).contains("NO-SOURCE")
    }

    @Test
    fun `renderPreviews is UP-TO-DATE on second run`() {
        val projectDir = createTestProject()

        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("renderAllPreviews")
            .withPluginClasspath()
            .build()

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("renderAllPreviews")
            .withPluginClasspath()
            .build()

        assertThat(result.task(":renderPreviews")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
    }
}
