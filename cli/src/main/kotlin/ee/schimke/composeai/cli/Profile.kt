package ee.schimke.composeai.cli

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * On-disk shape of a `compose-preview profile <path>` JSON file. A profile is a named bundle of
 * "things you'd otherwise type on the CLI" — which extensions to enable, how to filter the preview
 * set, and which per-extension thresholds to fail on — so teams can capture "our standard a11y
 * check for the auth module" once and re-run it via `compose-preview profile auth-a11y .json`
 * instead of re-typing the flag combination.
 *
 * Pinned schema: `compose-preview-profile/v1`. Profiles emit the field for forward-compat; v2 bumps
 * when the field set breaks (e.g. richer filter operators, multi-renderer reports).
 *
 * Implementation note: profiles are deliberately "thin wrapper over the CLI flag surface" — the
 * runner ([ProfileCommand]) synthesises a `--flag value` arg list and delegates to the existing
 * [ReportCommand]. That keeps the renderer / Gradle-drive code single-source-of-truth and makes
 * profiles trivially equivalent to "what you'd have typed by hand," which is the right shape for a
 * v1 feature. Richer cases (predicate filters, per-result hooks, multi-extension orchestration)
 * belong on the Kotlin-scripting follow-up (issue #1084), not here.
 */
@Serializable
data class Profile(
  /**
   * Schema pin. Always `"compose-preview-profile/v1"` for v1 files; the parser rejects unknown
   * majors. Optional in the JSON to keep hand-written profiles readable; defaults to v1.
   */
  val schema: String = PROFILE_SCHEMA_V1,
  /**
   * Data extensions to opt into for this run — equivalent to passing `--with-extension <id>` for
   * each. Forwards as `-PcomposePreview.previewExtensions.<id>.enableAllChecks=true` Gradle
   * properties on the spawned build (same path the CLI's `a11y` / `--with-extension` flags use).
   * Empty list is valid; the run still happens, just without extra extensions enabled.
   */
  val extensions: List<String> = emptyList(),
  /**
   * Preview-set filter. Equivalent to the global `--module` / `--filter` / `--changed-only` flags.
   * `null` (or omitted) means "no filter on that axis."
   */
  val filter: ProfileFilter = ProfileFilter(),
  /**
   * Per-extension `--fail-on` threshold map. Key is the extension id, value is `"errors"` /
   * `"warnings"` / `"none"` — the same strings the CLI's `--fail-on` flag accepts. Today only one
   * entry is honoured (the chosen [report] extension's), since the existing CLI surface exits on
   * the first failing extension; multi-extension fail-on is on the v2 roadmap with issue #1084.
   */
  val failOn: Map<String, String> = emptyMap(),
  /**
   * Which extension's canned report to render after the build. Defaults to the first entry of
   * [extensions]; explicit when the profile enables multiple extensions but only wants one
   * reported. Must reference a registered [ExtensionReportRenderer] id —
   * [ProfileCommand.resolveReportExtension] validates against [builtInExtensionReporters] and exits
   * 1 on miss.
   */
  val report: String? = null,
)

/** Filter knobs mirrored from the CLI's existing global flag set; null means "no filter." */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ProfileFilter(
  /** Gradle module path (`:app`, `samples:wear`). Mirrors `--module`. */
  val module: String? = null,
  /**
   * Case-insensitive **substring** match on preview id — same semantics as `--filter`. Note: this
   * is NOT a glob; `Home*` would match the literal string `Home*`. Glob support is a v2 candidate.
   */
  val idSubstring: String? = null,
  /** Exact preview id match. Mirrors `--id`. */
  val id: String? = null,
  /** Drop previews with no `changed=true` capture. Mirrors `--changed-only`. */
  @EncodeDefault val changedOnly: Boolean = false,
)

internal const val PROFILE_SCHEMA_V1 = "compose-preview-profile/v1"

/** Schema majors the parser will accept. Add entries when introducing v2-compatible shapes. */
internal val ACCEPTED_PROFILE_SCHEMAS: Set<String> = setOf(PROFILE_SCHEMA_V1)
