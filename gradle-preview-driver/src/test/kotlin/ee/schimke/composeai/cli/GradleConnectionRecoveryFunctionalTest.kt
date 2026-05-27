package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * End-to-end coverage for the Kotlin IC "Storage already registered" recovery (issue #1493). Drives
 * a synthetic Gradle project through [GradleConnection] using a real Gradle distribution; the
 * synthetic project's `trigger` task emits the upstream marker line on its first invocation, then
 * succeeds normally on subsequent invocations. Exercises the close-connection → stop-daemons →
 * wipe-cache → reconnect → retry-with-rerun-tasks pipeline that [GradleConnection]'s recovery code
 * is responsible for.
 *
 * The Gradle distribution path is provided by the parent build via the `composeai.test.gradleHome`
 * system property (see `gradle-preview-driver/build.gradle.kts`). When the property is absent (e.g.
 * running the test class directly from an IDE without that wiring), the test self-skips via
 * `assumeTrue` so it doesn't false-fail.
 *
 * The synthetic project deliberately ships a fake `gradlew` shell stub instead of a real wrapper so
 * the recovery's `./gradlew --stop` call (a) executes without trying to download Gradle and (b)
 * cannot accidentally kill the daemon serving our own `./gradlew test` run. The stub touches a flag
 * file we then assert on.
 */
class GradleConnectionRecoveryFunctionalTest {
  private val gradleHome: File? =
    (System.getProperty("composeai.test.gradleHome") ?: System.getenv("GRADLE_HOME"))?.let {
      File(it).takeIf { dir -> dir.isDirectory }
    }

  private val tempDirs = mutableListOf<File>()

  @BeforeTest
  fun checkGradleAvailable() {
    assumeTrue(
      "GradleConnectionRecoveryFunctionalTest needs a Gradle install at " +
        "-Dcomposeai.test.gradleHome=<path> or env GRADLE_HOME; got $gradleHome",
      gradleHome != null,
    )
  }

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { runCatching { it.deleteRecursively() } }
  }

  private fun tempDir(prefix: String = "ic-recovery-"): File =
    Files.createTempDirectory(prefix).toFile().also { tempDirs += it }

  @Test
  fun `wipes caches-jvm and retries with --rerun-tasks after Kotlin IC storage marker`() {
    val projectDir = tempDir()
    val stopFlag = projectDir.resolve("stop-called.flag")
    writeFakeGradlewStub(projectDir, stopFlag)

    val cacheDir = projectDir.resolve("build/kotlin/compileKotlin/cacheable/caches-jvm")
    val counterFile = projectDir.resolve("build/invocations.txt")

    projectDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"ic-recovery\"\n")
    projectDir.resolve("build.gradle.kts").writeText(triggerBuildScript())

    val ok =
      GradleConnection(projectDir = projectDir, verbose = false, gradleInstallation = gradleHome)
        .use { it.runTasks("trigger", timeoutSeconds = 180) }

    assertTrue(ok, "recovery retry should have succeeded; final outcome was false")
    assertTrue(counterFile.exists(), "counter file should exist after both invocations")
    assertEquals(
      "2",
      counterFile.readText().trim(),
      "task should have run exactly twice (initial + recovery retry)",
    )
    assertFalse(
      cacheDir.exists(),
      "caches-jvm should have been wiped during recovery and not recreated on the retry pass " +
        "(retry's counter==2 branch skips the cache-creation block)",
    )
    assertTrue(
      stopFlag.exists(),
      "fake gradlew stub should have been invoked by recovery's stopGradleDaemons()",
    )
  }

  @Test
  fun `does not loop when the marker re-occurs on the retry`() {
    val projectDir = tempDir()
    val stopFlag = projectDir.resolve("stop-called.flag")
    writeFakeGradlewStub(projectDir, stopFlag)

    val counterFile = projectDir.resolve("build/invocations.txt")

    projectDir.resolve("settings.gradle.kts").writeText("rootProject.name = \"ic-recovery\"\n")
    // ALWAYS-emit variant: the recovery retry will re-trigger detection, but the retry guard
    // (allowKotlinIcRecovery=false on the recursive call) must prevent a second recovery pass.
    projectDir.resolve("build.gradle.kts").writeText(triggerBuildScript(alwaysEmit = true))

    val ok =
      GradleConnection(projectDir = projectDir, verbose = false, gradleInstallation = gradleHome)
        .use { it.runTasks("trigger", timeoutSeconds = 180) }

    assertTrue(ok, "build itself still succeeds; the marker is only printed, not thrown")
    assertEquals(
      "2",
      counterFile.readText().trim(),
      "exactly two invocations: initial + one retry. A third would mean the recovery guard leaked.",
    )
  }

  private fun writeFakeGradlewStub(projectDir: File, stopFlag: File) {
    val script = projectDir.resolve("gradlew")
    script.writeText(
      """
      #!/bin/sh
      echo stopped > '${stopFlag.absolutePath}'
      exit 0
      """
        .trimIndent() + "\n"
    )
    script.setExecutable(true)
  }

  private fun triggerBuildScript(alwaysEmit: Boolean = false): String {
    val emitCondition = if (alwaysEmit) "true" else "count == 1"
    return """
      tasks.register("trigger") {
        val cacheDir = file("build/kotlin/compileKotlin/cacheable/caches-jvm/jvm/kotlin")
        val counterFile = file("build/invocations.txt")
        outputs.upToDateWhen { false }
        doLast {
          val previous = if (counterFile.exists()) counterFile.readText().trim().toInt() else 0
          val count = previous + 1
          counterFile.parentFile.mkdirs()
          counterFile.writeText(count.toString())
          if ($emitCondition) {
            cacheDir.mkdirs()
            val tab = cacheDir.resolve("source-to-classes.tab")
            tab.createNewFile()
            // Mirror Kotlin's IC compiler logger format (KT-59321). The build still succeeds —
            // upstream Kotlin falls back to non-incremental rather than failing the task.
            println("e: Incremental compilation failed: Storage for [" + tab.absolutePath + "] is already registered")
          }
        }
      }
      """
      .trimIndent()
  }
}
