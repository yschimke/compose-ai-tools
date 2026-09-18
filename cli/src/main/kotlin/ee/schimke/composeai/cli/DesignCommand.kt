package ee.schimke.composeai.cli

/**
 * `compose-preview design` — the launcher for the preview server's `design` command.
 *
 * Fourth of the launchers, beside [ServeCommand], [BrowseCommand] and [UiBuilderCommand]: the
 * server binary renders a UI-builder design, exports its generated source, or reads its document,
 * and writes the result to a file
 * ([yschimke/compose-preview-server#529](https://github.com/yschimke/compose-preview-server/issues/529)).
 *
 * It adds nothing of its own — no defaults, no flag rewriting — for the reason `ui-builder` adds
 * nothing: the work needs the UI-builder renderer, the export executor and the asset store, all of
 * which the layer rule places in that repository, and this side has no useful opinion about any of
 * it. `--help` therefore reaches the server too, which is where the answer lives.
 *
 * **It is the one launcher whose target does not serve anything.** `design` runs against a server
 * that is already up — `--server`, defaulting to a local one — and exits with a code that says
 * whether the export was refused. [ServeCommand] already inherits IO and propagates the child's
 * exit code, so a one-shot command needs nothing more from this side than the command word.
 */
class DesignCommand(private val args: List<String>) {
  fun run() {
    ServeCommand(args, serverCommand = SERVER_COMMAND, childEnvironment = storedGrantEnv()).run()
  }

  /**
   * The grant this CLI already holds for the design client's target server, as environment for the
   * server process that runs the verbs.
   *
   * The verbs are the server's, and the server resolves credentials from `$COMPOSE_PREVIEW_TOKEN` —
   * but the *store* is this CLI's: `compose-preview auth request` wrote it, and its success line
   * says other commands against that server will use it automatically. A child process cannot read
   * this CLI's credential home, so without a bridge the verbs never saw the grant and went straight
   * to an interactive device-flow ask — even with a live, scope-correct token on disk.
   *
   * The bridge is deliberately narrow:
   *
   * - Only an explicit `--server` names the origin. Without one, the server-side default decides
   *   which port answers, and injecting a token for a guessed origin could hand the wrong server's
   *   credential to the right-looking URL. No `--server`, no injection.
   * - A token the caller exported themselves (`$COMPOSE_PREVIEW_TOKEN`, or the older
   *   `$COMPOSE_PREVIEW_UI_BUILDER_TOKEN`) always wins; nothing here overrides it.
   * - No entry for that origin, or no credential home at all, means no injection and behaviour
   *   exactly as before — the server's own flow asks, or fails with `--no-authorize`.
   */
  internal fun storedGrantEnv(
    env: (String) -> String? = System::getenv,
    storeFactory: () -> AgentAccessStore = { AgentAccessStore() },
  ): Map<String, String> {
    if (!env(DESIGN_TOKEN_ENV).isNullOrBlank() || !env(DESIGN_LEGACY_TOKEN_ENV).isNullOrBlank()) {
      return emptyMap()
    }
    val server = args.serverValue() ?: return emptyMap()
    val token =
      try {
        storeFactory().tokenFor(server)
      } catch (_: NoCredentialHomeException) {
        // A machine with nowhere to keep credentials has no grant to bridge; the server's own
        // flow (env token, device-code ask, --no-authorize) applies unchanged.
        null
      } ?: return emptyMap()
    return mapOf(DESIGN_TOKEN_ENV to token)
  }

  internal companion object {
    const val SERVER_COMMAND: String = "design"

    // The variable names the server's verb runner reads. They live there; spelling them here a
    // second time is the bridge's whole job, and a drift would surface as a grant that stops
    // being picked up — the kind of thing a rename on either side must catch together.
    const val DESIGN_TOKEN_ENV: String = "COMPOSE_PREVIEW_TOKEN"
    const val DESIGN_LEGACY_TOKEN_ENV: String = "COMPOSE_PREVIEW_UI_BUILDER_TOKEN"
  }
}

/** `--server <url>` or `--server=<url>` from launcher argv, or null. */
private fun List<String>.serverValue(): String? {
  var index = 0
  while (index < size) {
    val arg = this[index]
    if (arg == "--server") return getOrNull(index + 1)?.takeIf { !it.startsWith("--") }
    if (arg.startsWith("--server=")) return arg.substringAfter('=').takeIf { it.isNotEmpty() }
    index++
  }
  return null
}
