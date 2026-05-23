// Suppress at file scope: this is the deprecation slope. `A11yReportRenderer` populates and reads
// `PreviewResult.a11yFindings` / `a11yAnnotatedPath` *by design* during the v1 → v2 window,
// alongside the new `dataExtensions["a11y"]` payload. Once the deprecated fields are removed,
// this file-level suppress goes away too.
@file:Suppress("DEPRECATION")

package ee.schimke.composeai.cli

import kotlinx.serialization.json.Json

/*
 * On-disk shape mirrors the daemon-side aggregation in
 * `ee.schimke.composeai.daemon.AccessibilityDataProductRegistry`. The standalone gradle path no
 * longer produces this file; it's strictly a daemon-mode artefact now.
 *
 * The DTOs (`AccessibilityFinding`, `AccessibilityEntry`, `AccessibilityReport`) carved out to
 * `:preview-data-api` alongside `PreviewResult` — they're the wire-format mirrors that the
 * deprecated `PreviewResult.a11yFindings` references. See `preview-data-api/A11yWireFormat.kt`.
 */

/**
 * [ExtensionReportRenderer] for the built-in `a11y` extension. Reads each module's
 * `accessibility.json`, joins per-preview findings onto [PreviewResult.a11yFindings] (kept on the
 * result for back-compat with the v1 `compose-preview-show/v1` JSON wire format), and prints
 * findings grouped by preview with optional `--fail-on` thresholding.
 *
 * Owned state: [a11yByKey] is the per-preview lookup the [annotate] step reads from. It's built by
 * [load] and cached for the duration of one CLI invocation.
 */
class A11yReportRenderer : ExtensionReportRenderer {
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

  /** `"<module>/<previewId>"` -> (findings, absolute annotated PNG path). */
  private var a11yByKey: Map<String, Pair<List<AccessibilityFinding>, String?>> = emptyMap()

  /** Set of module gradle-paths whose manifest claims a11y is enabled (pointer non-null). */
  private var enabledModules: Set<String> = emptySet()

  override fun load(
    manifests: List<Pair<PreviewModule, PreviewManifest>>,
    verbose: Boolean,
  ): Set<String> {
    val out = mutableMapOf<String, Pair<List<AccessibilityFinding>, String?>>()
    val enabled = mutableSetOf<String>()
    for ((module, manifest) in manifests) {
      // Prefer the manifest pointer when a producer stamped one (legacy gradle-aggregated reports,
      // future daemon-stamped pointer); fall back to the conventional `accessibility.json`
      // location so a freshly-written daemon-aggregated report is still picked up even when no
      // producer touched the manifest. The standalone gradle plugin no longer writes the
      // pointer at all — that's a daemon / CLI concern now — so the fallback is the primary
      // path for `compose-preview a11y`.
      val pointer = manifest.reportsView[id]
      val reportFile =
        pointer?.let { module.projectDir.resolve("build/compose-previews/$it") }
          ?: module.projectDir.resolve("build/compose-previews/accessibility.json")
      if (!reportFile.exists()) continue
      enabled += module.gradlePath
      val report =
        try {
          json.decodeFromString(AccessibilityReport.serializer(), reportFile.readText())
        } catch (e: Exception) {
          if (verbose) {
            System.err.println("Warning: unreadable a11y report ${reportFile.path}: ${e.message}")
          }
          continue
        }
      val reportDir = reportFile.parentFile
      for (entry in report.entries) {
        val annotatedAbs =
          entry.annotatedPath
            ?.let { reportDir.resolve(it).canonicalFile }
            ?.takeIf { it.exists() }
            ?.absolutePath
        out["${module.gradlePath}/${entry.previewId}"] = entry.findings to annotatedAbs
      }
    }
    a11yByKey = out
    enabledModules = enabled
    return enabled
  }

