package ee.schimke.composeai.cli

/**
 * `compose-preview design`: launcher for the preview server's `design` command, which renders a
 * UI-builder design, exports its source, or reads its document into a file. Adds nothing of its own
 * (the work lives server-side); `--help` reaches the server. Unlike the other launchers it serves
 * nothing: it talks to an already-running server (`--server`, default local) and propagates the
 * exit code via [ServeCommand].
 */
class DesignCommand(private val args: List<String>) {
  fun run() {
    ServeCommand(args, serverCommand = SERVER_COMMAND, childEnvironment = storedGrantEnv()).run()
  }

  /**
   * The grant this CLI holds for the design target server, as environment for the server process
   * (which reads `$COMPOSE_PREVIEW_TOKEN` but can't read this CLI's credential store). Narrow:
   * - only an explicit `--server` names the origin (no injection for a guessed default);
   * - a caller-exported `$COMPOSE_PREVIEW_TOKEN` / `$COMPOSE_PREVIEW_UI_BUILDER_TOKEN` always wins;
   * - no stored entry or credential home means no injection.
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
        // No credential home: nothing to bridge.
        null
      } ?: return emptyMap()
    return mapOf(DESIGN_TOKEN_ENV to token)
  }

  internal companion object {
    const val SERVER_COMMAND: String = "design"

    // The variable names the server's verb runner reads; a rename must change both sides together.
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
