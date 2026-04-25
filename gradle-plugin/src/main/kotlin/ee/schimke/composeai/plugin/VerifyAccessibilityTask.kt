package ee.schimke.composeai.plugin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Aggregates the per-preview ATF reports emitted by [RobolectricRenderTestBase] into a single
 * `accessibility.json` keyed by previewId, and fails the build when findings exceed the thresholds
 * configured on [AccessibilityChecksExtension].
 *
 * Runs once per Android module after `renderPreviews`. Only registered when
 * `composePreview.accessibilityChecks.enabled = true`.
 */
@CacheableTask
abstract class VerifyAccessibilityTask : DefaultTask() {

  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val perPreviewDir: DirectoryProperty

  @get:Input abstract val moduleName: Property<String>

  @get:Input abstract val failOnErrors: Property<Boolean>

  @get:Input abstract val failOnWarnings: Property<Boolean>

  @get:OutputFile abstract val reportFile: RegularFileProperty

  // Serializers duplicated here rather than shared with renderer-android —
  // the plugin runs on the Gradle JVM and cannot depend on an Android
  // artifact. Stay in sync with [RenderManifest.kt] in renderer-android.
  @Serializable
  private data class A11yFinding(
    val level: String,
    val type: String,
    val message: String,
    val viewDescription: String? = null,
    val boundsInScreen: String? = null,
  )

  @Serializable
  private data class A11yNode(
    val label: String,
    val role: String? = null,
    val states: List<String> = emptyList(),
    val boundsInScreen: String,
  )

  @Serializable
  private data class A11yEntry(
    val previewId: String,
    val findings: List<A11yFinding>,
    // Mirror of [renderer-android/RenderManifest.AccessibilityNode] —
    // round-trips through the aggregate JSON so downstream tools (CLI,
    // VS Code, the python report lib) can read the ANI overlay data
    // without going through the renderer's classpath.
    val nodes: List<A11yNode> = emptyList(),
    val annotatedPath: String? = null,
  )

  @Serializable private data class A11yReport(val module: String, val entries: List<A11yEntry>)

  @TaskAction
  fun verify() {
    val json = Json {
      prettyPrint = true
      encodeDefaults = true
      ignoreUnknownKeys = true
    }

    val dir = perPreviewDir.get().asFile
    val entries =
      if (dir.exists()) {
        dir
          .listFiles { f -> f.isFile && f.name.endsWith(".json") }
          .orEmpty()
          .sortedBy { it.name }
          .map { json.decodeFromString(A11yEntry.serializer(), it.readText()) }
      } else {
        emptyList()
      }

    val report = A11yReport(module = moduleName.get(), entries = entries)
    val out = reportFile.get().asFile
    out.parentFile?.mkdirs()
    out.writeText(json.encodeToString(A11yReport.serializer(), report))

    val errorCount = entries.sumOf { it.findings.count { f -> f.level == "ERROR" } }
    val warningCount = entries.sumOf { it.findings.count { f -> f.level == "WARNING" } }

    logger.lifecycle(
      "Accessibility: ${entries.size} preview(s), " +
        "$errorCount error(s), $warningCount warning(s)"
    )
    for (entry in entries) {
      for (finding in entry.findings) {
        if (finding.level == "INFO") continue
        logger.lifecycle(
          "  [${finding.level}] ${entry.previewId} · ${finding.type}: ${finding.message}"
        )
      }
    }

    val failures = mutableListOf<String>()
    if (failOnErrors.get() && errorCount > 0) {
      failures += "$errorCount error(s)"
    }
    if (failOnWarnings.get() && warningCount > 0) {
      failures += "$warningCount warning(s)"
    }
    if (failures.isNotEmpty()) {
      throw GradleException(
        "Accessibility check failed: ${failures.joinToString(", ")}. " +
          "See ${out.absolutePath} for the full report, or disable the " +
          "relevant `failOn*` flag in `composePreview.accessibilityChecks` " +
          "to downgrade to a warning."
      )
    }
  }
}