  override fun annotate(result: PreviewResult, module: PreviewModule): PreviewResult {
    if (module.gradlePath !in enabledModules) return result
    val pair = a11yByKey["${module.gradlePath}/${result.id}"]
    val findings = pair?.first ?: emptyList()
    val annotatedPath = pair?.second
    // Populate the new generic `dataExtensions["a11y"]` carrier alongside the deprecated
    // `a11yFindings` / `a11yAnnotatedPath` fields. Both are written for one release so external
    // consumers can migrate to decoding from `dataExtensions` against `:data-a11y-core`'s
    // typed DTOs at their own pace.
    val a11yPayload =
      ExtensionPayload(
        schema = A11Y_PAYLOAD_SCHEMA_V1,
        payload =
          json.encodeToJsonElement(
            AccessibilityEntry.serializer(),
            AccessibilityEntry(
              previewId = result.id,
              findings = findings,
              annotatedPath = annotatedPath,
            ),
          ),
      )
    // Module had a11y enabled but no findings for this preview: empty list (not null) tells
    // downstream consumers "checks ran and found nothing" vs "feature off."
    return result.copy(
      a11yFindings = findings,
      a11yAnnotatedPath = annotatedPath,
      dataExtensions = result.dataExtensions + (id to a11yPayload),
    )
  }

  override fun hasData(result: PreviewResult): Boolean = result.a11yFindings != null

  override fun printAll(filtered: List<PreviewResult>) {
    val totalFindings = filtered.sumOf { it.a11yFindings?.size ?: 0 }
    println("$totalFindings accessibility finding(s):")
    for (result in filtered) {
      val findings = result.a11yFindings ?: continue
      var annotatedPrinted = false
      for (f in findings) {
        println("  [${f.level}] ${result.id} · ${f.type}")
        println("      ${f.message}")
        f.viewDescription?.let { println("      element: $it") }
        if (!annotatedPrinted) {
          result.a11yAnnotatedPath?.let { println("      annotated: $it") }
          annotatedPrinted = true
        }
      }
    }
  }

  override fun printEmpty() {
    println("No accessibility findings.")
  }

  override fun thresholdExitCode(results: List<PreviewResult>, failOn: String?): Int? {
    val errorCount = results.sumOf { it.a11yFindings?.count { f -> f.level == "ERROR" } ?: 0 }
    val warnCount = results.sumOf { it.a11yFindings?.count { f -> f.level == "WARNING" } ?: 0 }
    return a11yExitCode(
        buildOk = true,
        errorCount = errorCount,
        warnCount = warnCount,
        failOn = failOn,
      )
      .takeIf { it != 0 }
  }
}

/**
 * Schema string stamped into `ExtensionPayload.schema` for the `a11y` entry of
 * `PreviewResult.dataExtensions`. Pinned — consumers should string-equal this constant rather than
 * parsing it. Bump to `/v2` when the underlying `AccessibilityEntry` shape breaks.
 */
const val A11Y_PAYLOAD_SCHEMA_V1: String = "compose-preview-data-a11y/v1"

/** Sentinel returned by [a11yExitCode] when `failOn` is not one of the accepted values. */
internal const val EXIT_UNKNOWN_FAIL_ON = 1

/**
 * Pure exit-code policy for `compose-preview a11y`. Kept top-level (not on [A11yReportRenderer]) so
 * the existing unit-test matrix in `A11yCommandTest` stays callable without instantiating a
 * renderer. Same semantics as before the strategy refactor:
 * - `0` — clean run, build succeeded, threshold not tripped.
 * - `2` — Gradle build failed, OR the CLI-side `--fail-on` threshold tripped.
 * - [EXIT_UNKNOWN_FAIL_ON] (`1`) — `failOn` is set to something other than `errors` / `warnings` /
 *   `none`. Caller is responsible for printing the user-facing message.
 *
 * `failOn` semantics: `null`/`"none"` never trip on findings; `"errors"` trips on any ERROR;
 * `"warnings"` trips on any ERROR or WARNING.
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
