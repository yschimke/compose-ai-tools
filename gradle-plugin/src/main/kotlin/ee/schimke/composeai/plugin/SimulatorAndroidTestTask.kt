package ee.schimke.composeai.plugin

import groovy.json.JsonSlurper
import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.process.ExecOperations

/**
 * `simulator<Variant>AndroidTest`: the variant's `androidTest` suite, run in Robolectric's
 * simulator beside the app instead of on a device — `connected<Variant>AndroidTest` with no
 * emulator. It runs `compose-preview android-simulator <app.apk> --instrument <test.apk>` (see
 * docs/SIMULATOR.md, "Instrumentation tests") and writes JUnit XML and JSON results to
 * [resultsDir].
 *
 * Like the connected task it never counts as up to date: a test run is the point of invoking it.
 */
abstract class SimulatorAndroidTestTask : DefaultTask() {

  @get:Inject abstract val execOperations: ExecOperations

  /** The app variant's APK directory (AGP's `SingleArtifact.APK`). */
  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val appApkDir: DirectoryProperty

  /** The variant's `androidTest` APK directory. */
  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val testApkDir: DirectoryProperty

  /**
   * The `compose-preview` CLI to run: `-PcomposePreview.cli=<path>`, else `COMPOSE_PREVIEW_CLI`,
   * else `compose-preview` on `PATH`.
   */
  @get:Input abstract val cliExecutable: Property<String>

  /** The JDK (21+) the simulator runs on; unset leaves the CLI to find one. */
  @get:Input @get:Optional abstract val javaHome: Property<String>

  /** The simulator's Android SDK level, `--sdk`; unset uses the CLI's default. */
  @get:Input @get:Optional abstract val sdk: Property<Int>

  @get:Input
  @get:Optional
  @get:Option(
    option = "tests",
    description = "A test class, or Class#method, to run (repeatable). Default: every test.",
  )
  abstract val tests: ListProperty<String>

  @get:OutputDirectory abstract val resultsDir: DirectoryProperty

  @get:Internal
  val xmlReport: File
    get() = resultsDir.file("TEST-simulator.xml").get().asFile

  @get:Internal
  val jsonReport: File
    get() = resultsDir.file("results.json").get().asFile

  init {
    outputs.upToDateWhen { false }
  }

  @TaskAction
  fun run() {
    val appApk = singleApk(appApkDir.get().asFile, "app")
    val testApk = singleApk(testApkDir.get().asFile, "androidTest")
    resultsDir.get().asFile.mkdirs()
    xmlReport.delete()
    jsonReport.delete()
    val command = buildList {
      add(cliExecutable.get())
      add("android-simulator")
      add(appApk.absolutePath)
      add("--instrument")
      add(testApk.absolutePath)
      add("--test-report")
      add(xmlReport.absolutePath)
      add("--test-json")
      add(jsonReport.absolutePath)
      javaHome.orNull?.let {
        add("--java")
        add(it)
      }
      sdk.orNull?.let {
        add("--sdk")
        add(it.toString())
      }
      val selected = tests.getOrElse(emptyList()).filter { it.isNotBlank() }
      if (selected.isNotEmpty()) {
        add("--tests")
        add(selected.joinToString(","))
      }
    }
    logger.info("compose-preview: {}", command.joinToString(" "))
    val result =
      try {
        execOperations.exec {
          commandLine(command)
          isIgnoreExitValue = true
        }
      } catch (e: Exception) {
        throw GradleException(
          "Could not run `${cliExecutable.get()}` (${e.message}). Install the compose-preview " +
            "CLI, or point -PcomposePreview.cli (or COMPOSE_PREVIEW_CLI) at it.",
          e,
        )
      }
    val summary = summary()
    if (result.exitValue != 0) {
      throw GradleException(
        buildString {
          append("Simulator instrumentation tests failed (exit ${result.exitValue}).")
          summary?.let { append("\n").append(it) }
          append("\nResults: ").append(resultsDir.get().asFile)
        },
      )
    }
    summary?.let { logger.lifecycle(it) }
  }

  /** "n tests: p passed, f failed, s skipped" and each failed test, from [jsonReport]. */
  private fun summary(): String? {
    if (!jsonReport.isFile) return null
    @Suppress("UNCHECKED_CAST")
    val json = JsonSlurper().parse(jsonReport) as? Map<String, Any?> ?: return null
    @Suppress("UNCHECKED_CAST") val cases = json["cases"] as? List<Map<String, Any?>> ?: emptyList()
    json["error"]?.let {
      return "No tests ran: $it"
    }
    return buildString {
      append(
        "${json["tests"]} tests: ${json["passed"]} passed, ${json["failed"]} failed, " +
          "${json["skipped"]} skipped",
      )
      for (case in cases.filter { it["status"] == "failed" }) {
        append("\n  FAILED ${case["class"]}#${case["method"]}: ${case["message"]}")
      }
    }
  }

  private fun singleApk(dir: File, what: String): File {
    val apks = dir.walkTopDown().filter { it.isFile && it.extension == "apk" }.toList()
    return apks.singleOrNull()
      ?: throw GradleException(
        "Expected one $what APK under $dir, found ${apks.size}" +
          (if (apks.isEmpty()) "" else ": ${apks.joinToString { it.name }}") +
          ". Split APKs are not supported by the simulator.",
      )
  }

  companion object {
    const val GROUP = "verification"

    fun name(variantName: String): String =
      "simulator${variantName.replaceFirstChar { it.uppercase() }}AndroidTest"
  }
}
