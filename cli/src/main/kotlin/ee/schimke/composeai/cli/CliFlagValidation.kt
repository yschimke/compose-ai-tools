package ee.schimke.composeai.cli

/**
 * Per-command option allowlists, so an unrecognised option doesn't vanish silently. Commands read
 * options directly from argv, which makes a typo indistinguishable from an unread option; the
 * routed argv is validated and a non-fatal warning printed before expensive work starts.
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

  // `--id-file` selects for report commands (the `apply` action's a11y pipeline); `bundle pack` has
  // its own entry.
  private val reportFlags = commandBase + setOf("--json", "--fail-on", "--id-file")

  /**
   * Every flag the server's `serve` documents, shared by the launchers (the server's `ui` accepts
   * them all too), so new serve flags are added in one place.
   */
  private val serveFlags =
    setOf(
      "--accept-bundles",
      "--accept-bundles-from",
      "--accept-docs",
      "--accept-docs-from",
      "--accept-images",
      "--admin-token",
      "--agent-grant-capabilities",
      "--agent-grant-max-active",
      "--agent-grant-max-ttl",
      "--agent-grant-rate-limit",
      "--agent-grant-scopes",
      "--agent-grants",
      "--allow-render-trusted",
      "--background-renders",
      "--bundle",
      "--bundles",
      "--catalog-branch-prefix",
      "--catalog-cache-dir",
      "--catalog-cache-max-bytes",
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
      "--github-auth-client-id",
      "--github-auth-client-secret",
      "--github-auth-cookie-domain",
      "--github-auth-cookie-secret",
      "--github-auth-repo",
      "--github-auth-scope",
      "--github-auth-users",
      "--help",
      "--history-branch",
      "--host",
      "--image-rate-limit",
      "--image-ttl",
      "--image-upload-repo",
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
      "--playground-edit-lease-ttl",
      "--playground-editing",
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
      "--theme-cache-dir",
      "--theme-cache-evict",
      "--theme-cache-max-bytes",
      "--theme-optimizer-coordination-dir",
      "--token",
      "--trust-forwarded-for",
      "--trust-store",
      "--wasm-dir",
      "-h",
      "--admin-read-token",
      "--catalog-mcp",
      "--onboard-cache",
      "--spare-sandboxes",
      "--ui-builder-comment-webhook",
      "--ui-builder-comment-webhook-format",
      "--ui-builder-packs",
      "--wasm-ui-dir",
    )

  val BY_COMMAND: Map<String, Set<String>> =
    mapOf(
      "show" to
        commandBase +
          setOf("--json", "--images", "--link", "--openai-plugin-id", "--openai-marketplace"),
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
      "guidelines" to
        reportFlags +
          setOf(
            "--model",
            "--max-cost",
            "--rounds",
            "--request-timeout",
            "--idle-timeout",
            "--concurrency",
            "--no-stream",
            "--provider-sort",
            "--preferred-max-latency",
            "--preferred-min-throughput",
            "--checker",
            "--compare-with",
            "--no-triage",
            "--annotate",
            "--guidelines",
            "--surface",
            "--profile",
            "--previews-json",
            "--renders-dir",
            "--a11y-json",
            "--source-root",
          ),
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
        setOf(
          "--baselines",
          "--branch",
          "--help",
          "-h",
          "--layout",
          "--output",
          "--quiet",
          "--repo",
        ),
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
          // Doctor's discovery pass uses the shared timeout budget.
          "--timeout",
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
      "serve" to commandBase + serveFlags,
      // Launcher for the server's `ui`: its selectors, network knobs and builder options.
      // `--no-open` is the lane's own (suppresses `--open-browser`); `--no-project` is the server's
      // packaged-catalogs mode.
      "ui-builder" to
        commandBase +
          serveFlags +
          setOf(
            "--build-host",
            "--discover",
            "--help",
            "-h",
            "--host",
            "--lan",
            "--no-open",
            "--no-project",
            "--open-path",
            "--port",
            "--public",
            "--server-binary",
            "--token",
            "--ui-builder-catalogs",
            "--ui-builder-components",
            "--ui-builder-dir",
            "--ui-builder-migrate-state",
            "--ui-builder-runtime-dir",
            "--ui-builder-state-dir",
          ),
      // Launcher for the server's `design`: no project selectors (it names a design on a server).
      // `--token` is listed so the server's explicit refusal of command-line credentials is what
      // users see.
      "design" to
        setOf(
          "--assets",
          "--catalog",
          "--components",
          "--document",
          "--format",
          "--local",
          "--help",
          "-h",
          "--limit",
          "--no-authorize",
          "--out",
          "-o",
          "--revision",
          "--server",
          "--server-binary",
          "--timeout",
          "--token",
        ),
      // Launcher for the server's `a2ui` (document in, PNG out); `--token` listed for the same
      // reason as `design`.
      "a2ui" to
        setOf(
          "--catalog",
          "--document",
          "--help",
          "-h",
          "--no-authorize",
          "--out",
          "-o",
          "--preview",
          "--server",
          "--server-binary",
          "--timeout",
          "--token",
        ),
      // Flags the preview server passes when spawning the Gradle half of `serve`; deliberately
      // narrow.
      "build-host" to setOf("--stdio", "--module", "--variant", "--verbose", "-v"),
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
          "--secret",
          "--serve-url",
          "--serve-token",
          "--github-token-file",
          // Known so the command's own refusal (why tokens can't be arguments) is the message
          // shown.
          "--github-token",
        ),
      // Union of what `compile` / `dump` / `header` read, and only that: `rc` reads no project, so
      // `commandBase` flags would be silently discarded (as with `devices`).
      "rc" to setOf("--help", "-h", "--output", "-o", "--compact", "--json"),
      // Union of the nested subcommands' options; positional dispatch is BundleCommand's concern.
      "bundle" to
        commandBase +
          setOf(
            "--embed-deps",
            "--id-file",
            "--exclude-preview-id",
            "--exclude-preview-id-file",
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
            "--carriage-report",
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
            "--allow-lost-font-families",
          ),
      "mcp" to
        setOf(
          "--antigravity",
          "--antigravity-config",
          "--claude",
          "--codex",
          "--codex-config",
          "--opencode",
          "--opencode-config",
          "--help",
          "-h",
          "--json",
          "--module",
          "--no-antigravity",
          "--no-claude",
          "--no-codex",
          "--no-opencode",
          "--no-plugin-hint",
          "--project",
          "--replicas-per-daemon",
          "--scope",
          "--ui-builder-url",
          "--ui-builder-actor",
          "--verbose",
          "-v",
        ),
      "update" to setOf("--dry-run", "--no-modify-path"),
      "init-script" to setOf("--path", "--print"),
      "pin" to setOf("--cli", "--json", "--remove", "--unset"),
      "auth" to
        setOf(
          "--server",
          "--scope",
          "--capability",
          "--ttl",
          "--label",
          "--no-wait",
          "--json",
          "--help",
          "-h",
        ),
      "version" to emptySet(),
      "help" to setOf("--all"),
    )

  /** Every distinct option registered for at least one command, for the source drift guard. */
  val ALL: Set<String> = BY_COMMAND.values.flatten().toSet()

  /**
   * Commands whose argv is forwarded to the server binary, which owns the flag surface; for these
   * an unknown option is passed through, not ignored, and the note says so.
   */
  internal val FORWARDED_TO_SERVER: Set<String> =
    setOf("serve", "browse", "ui-builder", "design", "a2ui")

  /** The stderr line [Main] prints for one unknown option — accurate about where it lands. */
  fun unknownFlagNote(command: String, flag: String): String =
    if (command in FORWARDED_TO_SERVER) {
      "compose-preview: note: option '$flag' is not one the '$command' launcher reads; it is " +
        "forwarded to the compose-preview-server binary, which validates its own flags"
    } else {
      "compose-preview: warning: unrecognised option '$flag' for '$command' (ignored)"
    }

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
      // Only a recognised value flag consumes the next token, so an unknown flag can't hide the
      // option after it.
      index += if (flag in allowed && flag in CliFlags.VALUE_FLAGS && '=' !in raw) 2 else 1
    }
    return unknown.toList()
  }
}
