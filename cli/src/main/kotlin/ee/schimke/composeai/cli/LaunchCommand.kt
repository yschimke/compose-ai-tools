package ee.schimke.composeai.cli

import ee.schimke.composeai.cli.simulator.AndroidSimulatorCommand

/**
 * `compose-preview launch`: the original name of [AndroidSimulatorCommand], kept as a trampoline so
 * every script, MCP host and editor integration that runs `launch` keeps working. It takes exactly
 * the flags `android-simulator` does and runs the same code; only `--help` differs, by saying where
 * the command now lives.
 */
internal class LaunchCommand(private val args: List<String>) {
  fun run() {
    if ("--help" in args || "-h" in args) {
      println(USAGE)
      return
    }
    AndroidSimulatorCommand(
        args,
        invokedAs = "launch",
        environment = cliSimulatorHostEnvironment(),
      )
      .run()
  }

  companion object {
    val USAGE: String =
      "`compose-preview launch` is an alias of `compose-preview android-simulator`: the same " +
        "flags, the same simulator.\n\n" +
        AndroidSimulatorCommand.USAGE
  }
}
