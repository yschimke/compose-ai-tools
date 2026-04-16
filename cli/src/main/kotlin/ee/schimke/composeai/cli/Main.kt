package ee.schimke.composeai.cli

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        exitProcess(0)
    }

    // Find the command — first non-flag argument that isn't a flag's value.
    // Flags that take values: --module, --variant, --filter, --id, --output, --timeout, --plugin-version
    val valuedFlags = setOf(
        "--module", "--variant", "--filter", "--id", "--output", "--timeout", "--plugin-version",
    )
    val commands = setOf("show", "list", "render", "doctor", "help")

    var commandIndex = -1
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        if (arg in valuedFlags) {
            i += 2 // skip flag and its value
            continue
        }
        if (arg.startsWith("-")) {
            i++
            continue
        }
        commandIndex = i
        break
    }

    if (commandIndex < 0) {
        if ("--help" in args || "-h" in args) {
            printUsage()
            exitProcess(0)
        }
        System.err.println("No command specified.")
        printUsage()
        exitProcess(1)
    }

    val command = args[commandIndex]
    val allArgs = args.toMutableList().apply { removeAt(commandIndex) }

    when (command) {
        "show" -> ShowCommand(allArgs).run()
        "list" -> ListCommand(allArgs).run()
        "render" -> RenderCommand(allArgs).run()
        "doctor" -> DoctorCommand(allArgs).run()
        "help" -> printUsage()
        else -> {
            System.err.println("Unknown command: $command")
            printUsage()
            exitProcess(1)
        }
    }
}

private fun printUsage() {
    println(
        """
        compose-preview — Compose Preview CLI

        Usage: compose-preview [options] <command> [options]

        Commands:
          show    Discover and render previews; print id, path, sha256, changed flag
          list    List discovered previews
          render  Render previews; with --output copies a single match to disk
          doctor  Verify Java 21 + GitHub Packages credentials before editing Gradle files
          help    Show this help message

        Options:
          --module <name>      Target module (default: auto-detect all)
          --variant <variant>  Build variant (default: debug)
          --filter <pattern>   Case-insensitive substring match on preview id
          --id <exact>         Exact match on preview id
          --json               Emit JSON (show, list)
          --output <path>      Copy matched preview PNG to this path (render)
          --progress           Print per-task milestone/heartbeat lines to stderr
          --verbose, -v        Show full Gradle build output (implies --progress)
          --timeout <seconds>  Gradle build timeout (default: 300)

        OSC 9;4 terminal progress (native taskbar/tab progress bar) is on by
        default in a TTY and auto-disables when stdout is piped or redirected.

        JSON output includes sha256 of each rendered PNG and a `changed` flag
        computed against the previous invocation (state under
        <module>/build/compose-previews/.cli-state.json; wiped on `clean`).
        """.trimIndent()
    )
}
