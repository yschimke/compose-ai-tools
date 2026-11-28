package ee.schimke.composeai.cli

/**
 * `compose-preview launch`: the original name of `android-simulator`, kept as a trampoline so every
 * script, MCP host and editor integration that runs `launch` keeps working.
 */
internal class LaunchCommand(private val args: List<String>) {
  fun run() {
    if ("--help" in args || "-h" in args) {
      println(USAGE)
      return
    }
    SimulatorLauncherCommand(args, invokedAs = "launch").run()
  }

  companion object {
    val USAGE: String =
      "`compose-preview launch` is an alias of `compose-preview android-simulator`: the same " +
        "flags, the same simulator.\n\n" +
        SimulatorLauncherCommand.usage("launch")
  }
}
