package ee.schimke.composeai.cli

/**
 * Single source of truth for how argv flags consume values, used to locate the subcommand token in
 * [main] before any command-specific parsing runs.
 *
 * Command detection scans argv left-to-right for the first bare (non-`-`) token. A flag that takes
 * its value as the *following* token (`--module foo`) must be listed in [VALUE_FLAGS] so the scan
 * skips the value — otherwise the value is mistaken for the command (`compose-preview --module show
 * list` would pick `show`). Flags whose value is genuinely *optional* (`--images`, which means
 * "auto" with no value) must NOT be listed: they never consume a following token, so a bare token
 * after them is the command. A flag whose value is required but may be written attached
 * (`--force=<reason>`) still belongs in [VALUE_FLAGS] — the attached form is a single argv token
 * the scan skips anyway, and the space form (`--force <reason>`, supported by `flagValue` and
 * `ForceFlagTest`) must skip the reason so it isn't mistaken for the command.
 *
 * The invariant — every flag read through the shared [flagValue]/[flagValuesAll] helpers is
 * classified here as either value-consuming or attached/optional — is enforced by
 * `CliFlagsRegistryTest`, which scans the CLI sources. Add new shared-helper flags to one of the
 * two sets or that test fails. (Commands that parse flags bespoke — e.g. `render-matrix`'s
 * `axisValues` — are out of the test's scope; their value flags are still listed here so
 * global-position parsing stays correct, but the compiler/test won't remind you to add them.)
 */
internal object CliFlags {
  /**
   * Flags whose value is the next argv token; command detection skips both the flag and its value.
   */
  val VALUE_FLAGS: Set<String> =
    setOf(
      "--module",
      "--filter",
      "--id",
      "--output",
      "--timeout",
      "--plugin-version",
      "--fail-on",
      "--desc",
      "--mechanism",
      "--branch",
      "--remote",
      "--pr-number",
      "--message",
      "--raw-base",
      "--with-extension",
      "--with",
      "--missing-renders",
      "--variant",
      "--preview",
      "--script",
      "--out",
      "--format",
      "--fps",
      "--scale",
      "--overrides",
      "--host",
      "--port",
      "--token",
      "--export",
      "--bundles",
      "--accept-bundles-from",
      "--trust-store",
      "--revisions-allow",
      // bundle sign / verify / keygen (producer trust)
      "--key",
      "--key-id",
      "--producer",
      "--provenance-identity",
      "--provenance-type",
      "--trust",
      "--origin",
      // Required-reason escape hatch. Usually written attached (`--force=<reason>`), but the space
      // form `--force <reason>` is supported (ForceFlagTest), so the reason must be skipped or it's
      // mistaken for the command.
      "--force",
      // Read via flagValue() but previously absent from the command-detection skip set, so a
      // global-position `--since 2024 history list` mis-detected `2024` as the command.
      "--agent",
      "--antigravity-config",
      "--baseline-dir",
      "--codex-config",
      "--commit",
      "--cursor",
      "--history-dir",
      "--limit",
      "--mode",
      "--project",
      "--ref",
      "--since",
      "--source",
      "--title",
      "--until",
      // render-matrix axes — parsed bespoke (not via flagValue), value-consuming; listed so
      // command detection stays correct if ever used in global position.
      "--device",
      "--locale",
      "--ui-mode",
      "--font-scale",
    )

  /**
   * Flags read through [flagValue] whose value is attached (`=`) or optional, so they never consume
   * the following token. Listed only so `CliFlagsRegistryTest` can tell them apart from a missing
   * [VALUE_FLAGS] entry — they are intentionally excluded from command-detection skipping.
   */
  val ATTACHED_OR_OPTIONAL_FLAGS: Set<String> = setOf("--images", "--exit-when-idle")

  /**
   * The first positional token in [args] — the bare token that isn't the value of a value-consuming
   * flag — or `null` if there is none. Used by commands with a nested positional subcommand
   * (`bundle pack`, `history list`) so a leading `--module :app` isn't mistaken for the subcommand.
   */
  fun firstPositional(args: List<String>): String? =
    firstPositionalIndex(args).let { if (it >= 0) args[it] else null }

  /** Index of [firstPositional] in [args], or `-1`. */
  fun firstPositionalIndex(args: List<String>): Int = findCommandIndex(args.toTypedArray())

  /**
   * Index of the subcommand token in [args], or `-1` if argv is entirely flags and their values.
   * The first bare (non-`-`) token that isn't the value of a [VALUE_FLAGS] flag.
   */
  fun findCommandIndex(args: Array<String>): Int {
    var i = 0
    while (i < args.size) {
      val arg = args[i]
      if (arg in VALUE_FLAGS) {
        i += 2 // skip the flag and its value
        continue
      }
      if (arg.startsWith("-")) {
        i++
        continue
      }
      return i
    }
    return -1
  }
}
