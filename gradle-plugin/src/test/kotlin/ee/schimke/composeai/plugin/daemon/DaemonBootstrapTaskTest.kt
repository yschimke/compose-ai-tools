package ee.schimke.composeai.plugin.daemon

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.serialization.json.Json
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Direct invocation of [DaemonBootstrapTask] via [ProjectBuilder]. The full Android pipeline is
 * exercised in samples (see the worktree's `:samples:android:composePreviewDaemonStart` smoke test
 * in the PR description) — this unit test pins the descriptor's JSON shape so the VS Code extension
 * and Stream B daemon can target a stable contract without relying on AGP being on the test
 * classpath.
 */
class DaemonBootstrapTaskTest {

  @get:Rule val tempDir = TemporaryFolder()

  private val json = Json { ignoreUnknownKeys = true }

  private fun newProject(): Project = ProjectBuilder.builder().withProjectDir(tempDir.root).build()

  @Test
  fun `descriptor JSON populates every documented field`() {
    val project = newProject()
    val outFile = File(tempDir.root, "build/compose-previews/daemon-launch.json")
    val cpJar = File(tempDir.root, "fakelib.jar").apply { writeText("placeholder") }

    val task =
      project.tasks.register("composePreviewDaemonStart", DaemonBootstrapTask::class.java) {
        modulePath.set(":samples:fake")
        variant.set("debug")
        daemonEnabled.set(false)
        maxHeapMb.set(1024)
        maxRendersPerSandbox.set(1000)
        warmSpare.set(true)
        mainClass.set("ee.schimke.composeai.daemon.DaemonMain")
        javaLauncher.set("/usr/lib/jvm/java-17/bin/java")
        classpath.from(cpJar)
        jvmArgs.set(listOf("--add-opens=java.base/java.lang=ALL-UNNAMED", "-Xmx1024m"))
        systemProperties.set(
          mapOf("robolectric.graphicsMode" to "NATIVE", "composeai.daemon.maxHeapMb" to "1024")
        )
        workingDirectory.set(tempDir.root.absolutePath)
        manifestPath.set("/abs/previews.json")
        outputFile.set(outFile)
      }

    task.get().emit()

    assertThat(outFile.exists()).isTrue()
    val descriptor = json.decodeFromString<DaemonClasspathDescriptor>(outFile.readText())

    assertThat(descriptor.schemaVersion).isEqualTo(DAEMON_DESCRIPTOR_SCHEMA_VERSION)
    assertThat(descriptor.modulePath).isEqualTo(":samples:fake")
    assertThat(descriptor.variant).isEqualTo("debug")
    assertThat(descriptor.enabled).isFalse()
    assertThat(descriptor.mainClass).isEqualTo("ee.schimke.composeai.daemon.DaemonMain")
    assertThat(descriptor.javaLauncher).isEqualTo("/usr/lib/jvm/java-17/bin/java")
    assertThat(descriptor.classpath).hasSize(1)
    assertThat(descriptor.classpath.single()).endsWith("fakelib.jar")
    assertThat(descriptor.jvmArgs).contains("-Xmx1024m")
    assertThat(descriptor.systemProperties).containsEntry("robolectric.graphicsMode", "NATIVE")
    assertThat(descriptor.systemProperties).containsEntry("composeai.daemon.maxHeapMb", "1024")
    assertThat(descriptor.workingDirectory).isEqualTo(tempDir.root.absolutePath)
    assertThat(descriptor.manifestPath).isEqualTo("/abs/previews.json")
  }

  @Test
  fun `descriptor honours enabled flag from extension wiring`() {
    val project = newProject()
    val outFile = File(tempDir.root, "build/compose-previews/daemon-launch.json")

    val task =
      project.tasks.register("composePreviewDaemonStart", DaemonBootstrapTask::class.java) {
        modulePath.set(":x")
        variant.set("debug")
        daemonEnabled.set(true)
        maxHeapMb.set(2048)
        maxRendersPerSandbox.set(500)
        warmSpare.set(false)
        mainClass.set("ee.schimke.composeai.daemon.DaemonMain")
        jvmArgs.set(emptyList())
        systemProperties.set(emptyMap())
        workingDirectory.set(tempDir.root.absolutePath)
        manifestPath.set("/abs/previews.json")
        outputFile.set(outFile)
      }

    task.get().emit()

    val descriptor = json.decodeFromString<DaemonClasspathDescriptor>(outFile.readText())
    assertThat(descriptor.enabled).isTrue()
    // No javaLauncher provider configured — descriptor encodes null rather
    // than an empty string. VS Code's daemonProcess.ts treats both the
    // missing field and the explicit null as "no AGP-provided launcher,
    // fall back to extension JDK detection."
    assertThat(descriptor.javaLauncher).isNull()
  }
}
