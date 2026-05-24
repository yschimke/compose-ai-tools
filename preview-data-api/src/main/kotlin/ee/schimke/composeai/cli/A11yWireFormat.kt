package ee.schimke.composeai.cli

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// v1 a11y wire-format mirror types. Lives alongside `PreviewResult` in
// `:preview-data-api` so the deprecated `PreviewResult.a11yFindings` /
// `a11yAnnotatedPath` fields have a published type to reference during the
// v1 → v2 deprecation window.
//
// These are STRUCTURALLY IDENTICAL to `:data-a11y-core`'s `AccessibilityFinding`
// / `AccessibilityEntry` / `AccessibilityReport` (package
// `ee.schimke.composeai.renderer`). The renderer-side module is the canonical
// definition — see
// [`AccessibilityModels.kt`](../../data/a11y/core/src/main/kotlin/ee/schimke/composeai/renderer/AccessibilityModels.kt).
// We mirror them here because:
//
//  - `:data-a11y-core` is an `android-library`, so the JVM-only
//    `:preview-data-api` can't depend on it without polluting the published
//    surface with Android-renderer-side transitive deps.
//  - The wire-format DTOs are stable enough that two mirrors don't drift in
//    practice — both are pinned by the JSON schema, not by Kotlin type
//    identity.
//
// Once the deprecation window closes, these mirrors disappear with the
// deprecated `PreviewResult` fields; consumers move to decoding
// `dataExtensions["a11y"]` against `:data-a11y-core`'s types directly.
// ---------------------------------------------------------------------------

@Serializable
data class AccessibilityFinding(
  val level: String,
  val type: String,
  val message: String,
  val viewDescription: String? = null,
  val boundsInScreen: String? = null,
)

@Serializable
data class AccessibilityEntry(
  val previewId: String,
  val findings: List<AccessibilityFinding>,
  val annotatedPath: String? = null,
)

@Serializable
data class AccessibilityReport(
  val module: String,
  val entries: List<AccessibilityEntry>,
  /**
   * Run-level status for this module's ATF pass. `null` for a normal run — `entries` reflects what
   * ATF returned, and an entry with empty `findings` means "checks ran, found nothing." Set to
   * [A11Y_REPORT_STATUS_ATF_UNAVAILABLE] when the daemon could not return ATF data for any preview
   * attempted (descriptor missing, render-session open failed, every per-preview fetch errored, …)
   * so downstream consumers can surface that rather than treating the empty findings list as a
   * clean run.
   */
  val status: String? = null,
)

/**
 * Schema string stamped into `ExtensionPayload.schema` for the `a11y` entry of
 * `PreviewResult.dataExtensions`. Pinned — consumers string-equal this constant rather than parsing
 * it. Bump to `/v2` when the underlying [AccessibilityEntry] shape breaks.
 *
 * Lives in `:preview-data-api` (not `:cli`) so external consumers (contrib scripting, third-party
 * tooling) can pin to it without taking a CLI dependency.
 */
const val A11Y_PAYLOAD_SCHEMA_V1: String = "compose-preview-data-a11y/v1"

/**
 * Value emitted in [AccessibilityReport.status] when ATF data could not be produced for any preview
 * in the module (daemon classpath issue, render-session open failed, every per-preview fetch
 * errored). The python helper and PR-comment renderer check for this string verbatim.
 */
const val A11Y_REPORT_STATUS_ATF_UNAVAILABLE: String = "atf-unavailable"
