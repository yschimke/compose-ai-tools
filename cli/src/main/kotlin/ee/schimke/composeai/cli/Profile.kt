package ee.schimke.composeai.cli

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * On-disk shape of a `compose-preview profile <path>` JSON file: a named bundle of CLI options
 * (extensions, preview filter, per-extension fail thresholds), so a team can rerun a standard check
 * by name. Schema `compose-preview-profile/v1`.
 *
 * Deliberately a thin wrapper: [ProfileCommand] synthesises the equivalent flags and delegates to
 * [ReportCommand], so a profile is exactly "what you'd have typed".
 */
@Serializable
data class Profile(
  /**
   * Schema pin, `"compose-preview-profile/v1"`; unknown majors are rejected. Optional in JSON
   * (defaults to v1).
   */
  val schema: String = PROFILE_SCHEMA_V1,
  /**
   * Data extensions to enable, as `--with-extension <id>` each (forwarded as
   * `-PcomposePreview.previewExtensions.<id>.enableAllChecks=true`). May be empty.
   */
  val extensions: List<String> = emptyList(),
  /**
   * Preview-set filter, as `--module` / `--filter` / `--changed-only`; null means no filter on that
   * axis.
   */
  val filter: ProfileFilter = ProfileFilter(),
  /**
   * Per-extension `--fail-on` thresholds (`"errors"` / `"warnings"` / `"none"`). Only the [report]
   * extension's entry is honoured today.
   */
  val failOn: Map<String, String> = emptyMap(),
  /**
   * Which extension's report to render; defaults to the first of [extensions]. Must be a registered
   * [ExtensionReportRenderer] id ([ProfileCommand.resolveReportExtension] exits 1 otherwise).
   */
  val report: String? = null,
)

/** Filter knobs mirrored from the CLI's existing global flag set; null means "no filter." */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ProfileFilter(
  /** Gradle module path (`:app`, `samples:wear`). Mirrors `--module`. */
  val module: String? = null,
  /** Case-insensitive substring match on preview id, like `--filter`; not a glob. */
  val idSubstring: String? = null,
  /** Exact preview id match. Mirrors `--id`. */
  val id: String? = null,
  /** Drop previews with no `changed=true` capture. Mirrors `--changed-only`. */
  @EncodeDefault val changedOnly: Boolean = false,
)

internal const val PROFILE_SCHEMA_V1 = "compose-preview-profile/v1"

/** Schema majors the parser will accept. Add entries when introducing v2-compatible shapes. */
internal val ACCEPTED_PROFILE_SCHEMAS: Set<String> = setOf(PROFILE_SCHEMA_V1)
