package ee.schimke.composeai.cli

import ee.schimke.composeai.previewdata.PreviewManifest
import ee.schimke.composeai.previewdata.PreviewModule
import ee.schimke.composeai.previewdata.PreviewResult

/**
 * How a data extension contributes a `compose-preview <extension>` report without its DTOs, output
 * format or exit policy living in [Command] or [ReportCommand]. Stateful per invocation: [load]
 * reads the sidecars `:composePreviewRenderAll` wrote, [annotate] enriches each [PreviewResult],
 * and the print/threshold steps use that state. Today only [A11yReportRenderer].
 */
interface ExtensionReportRenderer {
  /**
   * Stable extension id: the [PreviewManifest.dataExtensionReports] key, the `--with-extension
   * <id>` value, and the `composePreview.previewExtensions.<id>.enableAllChecks` property segment.
   * Agents and CI pin to it; don't change it.
   */
  val id: String

  /** Short human-readable label for `compose-preview extensions list`. */
  val displayName: String

  /** One-sentence description for `compose-preview extensions list`. */
  val description: String

  /**
   * Read each module's sidecar JSON when [reportsView] points at one, caching decoded state.
   * Returns the gradle paths of modules with the extension enabled; empty means "no enabled
   * modules".
   */
  fun load(manifests: List<Pair<PreviewModule, PreviewManifest>>, verbose: Boolean): Set<String>

  /**
   * Enrich [result] with this extension's data (e.g. [A11yReportRenderer] sets `a11yFindings` /
   * `a11yAnnotatedPath`), returning a copy. Called once per result after [load].
   */
  fun annotate(result: PreviewResult, module: PreviewModule): PreviewResult

  /** True iff the enriched [result] has any data from this extension (for `--changed-only` etc). */
  fun hasData(result: PreviewResult): Boolean

  /**
   * Print the human-readable section for [filtered] (header, entries, summary). Each implementation
   * owns what its counts mean. Only called when [filtered] is non-empty.
   */
  fun printAll(filtered: List<PreviewResult>)

  /** Print the empty-state line for the human path, in the extension's own wording. */
  fun printEmpty()

  /**
   * 2 if [failOn] crosses this extension's threshold, 0 if not, [EXIT_UNKNOWN_FAIL_ON] for an
   * unknown [failOn], or null when no threshold is configured (the Gradle result decides).
   */
  fun thresholdExitCode(results: List<PreviewResult>, failOn: String?): Int?
}

/**
 * Every built-in [ExtensionReportRenderer], for `Main.kt`'s command dispatch and `compose-preview
 * extensions list`. A function so each invocation (and test) gets fresh instances.
 */
internal fun builtInExtensionReporters(): Map<String, () -> ExtensionReportRenderer> =
  mapOf("a11y" to { A11yReportRenderer() })
