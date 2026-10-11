package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.previewdata.A11Y_PAYLOAD_SCHEMA_V1
import ee.schimke.composeai.previewdata.AccessibilityEntry
import ee.schimke.composeai.previewdata.AccessibilityReport
import ee.schimke.composeai.previewdata.ExtensionPayload
import ee.schimke.composeai.previewdata.PreviewManifest
import ee.schimke.composeai.previewdata.PreviewModule
import ee.schimke.composeai.previewdata.PreviewResult
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

/*
 * On-disk shape mirrors the daemon's aggregation (`AccessibilityDataProductRegistry`); the Gradle
 * path no longer produces it. The DTOs live in `:preview-data-api/A11yWireFormat.kt`, mirroring
 * `:data-a11y-core` so JVM consumers needn't pull an Android library to decode.
 */

/**
 * [ExtensionReportRenderer] for the built-in `a11y` extension: reads each module's
 * `accessibility.json`, attaches entries as `dataExtensions["a11y"]` payloads, and prints findings
 * by preview with optional `--fail-on`. [a11yByKey], built by [load], backs [annotate].
 */
class A11yReportRenderer(private val fileSystem: FileSystem = SystemFileSystem) :
  ExtensionReportRenderer {
  override val id: String = "a11y"
  override val displayName: String = "Accessibility (ATF)"
  override val description: String =
    "ATF findings + annotated overlay PNG. Enable with `--with-extension a11y` " +
      "(or `compose-preview a11y`)."

  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
  }

  /** `"<module>/<previewId>"` -> the decoded entry (findings + absolute annotated PNG path). */
  private var a11yByKey: Map<String, AccessibilityEntry> = emptyMap()

  /** Set of module gradle-paths whose manifest claims a11y is enabled (pointer non-null). */
  private var enabledModules: Set<String> = emptySet()

  /**
   * Modules whose report is [AccessibilityReport.partial] (a narrowed run). Their unlisted previews
   * were never checked, so they get no carrier rather than a false clean row.
   */
  private var partialModules: Set<String> = emptySet()

  /** Preview keys explicitly opted out via `@PreviewHelper(includeInA11y = false)`. */
  private var excludedPreviewKeys: Set<String> = emptySet()

  override fun load(
    manifests: List<Pair<PreviewModule, PreviewManifest>>,
    verbose: Boolean,
  ): Set<String> {
    val out = mutableMapOf<String, AccessibilityEntry>()
    val enabled = mutableSetOf<String>()
    val partial = mutableSetOf<String>()
    val excluded = mutableSetOf<String>()
    for ((module, manifest) in manifests) {
      manifest.previews
        .filterNot { it.includeInA11y }
        .mapTo(excluded) { "${module.gradlePath}/${it.id}" }
      // Prefer a manifest pointer when one was stamped, else the conventional `accessibility.json`
      // location — the usual path now, since the Gradle plugin no longer writes the pointer.
      val pointer = manifest.reportsView[id]
      val reportFile =
        pointer?.let { module.projectDir.resolve("build/compose-previews/$it") }
          ?: module.projectDir.resolve("build/compose-previews/accessibility.json")
      if (!reportFile.exists()) continue
      enabled += module.gradlePath
      val report =
        try {
          val text = fileSystem.read(reportFile.path.toPath()) { readUtf8() }
          json.decodeFromString(AccessibilityReport.serializer(), text)
        } catch (e: Exception) {
          if (verbose) {
            System.err.println("Warning: unreadable a11y report ${reportFile.path}: ${e.message}")
          }
          continue
        }
      if (report.partial) partial += module.gradlePath
      val reportDir = reportFile.parentFile
      for (entry in report.entries) {
        val annotatedAbs =
          entry.annotatedPath
            ?.let { reportDir.resolve(it).canonicalFile }
            ?.takeIf { it.exists() }
            ?.absolutePath
        // Absolute `annotatedPath` so consumers needn't know the sidecar dir; null when none was
        // produced.
        out["${module.gradlePath}/${entry.previewId}"] = entry.copy(annotatedPath = annotatedAbs)
      }
    }
    a11yByKey = out
    enabledModules = enabled
    partialModules = partial
    excludedPreviewKeys = excluded
    return enabled
  }

  override fun annotate(result: PreviewResult, module: PreviewModule): PreviewResult {
    if (module.gradlePath !in enabledModules) return result
    val key = "${module.gradlePath}/${result.id}"
    if (key in excludedPreviewKeys) return result
    val listed = a11yByKey[key]
    // Unlisted preview in a partial report: never checked, so leave the carrier off (null = didn't
    // run).
    if (listed == null && module.gradlePath in partialModules) return result
    // Module enabled but nothing listed: an empty entry means "ran, found nothing", unlike null.
    val entry = listed ?: AccessibilityEntry(previewId = result.id, findings = emptyList())
    val payload =
      ExtensionPayload(
        schema = A11Y_PAYLOAD_SCHEMA_V1,
        payload = json.encodeToJsonElement(AccessibilityEntry.serializer(), entry),
      )
    return result.copy(dataExtensions = result.dataExtensions + (id to payload))
  }

  override fun hasData(result: PreviewResult): Boolean = result.a11yEntry() != null

  override fun printAll(filtered: List<PreviewResult>) {
    val totalFindings = filtered.sumOf { it.a11yEntry()?.findings?.size ?: 0 }
    println("$totalFindings accessibility finding(s):")
    for (result in filtered) {
      val entry = result.a11yEntry() ?: continue
      var annotatedPrinted = false
      for (f in entry.findings) {
        println("  [${f.level}] ${result.id} · ${f.type}")
        println("      ${f.message}")
        f.viewDescription?.let { println("      element: $it") }
        if (!annotatedPrinted) {
          entry.annotatedPath?.let { println("      annotated: $it") }
          annotatedPrinted = true
        }
      }
    }
  }

  override fun printEmpty() {
    println("No accessibility findings.")
  }

  override fun thresholdExitCode(results: List<PreviewResult>, failOn: String?): Int? {
    val errorCount = results.sumOf {
      it.a11yEntry()?.findings?.count { f -> f.level == "ERROR" } ?: 0
    }
    val warnCount = results.sumOf {
      it.a11yEntry()?.findings?.count { f -> f.level == "WARNING" } ?: 0
    }
    return a11yExitCode(
        buildOk = true,
        errorCount = errorCount,
        warnCount = warnCount,
        failOn = failOn,
      )
      .takeIf { it != 0 }
  }
}

