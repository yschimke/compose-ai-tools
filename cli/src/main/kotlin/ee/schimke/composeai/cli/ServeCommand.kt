package ee.schimke.composeai.cli

import kotlin.system.exitProcess

/**
 * `compose-preview serve` — a launcher for the published preview server.
 *
 * This command used to *be* the server: it implemented the server's `ServeBuildHost` interface and
 * ran `ServeRunner` in this process, which is why `:cli` linked `compose-preview-serve` and why the
 * dependency cycle in yschimke/compose-preview-server#180 had a forward edge at all. An offline CLI
 * carried `ktor-server-*`, `jmdns` and `kotlin-reflect` so that four commands which never open a
 * socket could reach types filed in the same package as a web server.
 *
 * Now it execs the server binary, and the Gradle work the server needs travels the other way: the
 * server spawns `compose-preview build-host --stdio` (see [BuildHostCommand]) when it wants a local
 * build. Neither side links the other.
 *
 * **The binary is found, and failing that fetched.** [ServerBinaryDiscovery] answers which one to
 * run — an explicit flag, the environment, `PATH`, then a copy this CLI has already downloaded —
 * and [ServerDistributionProvision] fetches the pinned release when the machine has none, because
 * nothing else installs it (#5183). Only a failed fetch is fatal.
 *
 * **Arguments pass through untouched.** This deliberately parses nothing beyond finding the binary:
 * the server owns its own flags, and a launcher that validated them would be a second copy of that
 * surface, drifting from the first. `--help` reaches the server too, which is where the answer
 * actually lives.
 */
class ServeCommand(private val args: List<String>, private val browseProject: Boolean = false) {

  fun run() {
    val choice = ServerBinaryDiscovery.choose(args) ?: provision()
    if (choice == null) {
      System.err.println(ServerBinaryDiscovery.installationHint())
      exitProcess(1)
    }
    val command = launchCommand(choice.binary)
    val exit =
      try {
        ProcessBuilder(command).inheritIO().start().waitFor()
      } catch (t: Throwable) {
        System.err.println(
          "could not start ${choice.binary} (from ${choice.source}): " +
            "${t.message ?: t.javaClass.name}"
        )
        System.err.println()
        System.err.println(ServerBinaryDiscovery.installationHint())
        exitProcess(1)
      }
    exitProcess(exit)
  }

  /**
   * Fetch the pinned server when the machine has none, and name the copy that results.
   *
   * Nothing installs this binary — that is #5183, and it made `serve` fail for everyone who
   * installed the documented way — so a miss means "not fetched yet", not "not wanted". The fetch
   * happens once, prints what it is doing (a 120 MB transfer that appeared as silence would read as
   * a hang), and returns null having explained any failure, which the caller turns into
   * [ServerBinaryDiscovery.installationHint].
   */
  private fun provision(): ServerBinaryDiscovery.Choice? =
    ServerDistributionProvision.ensure()?.let {
      ServerBinaryDiscovery.Choice(it.path, ServerBinaryDiscovery.CACHE)
    }

  /**
   * The argv handed to the server.
   *
   * `--server-binary` is this launcher's own flag and is dropped rather than forwarded — the server
   * has no such option, and passing it through would make every invocation fail on an unknown
   * argument. Everything else, including the `serve` subcommand the server accepts as an alias, is
   * the caller's.
   *
   * Nothing is added for the build host. The server discovers `compose-preview` itself, by the same
   * flag/environment/PATH ordering this class uses, and a launcher that guessed a path here would
   * override an operator who had already chosen one.
   */
  internal fun launchCommand(binary: String): List<String> = buildList {
    add(binary)
    add("serve")
    addAll(forwardedArgs())
  }

  private fun forwardedArgs(): List<String> {
    val forwarded = mutableListOf<String>()
    var index = 0
    while (index < args.size) {
      if (args[index] == ServerBinaryDiscovery.FLAG) {
        // Skip the flag and its value.
        index += 2
        continue
      }
      forwarded += args[index]
      index++
    }
    return if (browseProject) BrowseCommand.serveArgs(forwarded) else forwarded
  }
}
