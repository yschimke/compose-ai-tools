package ee.schimke.composeai.cli

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        exitProcess(0)
    }

    val command = args[0]
    val remainingArgs = args.drop(1)

    when (command) {
        "show" -> ShowCommand(remainingArgs).run()
        "list" -> ListCommand(remainingArgs).run()
        "render" -> RenderCommand(remainingArgs).run()
        "help", "--help", "-h" -> printUsage()
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

        Usage: compose-preview <command> [options]

        Commands:
          show    Discover and render previews, display inline
          list    List discovered preview names
          render  Render a specific preview to a file
          help    Show this help message

        Options:
          --module <name>      Target module (default: auto-detect)
          --variant <variant>  Build variant (default: debug)
          --filter <pattern>   Filter previews by name pattern
          --json               Output as JSON (list command)
          --output <path>      Output file path (render command)
        """.trimIndent()
    )
}