/** Json decoder shared by every `a11yEntry()` call — Json instances are expensive to construct. */
private val a11yDecodeJson = Json { ignoreUnknownKeys = true }

/**
 * Decode the `dataExtensions["a11y"]` payload into an [AccessibilityEntry], or null when absent,
 * off-schema or undecodable. Null means "checks didn't run"; empty `findings` means "ran, clean".
 * Also used by `Commands.kt`'s `--brief` encoder.
 */
internal fun PreviewResult.a11yEntry(): AccessibilityEntry? {
  val payload = dataExtensions["a11y"] ?: return null
  if (payload.schema != A11Y_PAYLOAD_SCHEMA_V1) return null
  return runCatching {
    a11yDecodeJson.decodeFromJsonElement(AccessibilityEntry.serializer(), payload.payload)
  }
    .getOrNull()
}

/** a11y finding count for `--brief`; null when ATF didn't run. */
internal fun decodeA11yFindingsCount(result: PreviewResult): Int? =
  result.a11yEntry()?.findings?.size

/** Sentinel returned by [a11yExitCode] when `failOn` is not one of the accepted values. */
internal const val EXIT_UNKNOWN_FAIL_ON = 1

/**
 * Exit-code policy for `compose-preview a11y` (top-level so `A11yCommandTest` can call it):
 * - `0` — build succeeded, threshold not tripped.
 * - `2` — Gradle build failed, or the `--fail-on` threshold tripped.
 * - [EXIT_UNKNOWN_FAIL_ON] (`1`) — `failOn` isn't `errors` / `warnings` / `none`; the caller prints
 *   the message.
 *
 * `null`/`"none"` never trips; `"errors"` trips on any ERROR; `"warnings"` on any ERROR or WARNING.
 */
internal fun a11yExitCode(buildOk: Boolean, errorCount: Int, warnCount: Int, failOn: String?): Int {
  val cliFailed =
    when (failOn) {
      "errors" -> errorCount > 0
      "warnings" -> errorCount > 0 || warnCount > 0
      "none",
      null -> false
      else -> return EXIT_UNKNOWN_FAIL_ON
    }
  return when {
    cliFailed -> 2
    !buildOk -> 2
    else -> 0
  }
}
