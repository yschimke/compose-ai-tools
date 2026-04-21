package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class ColorValidationTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private fun createColorProject(): File {
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
            rootProject.name = "test-colors"
            """.trimIndent()
        )

        // Uses Android-style @Preview with backgroundColor parameter
        // For CMP, we use a custom approach since the desktop @Preview doesn't have backgroundColor
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
                toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
            }
            """.trimIndent()
        )

        File(projectDir, "gradle.properties").writeText(
            "org.gradle.configuration-cache=true\n"
        )

        val srcDir = File(projectDir, "src/main/kotlin/test")
        srcDir.mkdirs()
        File(srcDir, "ColorPreviews.kt").writeText(
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

            @Preview(
                name = "Red",
                showBackground = true,
                backgroundColor = 0xFFFF0000,
                widthDp = 50,
                heightDp = 50,
            )
            @Composable
            fun RedPreview() {
                Box(modifier = Modifier.size(50.dp).background(Color.Red))
            }

            @Preview(
                name = "Blue",
                showBackground = true,
                backgroundColor = 0xFF0000FF,
                widthDp = 50,
                heightDp = 50,
            )
            @Composable
            fun BluePreview() {
                Box(modifier = Modifier.size(50.dp).background(Color.Blue))
            }

            @Preview(
                name = "Green",
                showBackground = true,
                backgroundColor = 0xFF00FF00,
                widthDp = 50,
                heightDp = 50,
            )
            @Composable
            fun GreenPreview() {
                Box(modifier = Modifier.size(50.dp).background(Color.Green))
            }
            """.trimIndent()
        )

        return projectDir
    }

    @Test
    fun `red preview renders with red background color`() {
        val projectDir = createColorProject()

        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("renderAllPreviews", "--stacktrace")
            .withPluginClasspath()
            .build()

        val manifest = json.decodeFromString<PreviewManifest>(
            File(projectDir, "build/compose-previews/previews.json").readText()
        )

        val redPreview = manifest.previews.find { it.params.name == "Red" }
        assertThat(redPreview).isNotNull()
        assertThat(redPreview!!.params.backgroundColor).isEqualTo(0xFFFF0000)

        val pngFile = File(
            projectDir,
            "build/compose-previews/${redPreview.captures.first().renderOutput}",
        )
        assertThat(pngFile.exists()).isTrue()

        val img: BufferedImage = ImageIO.read(pngFile)
        // Check center pixel is red (0xFFFF0000)
        val centerPixel = img.getRGB(img.width / 2, img.height / 2)
        assertThat(centerPixel).isEqualTo(0xFFFF0000.toInt())
    }

    @Test
    fun `blue preview renders with blue background color`() {
        val projectDir = createColorProject()

        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("renderAllPreviews")
            .withPluginClasspath()
            .build()

        val manifest = json.decodeFromString<PreviewManifest>(
            File(projectDir, "build/compose-previews/previews.json").readText()
        )

        val bluePreview = manifest.previews.find { it.params.name == "Blue" }
        assertThat(bluePreview).isNotNull()

        val pngFile = File(
            projectDir,
            "build/compose-previews/${bluePreview!!.captures.first().renderOutput}",
        )
        assertThat(pngFile.exists()).isTrue()

        val img: BufferedImage = ImageIO.read(pngFile)
        val centerPixel = img.getRGB(img.width / 2, img.height / 2)
        assertThat(centerPixel).isEqualTo(0xFF0000FF.toInt())
    }

    @Test
    fun `green preview renders with green background color`() {
        val projectDir = createColorProject()

        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("renderAllPreviews")
            .withPluginClasspath()
            .build()

        val manifest = json.decodeFromString<PreviewManifest>(
            File(projectDir, "build/compose-previews/previews.json").readText()
        )

        val greenPreview = manifest.previews.find { it.params.name == "Green" }
        assertThat(greenPreview).isNotNull()

        val pngFile = File(
            projectDir,
            "build/compose-previews/${greenPreview!!.captures.first().renderOutput}",
        )
        assertThat(pngFile.exists()).isTrue()

        val img: BufferedImage = ImageIO.read(pngFile)
        val centerPixel = img.getRGB(img.width / 2, img.height / 2)
        assertThat(centerPixel).isEqualTo(0xFF00FF00.toInt())
    }

    @Test
    fun `discovered previews have correct backgroundColor params`() {
        val projectDir = createColorProject()

        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("discoverPreviews")
            .withPluginClasspath()
            .build()

        val manifest = json.decodeFromString<PreviewManifest>(
            File(projectDir, "build/compose-previews/previews.json").readText()
        )

        assertThat(manifest.previews).hasSize(3)

        val colorMap = manifest.previews.associate { it.params.name to it.params.backgroundColor }
        assertThat(colorMap["Red"]).isEqualTo(0xFFFF0000)
        assertThat(colorMap["Blue"]).isEqualTo(0xFF0000FF)
        assertThat(colorMap["Green"]).isEqualTo(0xFF00FF00)
    }
}
