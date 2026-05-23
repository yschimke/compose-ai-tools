package ee.schimke.composeai.scripting

import ee.schimke.composeai.cli.A11Y_PAYLOAD_SCHEMA_V1
import ee.schimke.composeai.cli.AccessibilityEntry
import ee.schimke.composeai.cli.AccessibilityReport
import ee.schimke.composeai.cli.DriverOptions
import ee.schimke.composeai.cli.ExtensionPayload
import ee.schimke.composeai.cli.GradlePreviewDriver
import ee.schimke.composeai.cli.PreviewManifest
import ee.schimke.composeai.cli.PreviewModule
import ee.schimke.composeai.cli.PreviewResult
import ee.schimke.composeai.cli.RenderRequest
import java.io.File
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json

/**
 * Standalone `compose-preview-scripting <path.composepreview.kts>` binary. Validates the clean-API
 * carve-out: this entire entry point uses only published modules (`:preview-data-api` +
 * `:gradle-preview-driver` + `:data-a11y-core`), no dependency on `:cli` or any other CLI-internal
 * infrastructure.
 *
 * Flow:
 * 1. Find the Gradle project root (walk upward for `gradlew`).
 * 2. Open a [GradlePreviewDriver] rooted there.
 * 3. Discover preview modules and render them with `--with-extension a11y` enabled.
 * 4. Annotate results with the a11y `dataExtensions["a11y"]` payload (the CLI does this via its
 *    `A11yReportRenderer`; we inline the same logic here so contrib doesn't take a `:cli` dep).
 * 5. Compile and evaluate the script; print any `fail(...)` messages; exit 2 if any.
 *
 * This is the reference implementation `yschimke/compose-ai-contrib` will lift wholesale into its
 * own published module.
 */
fun main(args: Array<String>) {
  val scriptPath = pickScriptPath(args)
  if (scriptPath == null) {
    System.err.println("Usage: compose-preview-scripting <path.composepreview.kts>")
    exitProcess(1)
  }
  val scriptFile = File(scriptPath)
  if (!scriptFile.exists()) {
    System.err.println("compose-preview-scripting: not found: $scriptPath")
    exitProcess(1)
  }

  val projectRoot = findProjectRoot(File("").absoluteFile)
  if (projectRoot == null) {
    System.err.println("compose-preview-scripting: no gradlew found walking up from cwd")
    exitProcess(1)
  }

  val verbose = "--verbose" in args || "-v" in args
  GradlePreviewDriver(projectRoot, DriverOptions(verbose = verbose)).use { driver ->
    val modules = driver.discoverModules()
    if (modules.isEmpty()) {
      System.err.println(
        "compose-preview-scripting: no modules with the compose-preview plugin found."
      )
      exitProcess(1)
    }

    val outcome = driver.render(RenderRequest(modules = modules, extensions = setOf("a11y")))
    if (!outcome.buildOk) {
      System.err.println("compose-preview-scripting: render failed")
      exitProcess(2)
    }

    val annotated = annotateA11y(outcome.previews, outcome.manifests)
    val state = ScriptState()
    for (result in annotated) state.results[result.id] = RenderedPreview(result)

    when (val r = ScriptRunner.evaluate(scriptFile, state)) {
      is ScriptRunner.Outcome.Failed -> {
        System.err.println("compose-preview-scripting: failed to evaluate $scriptPath")
        System.err.println(r.message)
        exitProcess(1)
      }
      is ScriptRunner.Outcome.Ok -> {} // fall through to fail-accumulation check
    }

    if (state.failures.isNotEmpty()) {
      for (msg in state.failures) System.err.println("compose-preview-scripting: $msg")
      exitProcess(2)
    }
  }
}

/**
 * Pick the script path from [args]. Prefers a `*.composepreview.kts` / `*.kts` ending so an
 * interleaved flag's value doesn't pollute the picker; falls back to the first non-flag arg.
 */
internal fun pickScriptPath(args: Array<String>): String? {
  val byExtension = args.firstOrNull {
    !it.startsWith("-") && (it.endsWith(".kts") || it.endsWith(".kt"))
  }
  if (byExtension != null) return byExtension
  return args.firstOrNull { !it.startsWith("-") }
}

/**
 * Walk upward from [start] until a `gradlew` script is found, marking the Gradle project root.
 * Returns `null` when no gradlew is reachable — the caller treats that as "not in a Gradle
 * project."
 */
internal fun findProjectRoot(start: File): File? {
  var dir: File? = start
  while (dir != null) {
    if (File(dir, "gradlew").exists()) return dir
    dir = dir.parentFile
  }
  return null
}

private val a11yJson = Json { ignoreUnknownKeys = true }

/**
 * Annotate every [results] entry with `dataExtensions["a11y"]` populated from the per-module
 * `accessibility.json` sidecar. Mirrors what the CLI's `A11yReportRenderer.annotate` does — we
 * inline it here so contrib doesn't depend on `:cli`.
 *
 * Sidecar location follows the manifest pointer when set, falls back to the conventional
 * `build/compose-previews/accessibility.json` path otherwise — same dual-path logic the CLI uses.
 * Unreadable sidecars are silently skipped; this is best-effort enrichment, not a hard gate.
 */
internal fun annotateA11y(
  results: List<PreviewResult>,
  manifests: List<Pair<PreviewModule, PreviewManifest>>,
): List<PreviewResult> {
  // module gradle path → per-preview AccessibilityEntry payload, encoded once per module.
  val byModule: Map<String, Map<String, ExtensionPayload>> =
    manifests.mapNotNull { (module, manifest) -> loadModuleA11y(module, manifest) }.toMap()
  return results.map { result ->
    val perId = byModule[result.module] ?: return@map result
    val payload = perId[result.id] ?: return@map result
    result.copy(dataExtensions = result.dataExtensions + ("a11y" to payload))
  }
}

private fun loadModuleA11y(
  module: PreviewModule,
  manifest: PreviewManifest,
): Pair<String, Map<String, ExtensionPayload>>? {
  val pointer = manifest.dataExtensionReports["a11y"]
  val reportFile =
    pointer?.let { module.projectDir.resolve("build/compose-previews/$it") }
      ?: module.projectDir.resolve("build/compose-previews/accessibility.json")
  if (!reportFile.exists()) return null
  val report =
    runCatching {
        a11yJson.decodeFromString(AccessibilityReport.serializer(), reportFile.readText())
      }
      .getOrNull() ?: return null
  // Re-encode each entry as its own ExtensionPayload — the script's `RenderedPreview.a11y`
  // handle expects per-preview shape, not the per-module `AccessibilityReport`.
  val perId =
    report.entries.associate { entry ->
      entry.previewId to
        ExtensionPayload(
          schema = A11Y_PAYLOAD_SCHEMA_V1,
          payload = a11yJson.encodeToJsonElement(AccessibilityEntry.serializer(), entry),
        )
    }
  return module.gradlePath to perId
}
