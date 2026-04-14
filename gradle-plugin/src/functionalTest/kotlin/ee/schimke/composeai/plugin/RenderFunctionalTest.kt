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
        // Default dimensions: 400x800 at 2x density = 800x1600
        assertThat(img.width).isEqualTo(800)
        assertThat(img.height).isEqualTo(1600)
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
