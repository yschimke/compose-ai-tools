package ee.schimke.composeai.cli

/**
 * Per-command option allowlists used to keep an unrecognised option from disappearing silently
 * (issue #3781).
 *
 * Parsing in this CLI is intentionally lightweight: commands read the options they care about
 * directly from [List] rather than registering them with a parser. That makes a typo
 * indistinguishable from an option the command simply did not read. Keep that parsing model, but
 * validate the routed argv against the command that will receive it and warn before any expensive
 * work starts.
 *
 * The warning is deliberately non-fatal for backwards compatibility. Existing scripts which pass an
 * extra option keep their exit-code behaviour, while a human or CI log can no longer mistake the
 * option for one that took effect.
 */
internal object CliFlagValidation {
  private val commandBase =
    setOf(
      "--module",
      "--filter",
      "--id",
      "--preview",
      "--verbose",
      "-v",
      "--progress",
      "--timeout",
      "--changed-only",
      "--brief",
      "--force",
      "--variant",
      "--with-extension",
      "--with",
      "--permutations",
      "--missing-renders",
      "--no-auto-inject",
    )

  private val reportFlags = commandBase + setOf("--json", "--fail-on")

  val BY_COMMAND: Map<String, Set<String>> =
    mapOf(
      "show" to commandBase + setOf("--json", "--images"),
      "show-resources" to commandBase + setOf("--json"),
      "list" to commandBase + setOf("--json"),
      "render" to commandBase + setOf("--output", "--bundle", "--embed-deps", "--format"),
      "render-matrix" to
        commandBase +
          setOf(
            "--json",
            "--help",
            "-h",
            "--device",
            "--locale",
            "--ui-mode",
            "--font-scale",
            "--contact-sheet",
            "--cells-dir",
          ),
      "record" to
        commandBase +
          setOf(
            "--script",
            "--out",
            "--output",
            "--format",
            "--fps",
            "--scale",
            "--overrides",
            "--baseline-dir",
          ),
      "a11y" to reportFlags,
      "diff-semantics" to setOf("--json", "--fail-on-change", "--help", "-h"),
      "devices" to setOf("--json", "--help", "-h"),
      "extensions" to setOf("--json"),
      "history" to
        setOf(
          "--agent",
          "--branch",
          "--commit",
          "--cursor",
          "--data",
          "--history-dir",
          "--inline",
          "--json",
          "--limit",
          "--mode",
          "--out",
          "--preview",
          "--ref",
          "--since",
          "--source",
          "--until",
        ),
      "history-manifest" to
        setOf("--baselines", "--branch", "--help", "-h", "--output", "--quiet", "--repo"),
      // Extra flags are forwarded to ReportCommand after the profile file is expanded.
      "profile" to reportFlags,
      "doctor" to
        setOf(
          "--daemon",
          "--explain",
          "--json",
          "--plugin-version",
          "--project",
          "--report",
          "--variant",
          "--verbose",
          "-v",
          "--with-daemon",
        ),
      "browse" to
        commandBase +
          setOf(
            "--help",
            "-h",
            "--host",
            "--lan",
            "--no-history",
            "--no-open",
            "--port",
            "--public",
            "--token",
            "--wasm-dir",
          ),
      "serve" to
        commandBase +
          setOf(
            "--accept-bundles",
            "--accept-bundles-from",
            "--accept-docs",
            "--accept-docs-from",
            "--admin-token",
            "--allow-render-trusted",
            "--bundle",
            "--bundles",
            "--catalog-branch-prefix",
            "--catalog-feed-cache",
            "--catalog-feed-idle-timeout",
            "--catalog-max-images",
            "--catalog-refresh-interval",
            "--catalog-repo",
            "--catalog-source-root",
            "--catalogs",
            "--catalogs-file",
            "--catalogs-unlisted",
            "--component-browser",
            "--discover",
            "--doc-ttl",
            "--engagement-file",
            "--exit-when-idle",
            "--export",
            "--extra-maven-repos",
            "--github-auth-callback-base-url",
            "--github-auth-cookie-domain",
            "--github-auth-client-id",
            "--github-auth-client-secret",
            "--github-auth-cookie-secret",
            "--github-auth-repo",
            "--github-auth-scope",
            "--github-auth-users",
            "--help",
            "-h",
            "--history-branch",
            "--host",
            "--inline",
            "--lan",
            "--live-seats",
            "--no-history",
            "--open-browser",
            "--playground",
            "--playground-android-bundle",
            "--playground-bundle",
            "--playground-caller-concurrency",
            "--playground-catalog-limit",
            "--playground-compile-slots",
            "--playground-editing",
            "--playground-edit-lease-ttl",
            "--playground-rate-limit",
            "--playground-sandbox",
            "--playground-sandbox-cpus",
            "--playground-sandbox-memory-mb",
            "--playground-sandbox-pids",
            "--playground-sandbox-ro",
            "--playground-sandbox-ttl",
            "--port",
            "--public",
            "--rc-player-wasm-dir",
            "--revisions",
            "--revisions-allow",
            "--sites",
            "--token",
            "--trust-forwarded-for",
            "--trust-store",
            "--wasm-dir",
          ),
      "share-preview" to
        setOf(
          "--allow-non-preview-branch",
          "--branch",
          "--desc",
          "--json",
          "--mechanism",
          "--message",
          "--pr-number",
          "--public",
          "--raw-base",
          "--remote",
        ),
      // `bundle` owns nested subcommands. Validate at the routed-command boundary while allowing
      // the union of their options; nested positional dispatch remains BundleCommand's concern.
      "bundle" to
        commandBase +
          setOf(
            "--embed-deps",
            "--exclude-preview-id",
            "--exclude-preview-row",
            "--ext",
            "--external-images",
            "--help",
            "-h",
            "--in-bundle",
            "--include-data-extensions",
            "--json",
            "--key",
            "--key-id",
            "--knob",
            "--no-crop",
            "--no-render",
            "--origin",
            "--output",
            "-o",
            "--per-preview",
            "--producer",
            "--provenance-identity",
            "--provenance-type",
            "--renders",
            "--res",
            "--res-out",
            "--shared-classpath-out",
            "--svg",
            "--title",
            "--trust",
            "--view-only",
            "--with-semantics",
          ),
      "mcp" to
        setOf(
          "--antigravity",
          "--antigravity-config",
          "--claude",
          "--codex",
          "--codex-config",
          "--help",
          "-h",
          "--json",
          "--module",
          "--no-antigravity",
          "--no-claude",
          "--no-codex",
          "--project",
          "--replicas-per-daemon",
          "--verbose",
          "-v",
        ),
      "update" to setOf("--dry-run"),
      "init-script" to setOf("--path", "--print"),
      "pin" to setOf("--cli", "--json", "--remove", "--unset"),
      "version" to emptySet(),
      "help" to setOf("--all"),
    )

  /** Every distinct option registered for at least one command, for the source drift guard. */
  val ALL: Set<String> = BY_COMMAND.values.flatten().toSet()

  /** Unknown option spellings, de-duplicated in argv order. */
  fun unknownFlags(command: String, args: List<String>): List<String> {
    val allowed = BY_COMMAND.getValue(command)
    val unknown = linkedSetOf<String>()
    var index = 0
    while (index < args.size) {
      val raw = args[index]
      if (raw == "--") break
      if (!raw.startsWith("-") || raw == "-") {
        index++
        continue
      }
      val flag = raw.substringBefore('=')
      if (flag !in allowed) unknown += flag
      // Only a recognised, required-value flag owns the following token. An unknown flag must not
      // hide another option after it, while a legitimate value such as "--literal" must be safe.
      index += if (flag in allowed && flag in CliFlags.VALUE_FLAGS && '=' !in raw) 2 else 1
    }
    return unknown.toList()
  }
}
