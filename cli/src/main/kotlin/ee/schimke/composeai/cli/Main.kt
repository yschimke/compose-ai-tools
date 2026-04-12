package ee.schimke.composeai.cli

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        exitProcess(0)
    }

    // Find the command — first non-flag argument that isn't a flag's value.
    // Flags that take values: --module, --variant, --filter, --output, --timeout
    val valuedFlags = setOf("--module", "--variant", "--filter", "--output", "--timeout")
    val commands = setOf("show", "list", "render", "help")

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
          show    Discover and render previews, display inline
          list    List discovered preview names
          render  Render a specific preview to a file
          help    Show this help message

        Options:
          --module <name>      Target module (default: auto-detect all)
          --variant <variant>  Build variant (default: debug)
          --filter <pattern>   Filter previews by name pattern
          --json               Output as JSON (list command)
          --output <path>      Output file path (render command)
          --verbose, -v        Show Gradle build output
          --timeout <seconds>  Gradle build timeout (default: 300)
        """.trimIndent()
    )
}
