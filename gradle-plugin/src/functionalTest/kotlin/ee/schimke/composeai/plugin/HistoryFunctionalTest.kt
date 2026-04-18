package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.imageio.ImageIO

/**
 * End-to-end coverage for the preview history flow.
 *
 * Uses the CMP renderer (fast + deterministic, same setup as RenderFunctionalTest)
 * rather than the Android path, since the history task itself is backend-agnostic.
 */
class HistoryFunctionalTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private fun createProject(historyEnabled: Boolean): File {
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
            rootProject.name = "test-history"
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
            composePreview {
                historyEnabled.set($historyEnabled)
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
        writePreviewSource(srcDir, color = "Red")

        return projectDir
    }

    private fun writePreviewSource(srcDir: File, color: String) {
        // backgroundColor+showBackground fills the full preview so the PNG's centre
        // pixel tracks the source colour — lets us prove renders differ across edits.
        val hex = when (color) {
            "Red" -> "0xFFFF0000"
            "Blue" -> "0xFF0000FF"
            "Green" -> "0xFF00FF00"
            else -> error("unknown colour: $color")
        }
        File(srcDir, "ColorBoxes.kt").writeText(
            """
            package test

            import androidx.compose.ui.tooling.preview.Preview
            import androidx.compose.foundation.background
            import androidx.compose.foundation.layout.Box
            import androidx.compose.foundation.layout.fillMaxSize
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.graphics.Color

            @Preview(
                showBackground = true,
                backgroundColor = $hex,
                widthDp = 50,
                heightDp = 50,
            )
            @Composable
            fun BoxPreview() {
                Box(modifier = Modifier.fillMaxSize().background(Color.$color))
            }
            """.trimIndent()
        )
    }

    private fun runRenderAllPreviews(projectDir: File) =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments("renderAllPreviews", "--stacktrace")
            .withPluginClasspath()
            .build()

    private fun historyPreviewFolder(projectDir: File): File? =
        File(projectDir, ".compose-preview-history")
            .takeIf { it.exists() }
            ?.listFiles()
            ?.firstOrNull { it.isDirectory }

    @Test
    fun `history disabled by default - no historize task is registered`() {
        val projectDir = createProject(historyEnabled = false)

        val result = runRenderAllPreviews(projectDir)

        assertThat(result.task(":historizePreviews")).isNull()
        assertThat(File(projectDir, ".compose-preview-history").exists()).isFalse()
    }

    @Test
    fun `first run archives a snapshot per preview`() {
        val projectDir = createProject(historyEnabled = true)

        val result = runRenderAllPreviews(projectDir)

        assertThat(result.task(":historizePreviews")?.outcome).isEqualTo(TaskOutcome.SUCCESS)

        val previewFolder = historyPreviewFolder(projectDir)
        assertThat(previewFolder).isNotNull()
        val snapshots = previewFolder!!.listFiles { f -> f.extension == "png" } ?: emptyArray()
        assertThat(snapshots).hasLength(1)
        // Timestamp format yyyyMMdd-HHmmss (plus optional -N collision suffix).
        assertThat(snapshots[0].name).matches("""\d{8}-\d{6}(-\d+)?\.png""")
    }

    @Test
    fun `rerun with identical render does not add a second snapshot`() {
        val projectDir = createProject(historyEnabled = true)

        runRenderAllPreviews(projectDir)
        val countAfterFirst = historyPreviewFolder(projectDir)!!
            .listFiles { f -> f.extension == "png" }!!.size

        // Force the history task to execute again even though renders are UP-TO-DATE:
        // delete the marker output and invoke via a second argument that bumps the graph.
        // Since history task has @Internal output dir and depends on renderTask, if
        // renders are UP-TO-DATE the history task's inputs are unchanged → it also goes
        // UP-TO-DATE. That alone proves no duplicate snapshot is archived.
        val secondRun = runRenderAllPreviews(projectDir)
        val countAfterSecond = historyPreviewFolder(projectDir)!!
            .listFiles { f -> f.extension == "png" }!!.size

        assertThat(countAfterSecond).isEqualTo(countAfterFirst)
        // Whether the task is SKIPPED/UP-TO-DATE or re-runs with no-op is fine — the
        // invariant is "no new file when bytes unchanged".
        assertThat(secondRun.task(":historizePreviews")?.outcome)
            .isAnyOf(TaskOutcome.UP_TO_DATE, TaskOutcome.SUCCESS, TaskOutcome.SKIPPED, TaskOutcome.FROM_CACHE)
    }

    @Test
    fun `changed preview content adds a new snapshot`() {
        val projectDir = createProject(historyEnabled = true)
        val srcDir = File(projectDir, "src/main/kotlin/test")

        // Preview uses showBackground + backgroundColor so the full PNG takes on the
        // preview's colour — byte-identity of two consecutive renders is a robust
        // signal that the render actually changed between source edits.
        runRenderAllPreviews(projectDir)
        val firstPng = File(projectDir, "build/compose-previews/renders")
            .listFiles { f -> f.extension == "png" }!!
            .first()
        val firstCenter = ImageIO.read(firstPng).let { img -> img.getRGB(img.width / 2, img.height / 2) }
        val initial = historyPreviewFolder(projectDir)!!
            .listFiles { f -> f.extension == "png" }!!
            .map { it.name }
            .toSet()

        // Change the preview's colour so the PNG bytes differ. Bump mtime explicitly —
        // on fast test machines two writes within the same second may not invalidate
        // Gradle's file-change tracking.
        writePreviewSource(srcDir, color = "Blue")
        File(srcDir, "ColorBoxes.kt").setLastModified(System.currentTimeMillis() + 2000)

        runRenderAllPreviews(projectDir)
        val secondPng = File(projectDir, "build/compose-previews/renders")
            .listFiles { f -> f.extension == "png" }!!
            .first()
        val secondCenter = ImageIO.read(secondPng).let { img -> img.getRGB(img.width / 2, img.height / 2) }

        // Sanity: the render actually changed — otherwise the history comparison
        // is vacuous and the test isn't exercising what we think.
        assertThat(firstCenter).isNotEqualTo(secondCenter)

        val after = historyPreviewFolder(projectDir)!!
            .listFiles { f -> f.extension == "png" }!!
            .map { it.name }
            .toSet()

        assertThat(after).containsAtLeastElementsIn(initial)
        assertThat(after - initial).hasSize(1)
    }
}
