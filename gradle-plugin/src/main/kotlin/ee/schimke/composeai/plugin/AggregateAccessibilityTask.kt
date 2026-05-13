package ee.schimke.composeai.plugin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Aggregates the per-preview ATF reports emitted by `RobolectricRenderTest` into a single
 * `accessibility.json` keyed by previewId. Never fails the build — findings are reported (logged,
 * written to the JSON report, surfaced as CLI / VS Code diagnostics) but the task is purely
 * informational. Runs once per Android module after `renderPreviews`.
 *
 * The inputs are modeled as a `@InputFiles` `FileCollection` rather than `@InputDirectory` so the
 * task no-ops cleanly on modules whose `RobolectricRenderTest` never wrote a per-preview JSON (e.g.
 * tile-only consumers, or modules that errored before any capture). Writes an empty
 * `accessibility.json` in that case so the CLI's manifest-pointer follow doesn't break.
 */
@CacheableTask
abstract class AggregateAccessibilityTask : DefaultTask() {

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val perPreviewFiles: ConfigurableFileCollection

  @get:Input abstract val moduleName: Property<String>

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
    val nodes: List<A11yNode> = emptyList(),
    val annotatedPath: String? = null,
  )

  @Serializable private data class A11yReport(val module: String, val entries: List<A11yEntry>)

  @TaskAction
  fun aggregate() {
    val json = Json {
      prettyPrint = true
      encodeDefaults = true
      ignoreUnknownKeys = true
    }

    val entries =
      perPreviewFiles
        .filter { it.isFile && it.name.endsWith(".json") }
        .sortedBy { it.name }
        .map { json.decodeFromString(A11yEntry.serializer(), it.readText()) }

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
  }
}
