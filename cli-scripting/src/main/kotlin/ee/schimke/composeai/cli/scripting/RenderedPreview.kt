package ee.schimke.composeai.cli.scripting

import ee.schimke.composeai.cli.A11Y_PAYLOAD_SCHEMA_V1
import ee.schimke.composeai.cli.AccessibilityEntry
import ee.schimke.composeai.cli.AccessibilityFinding
import ee.schimke.composeai.cli.CaptureResult
import ee.schimke.composeai.cli.PreviewResult
import kotlinx.serialization.json.Json

/**
 * Script-facing handle for a rendered preview. Returned by `show("id")` / `previews()` on
 * [ComposePreviewScript]. Wraps the CLI-internal [PreviewResult] so the script API can evolve
 * independently of the on-disk JSON wire format `compose-preview show --json` emits.
 *
 * Today this is mostly a thin delegating projection. The interesting shape lives on the extension
 * sub-handles ([a11y] etc.) — `ui.a11y.errors.isNotEmpty()` reads at the right level of intent
 * compared to `result.a11yFindings?.filter { it.level == "ERROR" }?.isNotEmpty()`.
 *
 * Interactive sub-handles (`ui.keyboard.type(...)`, `ui.uia.byText(...).hasFocus`) sketched in the
 * shape-C discussion are deliberately absent from this MVP — they require live-session JSON-RPC
 * plumbing to the daemon's data products that doesn't exist yet. Tracked alongside the lazy-fetch
 * story on issue #1084.
 */
class RenderedPreview internal constructor(internal val backing: PreviewResult) {
  /** Stable preview identifier (typically `<ClassName>.<functionName>` or a custom name). */
  val id: String
    get() = backing.id

  /** Gradle path of the module the preview lives in (`:app`, `:samples:wear`, …). */
  val module: String
    get() = backing.module

  /** Composable function name (without class qualifier). */
  val functionName: String
    get() = backing.functionName

  /** Fully qualified container class name. */
  val className: String
    get() = backing.className

  /**
   * Source file path, relative to the module root, or `null` when discovery couldn't resolve it.
   */
  val sourceFile: String?
    get() = backing.sourceFile

  /**
   * All rendered snapshots for this preview. Length > 1 indicates an animation time fan-out or a
   * scroll-with-progress capture. Static previews have a single entry.
   */
  val captures: List<CaptureResult>
    get() = backing.captures

  /** First capture's PNG path, mirroring [PreviewResult.pngPath] for back-compat / quick access. */
  val pngPath: String?
    get() = backing.pngPath

  /** First capture's sha256, or `null` when no PNG was produced. */
  val sha256: String?
    get() = backing.sha256

  /**
   * True if the first capture's sha256 differs from the prior run's, false if unchanged, `null` for
   * first-run / missing-PNG cases. Mirrors [PreviewResult.changed].
   */
  val changed: Boolean?
    get() = backing.changed

  /**
   * Accessibility-extension data. Decoded lazily from `backing.dataExtensions["a11y"]` against the
   * `:preview-data-api` wire mirror of `:data-a11y-core`'s `AccessibilityEntry`. Findings are
   * `null` (not empty list) when ATF wasn't enabled for this preview's module — the script can
   * distinguish "checks ran and found nothing" from "no checks ran" via [A11yHandle.ran].
   *
   * Note: this is the *clean* path through `dataExtensions`. The deprecated `a11yFindings` field on
   * `PreviewResult` still exists for one release as the v1 → v2 deprecation slope; contrib
   * scripting deliberately doesn't read it, so the shape demonstrates the published-API contract a
   * third-party consumer should follow.
   */
  val a11y: A11yHandle by lazy { decodeA11y(backing) }

  override fun toString(): String = "RenderedPreview(id=$id, module=$module)"

  internal companion object {
    private val a11yJson = Json { ignoreUnknownKeys = true }

    private fun decodeA11y(result: PreviewResult): A11yHandle {
      val payload = result.dataExtensions["a11y"] ?: return A11yHandle(findings = null)
      // Pin to the v1 schema string. A future v2 payload would deliberately NOT decode against
      // the v1 entry shape — the contract is "string-equal the schema constant or fall back to
      // unknown." For MVP we only know about v1.
      if (payload.schema != A11Y_PAYLOAD_SCHEMA_V1) return A11yHandle(findings = null)
      val entry =
        runCatching {
            a11yJson.decodeFromJsonElement(AccessibilityEntry.serializer(), payload.payload)
          }
          .getOrNull() ?: return A11yHandle(findings = null)
      return A11yHandle(findings = entry.findings)
    }
  }
}

/**
 * Script-facing accessibility extension data. Aliases over the underlying
 * `List<AccessibilityFinding>?` shape, surfaced at the level scripts actually want to operate on:
 * `ui.a11y.errors.isNotEmpty()` rather than the awkward null + level-string-filter dance.
 */
class A11yHandle internal constructor(val findings: List<AccessibilityFinding>?) {

  /**
   * True iff ATF actually ran for this preview's module. Distinguishes "checks ran, nothing
   * tripped" ([findings] is empty list) from "checks were disabled for this module" ([findings] is
   * null) — both yield empty [errors] / [warnings] otherwise.
   */
  val ran: Boolean
    get() = findings != null

  /** ATF findings at `level == "ERROR"`. Empty list when no errors, or when ATF didn't run. */
  val errors: List<AccessibilityFinding>
    get() = findings?.filter { it.level == "ERROR" } ?: emptyList()

  /** ATF findings at `level == "WARNING"`. Empty list when none, or when ATF didn't run. */
  val warnings: List<AccessibilityFinding>
    get() = findings?.filter { it.level == "WARNING" } ?: emptyList()

  /** Convenience for the common `if (ui.a11y.hasErrors) fail(…)` shape. */
  val hasErrors: Boolean
    get() = errors.isNotEmpty()
}
