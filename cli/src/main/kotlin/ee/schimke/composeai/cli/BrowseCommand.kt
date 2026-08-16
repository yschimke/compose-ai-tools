package ee.schimke.composeai.cli

/**
 * The small, local-project entry point for the Storybook-like component browser.
 *
 * [ServeCommand] remains the full hosting surface for bundles, public catalogs, authentication and
 * administration. Browse deliberately supplies the three project-mode choices a component author
 * should not have to learn: discover local previews, use the streamlined UI, and omit revision
 * history that UI does not expose.
 */
class BrowseCommand(private val args: List<String>) {
  fun run() {
    if ("--help" in args || "-h" in args) {
      printUsage()
      return
    }
    ServeCommand(serveArgs(args), browseProject = true).run()
  }

  internal companion object {
    fun serveArgs(args: List<String>): List<String> = buildList {
      addAll(args.filterNot { it == "--no-open" })
      if ("--discover" !in args) add("--discover")
      if ("--component-browser" !in args) add("--component-browser")
      if ("--no-history" !in args) add("--no-history")
      if ("--no-open" !in args) add("--open-browser")
    }

    private fun printUsage() {
      println(
        """
        compose-preview browse [options]

        Discover this project's @Preview functions and open the streamlined component browser.
        No build-file changes are required. Every module containing matching previews is included.
        Compatible Compose Multiplatform Wasm browser apps are built and connected automatically;
        previews without one keep their rendered snapshot and source-code experience.

        Options:
          --module <path>   Optionally narrow browsing to one Gradle module.
          --preview <ref>   Include previews selected by id, function name, or substring.
          --id <exact>      Include only one exact preview id.
          --filter <text>   Include preview ids containing this text.
          --variant <name>  Select the Android build variant used for previews.
          --host <addr>     Bind address (default 127.0.0.1).
          --port <n>        Preferred port (default 8791; the next free port is used).
          --lan             Make the token-gated browser reachable on the local network.
          --public          Remove the token gate from every route.
          --token <value>   Use a stable token so saved links remain valid.
          --no-open         Print the URL without opening a browser (for CI/headless shells).
          --progress        Print Gradle task progress while previews are prepared.
          --verbose, -v     Print full Gradle build output.
          --help, -h        Show this help.

        Advanced bundle, catalog, deployment and administration options remain under
        `compose-preview serve --help`.
        """
          .trimIndent()
      )
    }
  }
}
