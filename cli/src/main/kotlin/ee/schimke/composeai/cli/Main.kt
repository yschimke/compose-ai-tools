package ee.schimke.composeai.cli

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        exitProcess(0)
    }

    // Find the command — first non-flag argument that isn't a flag's value.
    // Flags that take values: --module, --filter, --id, --output, --timeout, --plugin-version
    val valuedFlags = setOf(
        "--module", "--filter", "--id", "--output", "--timeout", "--plugin-version",
        "--fail-on",
    )
    val commands = setOf("show", "list", "render", "a11y", "doctor", "help")

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
        "a11y" -> A11yCommand(allArgs).run()
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
          a11y    Render previews and print ATF accessibility findings
          doctor  Verify Java 17 + Compose/AGP environment before editing Gradle files
          help    Show this help message

        Options:
          --module <name>      Target module (default: auto-detect all)
          --filter <pattern>   Case-insensitive substring match on preview id
          --id <exact>         Exact match on preview id
          --json               Emit JSON (show, list, a11y)
          --brief              JSON only: drop functionName/className/sourceFile/params
          --changed-only       JSON only (show, a11y): drop previews with no changed capture
          --output <path>      Copy matched preview PNG to this path (render)
          --progress           Print per-task milestone/heartbeat lines to stderr
          --verbose, -v        Show full Gradle build output (implies --progress)
          --timeout <seconds>  Gradle build timeout (default: 300)
          --fail-on <level>    a11y: exit non-zero on 'errors' or 'warnings' (default: mirror Gradle)

        OSC 9;4 terminal progress (native taskbar/tab progress bar) is on by
        default in a TTY and auto-disables when stdout is piped or redirected.

        JSON output is wrapped in {schema, previews, counts?} (schema:
        compose-preview-show/v1). Each preview includes a `captures[]` array
        with per-capture pngPath/sha256/changed/advanceTimeMillis/scroll.
        For back-compat the top-level pngPath/sha256/changed mirror the first
        capture. State persisted per module under
        <module>/build/compose-previews/.cli-state.json (wiped on `clean`).

        Exit codes: 0 success, 1 build/CLI error, 2 render failure or a11y
        threshold tripped, 3 no previews found / matched.
        """.trimIndent()
    )
}
