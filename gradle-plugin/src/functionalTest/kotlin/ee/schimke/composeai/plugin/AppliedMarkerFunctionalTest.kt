package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Exercises the `composePreviewApplied` task — the sidecar-JSON "applied"
 * marker consumed by the VS Code extension. Kept deliberately lean: the
 * task has no Compose or AGP dependencies, so the test project only needs
 * `kotlin("jvm")` + the plugin. Much faster than [DiscoveryFunctionalTest].
 */
class AppliedMarkerFunctionalTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class AppliedMarker(
        val schema: String,
        val pluginVersion: String,
        val modulePath: String,
        val moduleName: String,
    )

    private fun createBareProject(): File {
        val projectDir = tempDir.root

        File(projectDir, "settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories { gradlePluginPortal() }
            }
            rootProject.name = "marker-test"
            """.trimIndent(),
        )

        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm") version "2.2.21"
                id("ee.schimke.composeai.preview")
            }
            """.trimIndent(),
        )

        return projectDir
    }

    @Test
    fun `composePreviewApplied writes the sidecar JSON at a stable path`() {
        val projectDir = createBareProject()

        val result = GradleRunner.create()
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
        assertThat(parsed.moduleName).isEqualTo("marker-test")
        assertThat(parsed.pluginVersion).isNotEmpty()
    }

    @Test
    fun `composePreviewApplied is UP-TO-DATE on repeat runs`() {
        val projectDir = createBareProject()

        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("composePreviewApplied")
            .withPluginClasspath()
            .build()

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("composePreviewApplied")
            .withPluginClasspath()
            .build()

        assertThat(result.task(":composePreviewApplied")?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
    }
}
