package ee.schimke.composeai.cli

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.tooling.BuildException
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.model.GradleProject
import org.junit.Assume.assumeTrue

/**
 * End-to-end regression for issue #1620 against a real Gradle build: preview discovery must not
 * realize the whole task graph.
 *
 * The synthetic build's `:native` module registers a task whose **configuration action** throws —
 * standing in for a real `org.graalvm.buildtools.native` `nativeCompile` task that provisions a
 * Java toolchain (expensive, network-dependent, frequently failing) when it's realized.
 *
 * Both queries run against the same Tooling-API connection:
 * 1. [DiscoverPreviewModulesAction] — the new discovery path. Walks `GradleBuild` + per-project
 *    `ComposePreviewModel` and never realizes `:native`'s task, so it completes. (No plugin is
 *    applied, so the result is empty — the point is that it does *not* throw.)
 * 2. The `GradleProject` model — the old discovery path. Building it realizes every task in every
 *    project, runs `:native`'s poison configuration, and fails the whole query. Asserting this
 *    keeps the repro honest: it's the exact failure #1620 removes.
 */
class DiscoverPreviewModulesIntegrationTest {

  private val gradleHome: String = System.getProperty("composeai.test.gradleHome", "")
  private val workspace: File =
    File.createTempFile("task-graph-repro", "").let {
      it.delete()
      it.mkdirs()
      it
    }

  @AfterTest
  fun cleanup() {
    workspace.deleteRecursively()
  }

  private fun seedBuild() {
    File(workspace, "settings.gradle.kts")
      .writeText(
        """
        rootProject.name = "task-graph-repro"
        include(":ui")
        include(":native")
        """
          .trimIndent()
      )
    File(workspace, "build.gradle.kts").writeText("")

    File(workspace, "ui").mkdirs()
    File(workspace, "ui/build.gradle.kts").writeText("")

    File(workspace, "native").mkdirs()
    // The lambda is the task's configuration action, which Gradle runs only when the task is
    // *realized*. Lazy registration means merely configuring the project (what a model query does)
    // is fine — realizing the task is what trips it. That's exactly the distinction under test.
    File(workspace, "native/build.gradle.kts")
      .writeText(
        """
        tasks.register("nativeCompile") {
            throw GradleException("config-time toolchain provisioning blocked")
        }
        """
          .trimIndent()
      )
  }

  @Test
  fun `discovery completes while GradleProject blows up on the poison module`() {
    assumeTrue(
      "Gradle installation dir not surfaced via composeai.test.gradleHome — skipping",
      gradleHome.isNotEmpty() && File(gradleHome).isDirectory,
    )
    seedBuild()

    GradleConnector.newConnector()
      .useInstallation(File(gradleHome))
      .forProjectDirectory(workspace)
      .connect()
      .use { connection ->
        // The fix: completes without realizing :native's poison task.
        val discovered = connection.action(DiscoverPreviewModulesAction()).run()
        assertEquals(emptyList(), discovered.modules.map { it.gradlePath })

        // Control: the old `GradleProject` discovery path realizes the task graph and dies.
        val failure =
          runCatching { connection.model(GradleProject::class.java).get() }.exceptionOrNull()
        assertTrue(
          failure is BuildException,
          "expected GradleProject query to fail by realizing :native's task, got $failure",
        )
      }
  }
}
