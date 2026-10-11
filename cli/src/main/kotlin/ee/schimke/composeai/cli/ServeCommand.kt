package ee.schimke.composeai.cli

import kotlin.system.exitProcess

/**
 * `compose-preview serve` — a launcher for the published preview server. It execs the server
 * binary; when the server needs a local build it spawns `compose-preview build-host --stdio`
 * ([BuildHostCommand]). Neither side links the other.
 *
 * [ServerBinaryDiscovery] picks the binary (flag, environment, `PATH`, then a cached download) and
 * [ServerDistributionProvision] fetches the release when there is none; only a failed fetch is
 * fatal. Arguments, including `--help`, pass through untouched: the server owns its flags.
 */
class ServeCommand(
  private val args: List<String>,
  private val browseProject: Boolean = false,
  /**
   * Which server command to launch: `serve` for `serve`/`browse`, `ui` for `ui-builder` (whose
   * differences all live server-side).
   */
  private val serverCommand: String = "serve",
  /**
   * Extra environment for the server process on top of the inherited one; empty by default.
   * [DesignCommand] and [A2uiCommand] use it to pass a grant from this CLI's store to the server.
   */
  private val childEnvironment: Map<String, String> = emptyMap(),
  /**
   * The oldest server release that has [serverCommand], or null. A cached copy older than this is
   * replaced by the newest before the exec ([ServerBinaryDiscovery.meetsMinimum]), so stale caches
   * don't answer "unknown command".
   */
  private val minimumServerVersion: String? = null,
) {

  fun run() {
    val choice =
      ServerBinaryDiscovery.choose(args)?.let {
        ServerBinaryDiscovery.meetsMinimum(
          it,
          minimumServerVersion,
          serverCommand,
          requested = ServerDistributionProvision.requestedVersion(),
          provision = ::provision,
        )
      } ?: provision()
    if (choice == null) {
      System.err.println(ServerBinaryDiscovery.installationHint())
      exitProcess(1)
    }
    // Before the exec: the start script resolves its own `java`, and a too-old JVM fails there with
    // an opaque `UnsupportedClassVersionError`. `null` means launch (the check fails open).
    ServerJavaPreflight.failure(choice, ReleasedDistribution.SERVER)?.let {
      System.err.println(it)
      exitProcess(1)
    }
    val command = launchCommand(choice.binary)
    val exit =
      try {
        ProcessBuilder(command)
          .apply { environment().putAll(childEnvironment) }
          .inheritIO()
          .runTiedToLauncher()
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
   * Fetch the server when the machine has none (nothing else installs it), announcing the large
   * download so it doesn't read as a hang. Returns null after explaining any failure; the caller
   * prints [ServerBinaryDiscovery.installationHint].
   */
  private fun provision(): ServerBinaryDiscovery.Choice? =
    ServerDistributionProvision.ensure()?.let {
      ServerBinaryDiscovery.Choice(it.path, ServerBinaryDiscovery.CACHE)
    }

  /**
   * The argv for the server: everything except this launcher's own `--server-binary`. Nothing is
   * added for the build host; the server discovers `compose-preview` itself.
   */
  internal fun launchCommand(binary: String): List<String> = buildList {
    add(binary)
    add(serverCommand)
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
