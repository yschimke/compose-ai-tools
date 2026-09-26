package ee.schimke.composeai.cli

/**
 * `compose-preview a2ui` — the launcher for the preview server's `a2ui` command.
 *
 * `a2ui render` hands an A2UI document (JSON Lines, a JSON array of messages, or a `{"components"}`
 * shorthand) to a running server's playground preview and writes the PNG the real Material A2UI
 * catalog draws for it (yschimke/compose-preview-server#1095). Like [DesignCommand] it is a client
 * of a server that is already up, so it adds nothing of its own: the flags, the defaults and
 * `--help` are the server's.
 *
 * It bridges the same stored grant [DesignCommand] does, for the same reason: `compose-preview auth
 * request` wrote it to this CLI's credential home, which the server process cannot read.
 */
class A2uiCommand(private val args: List<String>) {
  fun run() {
    ServeCommand(
        args,
        serverCommand = SERVER_COMMAND,
        childEnvironment = DesignCommand(args).storedGrantEnv(),
      )
      .run()
  }

  internal companion object {
    const val SERVER_COMMAND: String = "a2ui"
  }
}
