package ee.schimke.composeai.cli

import kotlinx.serialization.json.Json

/*
 * On-disk shape mirrors the daemon-side aggregation in
 * `ee.schimke.composeai.daemon.AccessibilityDataProductRegistry`. The standalone gradle path no
 * longer produces this file; it's strictly a daemon-mode artefact now.
 *
 * The DTOs (`AccessibilityFinding`, `AccessibilityEntry`, `AccessibilityReport`) live in
 * `:preview-data-api/A11yWireFormat.kt` — they're the JVM-side typed-decode surface for the
 * `compose-preview-data-a11y/v1` payload body. The renderer-side `:data-a11y-core` ships the
 * canonical types in `ee.schimke.composeai.renderer`; this module mirrors them so JVM consumers
 * (CLI, contrib) don't have to pull an `android-library` to decode.
 */

/**
 * [ExtensionReportRenderer] for the built-in `a11y` extension. Reads each module's
 * `accessibility.json`, packages each entry as a `dataExtensions["a11y"]` payload on the matching
 * [PreviewResult], and prints findings grouped by preview with optional `--fail-on` thresholding.
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

  /** `"<module>/<previewId>"` -> the decoded entry (findings + absolute annotated PNG path). */
  private var a11yByKey: Map<String, AccessibilityEntry> = emptyMap()

  /** Set of module gradle-paths whose manifest claims a11y is enabled (pointer non-null). */
  private var enabledModules: Set<String> = emptySet()

  override fun load(
    manifests: List<Pair<PreviewModule, PreviewManifest>>,
    verbose: Boolean,
  ): Set<String> {
    val out = mutableMapOf<String, AccessibilityEntry>()
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
        // Resolve `annotatedPath` to an absolute path now so downstream consumers don't have
        // to know the sidecar dir. `null` when the renderer didn't produce one — same signal as
        // before.
        out["${module.gradlePath}/${entry.previewId}"] = entry.copy(annotatedPath = annotatedAbs)
      }
    }
    a11yByKey = out
    enabledModules = enabled
    return enabled
  }

  override fun annotate(result: PreviewResult, module: PreviewModule): PreviewResult {
    if (module.gradlePath !in enabledModules) return result
    // Module had a11y enabled but no findings for this preview: empty entry (not null) tells
    // downstream consumers "checks ran and found nothing" vs "feature off."
    val entry =
      a11yByKey["${module.gradlePath}/${result.id}"]
        ?: AccessibilityEntry(previewId = result.id, findings = emptyList())
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
 * Decode the `dataExtensions["a11y"]` payload (if any) into a typed [AccessibilityEntry]. Returns
 * `null` when ATF didn't run for this preview's module (no payload), the payload's schema doesn't
 * match the v1 pin, or the body fails to decode. Same null-vs-empty semantics every consumer in
 * this file relies on: `null` means "checks didn't run"; an entry with empty `findings` means
 * "checks ran, nothing tripped."
 *
 * Internal — also used by `Commands.kt`'s `--brief` encoder for the a11y count.
 */
internal fun PreviewResult.a11yEntry(): AccessibilityEntry? {
  val payload = dataExtensions["a11y"] ?: return null
  if (payload.schema != A11Y_PAYLOAD_SCHEMA_V1) return null
  return runCatching {
      a11yDecodeJson.decodeFromJsonElement(AccessibilityEntry.serializer(), payload.payload)
    }
    .getOrNull()
}

/**
 * a11y-finding count for `--brief` output. `null` when ATF didn't run for the preview's module,
 * matching the v1 wire-format semantics agents already grep for.
 */
internal fun decodeA11yFindingsCount(result: PreviewResult): Int? =
  result.a11yEntry()?.findings?.size

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
