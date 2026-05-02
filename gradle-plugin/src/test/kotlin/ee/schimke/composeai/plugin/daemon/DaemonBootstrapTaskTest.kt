package ee.schimke.composeai.plugin.daemon

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.serialization.json.Json
import org.gradle.api.Project
import org.gradle.api.tasks.CacheableTask
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
  fun `bootstrap descriptor task is cacheable`() {
    assertThat(DaemonBootstrapTask::class.java.isAnnotationPresent(CacheableTask::class.java))
      .isTrue()
  }

  @Test
  fun `classpath fingerprint tracks launch paths not class contents`() {
    val project = newProject()
    val outFile = File(tempDir.root, "build/compose-previews/daemon-launch.json")
    val cpJar = File(tempDir.root, "fakelib.jar").apply { writeText("first") }

    val task =
      project.tasks.register("composePreviewDaemonStart", DaemonBootstrapTask::class.java) {
        modulePath.set(":x")
        variant.set("debug")
        daemonEnabled.set(true)
        maxHeapMb.set(512)
        maxRendersPerSandbox.set(50)
        warmSpare.set(true)
        mainClass.set("ee.schimke.composeai.daemon.DaemonMain")
        classpath.from(cpJar)
        jvmArgs.set(emptyList())
        systemProperties.set(emptyMap())
        workingDirectory.set(tempDir.root.absolutePath)
        manifestPath.set("/abs/previews.json")
        outputFile.set(outFile)
      }

    val before = task.get().classpathPaths
    cpJar.writeText("changed class bytes")
    val after = task.get().classpathPaths

    assertThat(after).isEqualTo(before)
    assertThat(after).containsExactly(cpJar.absolutePath)
  }

  @Test
  fun `classpath still contributes producer task dependencies`() {
    val project = newProject()
    val outFile = File(tempDir.root, "build/compose-previews/daemon-launch.json")
    val cpJar = File(tempDir.root, "fakelib.jar")
    val producer = project.tasks.register("producer")
    val producedClasspath = project.files(cpJar).builtBy(producer)

    val task =
      project.tasks.register("composePreviewDaemonStart", DaemonBootstrapTask::class.java) {
        modulePath.set(":x")
        variant.set("debug")
        daemonEnabled.set(true)
        maxHeapMb.set(512)
        maxRendersPerSandbox.set(50)
        warmSpare.set(true)
        mainClass.set("ee.schimke.composeai.daemon.DaemonMain")
        classpath.from(producedClasspath)
        jvmArgs.set(emptyList())
        systemProperties.set(emptyMap())
        workingDirectory.set(tempDir.root.absolutePath)
        manifestPath.set("/abs/previews.json")
        outputFile.set(outFile)
      }

    assertThat(task.get().taskDependencies.getDependencies(task.get())).contains(producer.get())
  }

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
  fun `descriptor encodes B2_0 userClassDirs sysprop verbatim`() {
    // B2.0 — the disposable user-classloader design (CLASSLOADER.md) requires the gradle plugin
    // to surface user-class-dirs to the daemon JVM. The plugin computes the value upstream
    // (heuristic over the resolved classpath in `AndroidPreviewSupport.kt`); this test pins the
    // contract that whatever value is set propagates verbatim through the descriptor.
    val project = newProject()
    val outFile = File(tempDir.root, "build/compose-previews/daemon-launch.json")
    val task =
      project.tasks.register("composePreviewDaemonStart", DaemonBootstrapTask::class.java) {
        modulePath.set(":x")
        variant.set("debug")
        daemonEnabled.set(true)
        maxHeapMb.set(512)
        maxRendersPerSandbox.set(50)
        warmSpare.set(true)
        mainClass.set("ee.schimke.composeai.daemon.DaemonMain")
        jvmArgs.set(emptyList())
        systemProperties.set(
          mapOf(
            "composeai.daemon.userClassDirs" to
              "/abs/build/intermediates/built_in_kotlinc/debug/classes:/abs/build/tmp/kotlin-classes/debug"
          )
        )
        workingDirectory.set(tempDir.root.absolutePath)
        manifestPath.set("/abs/previews.json")
        outputFile.set(outFile)
      }

    task.get().emit()

    val descriptor = json.decodeFromString<DaemonClasspathDescriptor>(outFile.readText())
    assertThat(descriptor.systemProperties).containsKey("composeai.daemon.userClassDirs")
    assertThat(descriptor.systemProperties["composeai.daemon.userClassDirs"])
      .contains("/abs/build/intermediates/built_in_kotlinc/debug/classes")
    assertThat(descriptor.systemProperties["composeai.daemon.userClassDirs"])
      .contains("/abs/build/tmp/kotlin-classes/debug")
  }

  @Test
  fun `descriptor encodes B2_1 cheapSignalFiles sysprop verbatim`() {
    // B2.1 — the Tier-1 ClasspathFingerprint design (DESIGN § 8) requires the gradle plugin to
    // surface the cheap-signal file set to the daemon JVM. This test pins the contract that
    // whatever value is set propagates verbatim through the descriptor.
    val project = newProject()
    val outFile = File(tempDir.root, "build/compose-previews/daemon-launch.json")
    val cheapPaths =
      listOf(
        "/abs/gradle/libs.versions.toml",
        "/abs/build.gradle.kts",
        "/abs/settings.gradle.kts",
        "/abs/gradle.properties",
      )
    val task =
      project.tasks.register("composePreviewDaemonStart", DaemonBootstrapTask::class.java) {
        modulePath.set(":x")
        variant.set("debug")
        daemonEnabled.set(true)
        maxHeapMb.set(512)
        maxRendersPerSandbox.set(50)
        warmSpare.set(true)
        mainClass.set("ee.schimke.composeai.daemon.DaemonMain")
        jvmArgs.set(emptyList())
        systemProperties.set(
          mapOf("composeai.daemon.cheapSignalFiles" to cheapPaths.joinToString(File.pathSeparator))
        )
        workingDirectory.set(tempDir.root.absolutePath)
        manifestPath.set("/abs/previews.json")
        outputFile.set(outFile)
      }

    task.get().emit()

    val descriptor = json.decodeFromString<DaemonClasspathDescriptor>(outFile.readText())
    assertThat(descriptor.systemProperties).containsKey("composeai.daemon.cheapSignalFiles")
    val emitted = descriptor.systemProperties["composeai.daemon.cheapSignalFiles"] ?: ""
    cheapPaths.forEach { assertThat(emitted).contains(it) }
  }

  @Test
  fun `descriptor encodes B2_2 previewsJsonPath sysprop verbatim`() {
    // B2.2 phase 1 — the daemon owns its own preview index, parsed from `previews.json` at
    // startup. The gradle plugin surfaces the absolute path via the
    // `composeai.daemon.previewsJsonPath` sysprop on the daemon JVM. This test pins the contract
    // that whatever value upstream wires propagates verbatim through the descriptor's
    // `systemProperties` map.
    val project = newProject()
    val outFile = File(tempDir.root, "build/compose-previews/daemon-launch.json")
    val previewsJsonAbs = "/abs/build/compose-previews/previews.json"
    val task =
      project.tasks.register("composePreviewDaemonStart", DaemonBootstrapTask::class.java) {
        modulePath.set(":x")
        variant.set("debug")
        daemonEnabled.set(true)
        maxHeapMb.set(512)
        maxRendersPerSandbox.set(50)
        warmSpare.set(true)
        mainClass.set("ee.schimke.composeai.daemon.DaemonMain")
        jvmArgs.set(emptyList())
        systemProperties.set(mapOf("composeai.daemon.previewsJsonPath" to previewsJsonAbs))
        workingDirectory.set(tempDir.root.absolutePath)
        manifestPath.set(previewsJsonAbs)
        outputFile.set(outFile)
      }

    task.get().emit()

    val descriptor = json.decodeFromString<DaemonClasspathDescriptor>(outFile.readText())
    assertThat(descriptor.systemProperties).containsKey("composeai.daemon.previewsJsonPath")
    assertThat(descriptor.systemProperties["composeai.daemon.previewsJsonPath"])
      .isEqualTo(previewsJsonAbs)
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
