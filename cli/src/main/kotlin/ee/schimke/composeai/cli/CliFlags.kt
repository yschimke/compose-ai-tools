package ee.schimke.composeai.cli

/**
 * Single source of truth for which flags consume a following value, used to find the subcommand in
 * [main] (the first bare token that isn't a flag's value).
 *
 * Flags taking the next token go in [VALUE_FLAGS], or `compose-preview --module show list` would
 * pick `show`. Flags with an optional value (`--images`) must not. Required values that may be
 * attached (`--force=<reason>`) still belong in [VALUE_FLAGS] for the space form.
 *
 * `CliFlagsRegistryTest` scans the sources and fails when a flag read via [flagValue] /
 * [flagValuesAll] is in neither set. Bespoke parsers (e.g. `render-matrix` axes) aren't covered,
 * but their value flags are listed here too.
 */
internal object CliFlags {
  /**
   * Flags whose value is the next argv token; command detection skips both the flag and its value.
   */
  val VALUE_FLAGS: Set<String> =
    setOf(
      "--module",
      "--filter",
      // `guidelines`
      "--model",
      "--max-cost",
      "--rounds",
      "--request-timeout",
      "--idle-timeout",
      "--concurrency",
      "--provider-sort",
      "--preferred-max-latency",
      "--preferred-min-throughput",
      "--checker",
      "--compare-with",
      "--guidelines",
      "--surface",
      "--profile",
      "--previews-json",
      "--renders-dir",
      "--a11y-json",
      "--source-root",
      "--id",
      "--id-file",
      "--exclude-preview-id",
      "--exclude-preview-id-file",
      "--exclude-preview-row",
      "--output",
      // Classified by hand (the registry scanner only matches `--` flags); otherwise `bundle merge
      // a.png b.png -o out.png` reads the output path as an operand.
      "-o",
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
      "--serve-url",
      "--serve-token",
      "--github-token-file",
      "--github-token",
      "--with-extension",
      "--with",
      "--permutations",
      "--missing-renders",
      "--variant",
      // `show --link`'s plugin coordinates (the link flag itself is attached/optional, below).
      "--openai-plugin-id",
      "--openai-marketplace",
      // Preview reference selector, used by every previewing command (`previewMatchesReference`).
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
      "--bundle",
      "--accept-bundles-from",
      "--accept-docs-from",
      "--doc-ttl",
      "--image-upload-repo",
      "--image-ttl",
      "--image-rate-limit",
      "--playground-bundle",
      "--playground-android-bundle",
      "--playground-sandbox",
      "--playground-sandbox-memory-mb",
      "--playground-sandbox-cpus",
      "--playground-sandbox-pids",
      "--playground-sandbox-ttl",
      "--playground-sandbox-ro",
      "--playground-compile-slots",
      "--playground-catalog-limit",
      "--playground-rate-limit",
      "--playground-caller-concurrency",
      "--playground-edit-lease-ttl",
      "--extra-maven-repos",
      "--trust-store",
      "--catalogs",
      "--catalogs-unlisted",
      "--catalogs-file",
      "--sites",
      "--admin-token",
      "--engagement-file",
      "--github-auth-client-id",
      "--github-auth-client-secret",
      "--github-auth-cookie-secret",
      "--github-auth-repo",
      "--github-auth-callback-base-url",
      "--github-auth-cookie-domain",
      "--github-auth-scope",
      "--github-auth-users",
      "--agent-grant-scopes",
      "--agent-grant-capabilities",
      "--capability",
      "--agent-grant-max-ttl",
      "--agent-grant-max-active",
      "--agent-grant-rate-limit",
      "--server",
      "--scope",
      "--ttl",
      "--label",
      "--catalog-repo",
      "--catalog-branch-prefix",
      "--catalog-refresh-interval",
      "--catalog-max-images",
      "--catalog-feed-idle-timeout",
      "--catalog-feed-cache",
      "--background-renders",
      "--catalog-cache-dir",
      "--catalog-cache-max-bytes",
      "--theme-cache-dir",
      "--theme-cache-max-bytes",
      "--theme-optimizer-coordination-dir",
      "--catalog-source-root",
      "--wasm-dir",
      "--rc-player-wasm-dir",
      "--revisions-allow",
      "--history-branch",
      "--live-seats",
      // bundle sign / verify / keygen (producer trust)
      "--key",
      "--key-id",
      "--producer",
      "--provenance-identity",
      "--provenance-type",
      "--trust",
      "--origin",
      // Usually attached (`--force=<reason>`), but the space form is supported (ForceFlagTest).
      "--force",
      // Read via flagValue(); must be skipped in command detection.
      "--agent",
      "--antigravity-config",
      "--baseline-dir",
      "--baselines",
      "--codex-config",
      "--opencode-config",
      "--commit",
      "--cursor",
      "--history-dir",
      "--limit",
      "--mode",
      "--project",
      "--ref",
      "--repo",
      "--replicas-per-daemon",
      "--ui-builder-url",
      "--ui-builder-actor",
      "--since",
      "--source",
      "--title",
      "--until",
      // render-matrix axes, parsed bespoke; listed so global-position use detects the command
      // correctly.
      "--device",
      "--locale",
      "--ui-mode",
      "--font-scale",
      // bundle externalize
      "--res-out",
      "--ext",
      // bundle split — content-addressed pool dir for the opt-in shared app classpath
      "--shared-classpath-out",
      // bundle split — where to write the measured shared-carriage report a publisher gates on
      "--carriage-report",
      // bundle render — repeatable theme override (theme.colors=scheme:…) applied via the daemon
      "--knob",
      // bundle render — content-addressed pool dir for a published bundle's externalized resources
      "--res",
      // bundle repack — dir of re-rendered preview PNGs swapped into the bundle's baked previews
      "--renders",
    )

  /**
   * Flags read via [flagValue] with an attached or optional value, which never consume the next
   * token; listed so `CliFlagsRegistryTest` can tell them from a missing [VALUE_FLAGS] entry.
   */
  val ATTACHED_OR_OPTIONAL_FLAGS: Set<String> =
    setOf("--images", "--exit-when-idle", "--contact-sheet", "--cells-dir", "--link")

  /**
   * The first positional token (not a value of a [VALUE_FLAGS] flag), or null; used by commands
   * with nested subcommands (`bundle pack`, `history list`).
   */
  fun firstPositional(args: List<String>): String? =
    firstPositionalIndex(args).let { if (it >= 0) args[it] else null }

  /** Index of [firstPositional] in [args], or `-1`. */
  fun firstPositionalIndex(args: List<String>): Int = findCommandIndex(args.toTypedArray())

  /**
   * Every positional in [args], in order, skipping [VALUE_FLAGS] values — the operands of a
   * variadic subcommand like `bundle merge <base> <shard>…`.
   */
  fun positionals(args: List<String>): List<String> = buildList {
    var i = 0
    while (i < args.size) {
      val arg = args[i]
      when {
        arg in VALUE_FLAGS -> i += 2
        arg.startsWith("-") -> i++
        else -> {
          add(arg)
          i++
        }
      }
    }
  }

  /** Index of the subcommand token in [args], or `-1` if argv is only flags and their values. */
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
