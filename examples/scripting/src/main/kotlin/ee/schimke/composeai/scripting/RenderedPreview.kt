package ee.schimke.composeai.scripting

// Using the `:preview-data-api` mirror types during the v1 → v2 deprecation window — see
// `A11yWireFormat.kt`. After v2 bumps, swap to `:data-a11y-core`'s `ee.schimke.composeai.renderer`
// types (those are the canonical shape; the mirror disappears at the same time the deprecated
// `PreviewResult.a11yFindings` field does).
import ee.schimke.composeai.cli.A11Y_PAYLOAD_SCHEMA_V1
import ee.schimke.composeai.cli.AccessibilityEntry
import ee.schimke.composeai.cli.AccessibilityFinding
import ee.schimke.composeai.cli.CaptureResult
import ee.schimke.composeai.cli.PreviewResult
import kotlinx.serialization.json.Json

/**
 * Script-facing handle for a rendered preview. Returned by `show("id")` / `previews()` on
 * [ComposePreviewScript]. Wraps the published [PreviewResult] so the script API can evolve
 * independently of the on-disk JSON wire format.
 *
 * Today this is mostly a thin delegating projection. The interesting shape lives on the extension
 * sub-handles ([a11y] etc.) — `ui.a11y.errors.isNotEmpty()` reads at the right level of intent
 * compared to decoding `result.dataExtensions["a11y"]` by hand.
 *
 * Interactive sub-handles (`ui.keyboard.type(...)`, `ui.uia.byText(...).hasFocus`) sketched in the
 * shape-C discussion are deliberately absent — they require live-session JSON-RPC plumbing to the
 * daemon's data products that this reference implementation doesn't reach for. Future contrib work.
 */
class RenderedPreview internal constructor(internal val backing: PreviewResult) {
  val id: String
    get() = backing.id

  val module: String
    get() = backing.module

  val functionName: String
    get() = backing.functionName

  val className: String
    get() = backing.className

  val sourceFile: String?
    get() = backing.sourceFile

  val captures: List<CaptureResult>
    get() = backing.captures

  val pngPath: String?
    get() = backing.pngPath

  val sha256: String?
    get() = backing.sha256

  val changed: Boolean?
    get() = backing.changed

  /**
   * Accessibility-extension data. Decoded lazily from `backing.dataExtensions["a11y"]` against
   * `:data-a11y-core`'s `AccessibilityEntry`. Findings are `null` (not empty list) when ATF wasn't
   * enabled for this preview's module — the script can distinguish "checks ran and found nothing"
   * from "no checks ran" via [A11yHandle.ran].
   *
   * This is the proof point: the script consumes a typed view backed by the published API. No
   * `:cli` dependency, no special-case wiring inside the driver.
   */
  val a11y: A11yHandle by lazy { decodeA11y(backing) }

  override fun toString(): String = "RenderedPreview(id=$id, module=$module)"

  internal companion object {
    private val a11yJson = Json { ignoreUnknownKeys = true }

    private fun decodeA11y(result: PreviewResult): A11yHandle {
      val payload = result.dataExtensions["a11y"] ?: return A11yHandle(findings = null)
      // Pin to the v1 schema string. A future v2 payload deliberately won't decode against the
      // v1 entry shape — string-equal the schema constant or fall back to unknown.
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
 * Script-facing accessibility extension data. Aliases over `List<AccessibilityFinding>?`, surfaced
 * at the level scripts actually want to operate on: `ui.a11y.errors.isNotEmpty()` rather than the
 * awkward null + level-string-filter dance.
 */
class A11yHandle internal constructor(val findings: List<AccessibilityFinding>?) {

  /**
   * True iff ATF actually ran for this preview's module. Distinguishes "checks ran, nothing
   * tripped" ([findings] is empty list) from "checks were disabled for this module" ([findings] is
   * null).
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
