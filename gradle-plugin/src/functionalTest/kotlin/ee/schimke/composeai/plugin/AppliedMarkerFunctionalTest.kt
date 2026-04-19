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

        // Configuration cache + Isolated Projects on by default — every
        // assertion in this file is implicitly an IP/CC-safety assertion.
        File(projectDir, "gradle.properties").writeText(
            """
            org.gradle.configuration-cache=true
            org.gradle.unsafe.isolated-projects=true
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

    @Test
    fun `configuration cache is reused across runs`() {
        val projectDir = createBareProject()

        // First run stores the CC entry. `--info` surfaces the
        // "Configuration cache entry stored" / "reused" messages we want to
        // assert on — Gradle itself doesn't fail the build on an IP/CC
        // violation at this level, so we verify by output inspection.
        val first = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("composePreviewApplied", "--info")
            .withPluginClasspath()
            .build()
        assertThat(first.output).contains("Configuration cache entry stored")

        val second = GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("composePreviewApplied", "--info")
            .withPluginClasspath()
            .build()
        // "reused" is the string Gradle prints when the cache hit is clean.
        // Anything that violated CC invariants during config would have
        // caused "discarded" or an outright failure — both of which we'd
        // catch here.
        assertThat(second.output).contains("Reusing configuration cache")
    }
}
