package ee.schimke.composeai.cli

/**
 * Strategy for "given a render outcome, surface a canned per-extension report" — the slot the CLI
 * gives each data extension to participate in the `compose-preview <extension>` command shape
 * without baking its DTOs / output format / exit-code policy into [Command] or [ReportCommand].
 *
 * Implementations are stateful within a single CLI invocation: [load] reads the on-disk sidecar
 * JSON written by `:renderAllPreviews`, [annotate] enriches each [PreviewResult] with whatever
 * fields make sense in the existing JSON wire format, and the print / threshold steps consume that
 * state. Each invocation gets a fresh instance, so implementations are free to cache decoded
 * payloads in fields.
 *
 * Today the only impl is [A11yReportRenderer]; "add a second canned report" is a question of
 * dropping a new file here plus a registry entry, not editing [Command] or [ReportCommand].
 */
interface ExtensionReportRenderer {
  /**
   * Stable extension id. Matches the manifest's [PreviewManifest.dataExtensionReports] key, the
   * `--with-extension <id>` flag value, and the `composePreview.previewExtensions.<id>
   * .enableAllChecks` Gradle property segment. Don't change this — agents and CI scripts pin to it.
   */
  val id: String

  /** Short human-readable label for `compose-preview extensions list`. */
  val displayName: String

  /** One-sentence description for `compose-preview extensions list`. */
  val description: String

  /**
   * Read every module's sidecar JSON for this extension if the manifest's [reportsView] points at
   * one. Caches decoded state internally so [annotate] / [hasData] / [printRow] can run without
   * re-parsing.
   *
   * Returns the set of module gradle-paths that had the extension enabled (manifest pointer
   * present). Empty result tells [ReportCommand] to emit the "no enabled modules" branch.
   */
  fun load(manifests: List<Pair<PreviewModule, PreviewManifest>>, verbose: Boolean): Set<String>

  /**
   * Enrich a [PreviewResult] with this extension's data — e.g. [A11yReportRenderer] sets
   * `a11yFindings` / `a11yAnnotatedPath`. Called once per result, after [load]. Returns the
   * enriched value (immutable; callers replace the original in their list).
   */
  fun annotate(result: PreviewResult, module: PreviewModule): PreviewResult

  /** True iff the enriched [result] has any data from this extension (for `--changed-only` etc). */
  fun hasData(result: PreviewResult): Boolean

  /**
   * Print the full human-readable section for [filtered] to stdout: any header line (e.g. "N
   * accessibility finding(s):"), one entry per preview, and any trailing summary. Owns its own
   * count semantics so implementations don't have to agree on what "N" means — a11y counts findings
   * (which can be > preview count when a preview has multiple findings); a future renderer might
   * count rows, captures, or something else.
   *
   * Called only when [filtered] is non-empty.
   */
  fun printAll(filtered: List<PreviewResult>)

  /**
   * Print the "no data" / empty-state line for the human path. Called once when [filtered] is
   * empty. Implementations choose the wording (`"No accessibility findings."` vs `"No theme
   * captures."`).
   */
  fun printEmpty()

  /**
   * Returns 2 if [failOn] crosses this extension's per-extension threshold given the enriched
   * results, 0 if not, or [EXIT_UNKNOWN_FAIL_ON] if [failOn] is unknown to the extension. `null`
   * means "no threshold configured" — exit code mirrors the underlying Gradle build.
   */
  fun thresholdExitCode(results: List<PreviewResult>, failOn: String?): Int?
}

/**
 * Lookup of every built-in [ExtensionReportRenderer]. Single source of truth for the
 * `compose-preview <name>` command dispatch in `Main.kt` plus the `compose-preview extensions list`
 * enumeration. New extensions register by appending to this map — no other CLI file changes.
 *
 * A function (not a `val`) so each CLI invocation gets fresh stateful renderer instances; tests
 * also rely on this for isolation.
 */
internal fun builtInExtensionReporters(): Map<String, () -> ExtensionReportRenderer> =
  mapOf("a11y" to ::A11yReportRenderer)
