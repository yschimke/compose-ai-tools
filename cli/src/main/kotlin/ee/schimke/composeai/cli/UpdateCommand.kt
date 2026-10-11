package ee.schimke.composeai.cli

import java.io.File
import kotlin.system.exitProcess

/**
 * `compose-preview update [VERSION] [--dry-run] [--no-modify-path]`: re-run `scripts/install.sh`
 * from `main` (idempotent; latest release or the given version), refreshing the skill bundle and
 * launcher.
 *
 * `--dry-run` prints the curl-pipe-bash command instead of running it. `--no-modify-path` is
 * forwarded so `install.sh` leaves shell startup files alone.
 */
class UpdateCommand(private val args: List<String>) {
  fun run() {
    val dryRun = "--dry-run" in args
    // Positional version arg, if present (anything not starting with `--`).
    val targetVersion = args.firstOrNull { !it.startsWith("--") }
    val pipeline = buildPipeline(targetVersion, noModifyPath = "--no-modify-path" in args)

    if (dryRun) {
      println(pipeline)
      return
    }

    val target = targetVersion ?: "latest"
    System.err.println("==> updating compose-preview (currently $BUNDLE_VERSION) to $target")
    System.err.println("==> running: $pipeline")

    val proc = ProcessBuilder("bash", "-c", pipeline).inheritIO().start()
    val exit = proc.waitFor()
    if (exit != 0) {
      System.err.println("error: install script exited with code $exit")
      exitProcess(exit)
    }
    pruneStaleMcp()?.let { System.err.println(it) }
    // Host configs written by older releases can name a versioned launcher the installer just
    // deleted, or pin a global entry to one project. Point them at the stable launcher.
    runCatching { McpCommand.repairInstalledHostConfigs() }
      .onSuccess { changes ->
        changes?.forEach { System.err.println("==> repaired MCP entry $it") }
      }
      .onFailure { System.err.println("warning: could not check MCP host configs: ${it.message}") }
  }

  companion object {
    /**
     * Drop cached MCP servers older than the newest release so the next `mcp serve` fetches it.
     * Returns the line to print, or null. A `COMPOSE_PREVIEW_SERVER_VERSION` pin, or an
     * unresolvable newest, leaves everything alone.
     */
    internal fun pruneStaleMcp(
      requested: String? = ServerDistributionProvision.requestedVersion(),
      offline: Boolean = ServerDistributionProvision.defaultOffline(),
      latest: () -> String? = { ServerDistributionProvision.latestVersion() },
      cacheRoot: File = ServerDistributionProvision.defaultCacheRoot(ReleasedDistribution.MCP),
    ): String? {
      if (requested != null || offline || !cacheRoot.isDirectory) return null
      val newest =
        latest() ?: return "==> could not resolve the newest MCP server; its cache is unchanged"
      val removed =
        ServerDistributionProvision.pruneOlderThan(newest, ReleasedDistribution.MCP, cacheRoot)
      if (removed.isEmpty()) return null
      return "==> removed cached MCP server ${removed.joinToString()}; " +
        "the next `mcp serve` uses $newest"
    }

    /**
     * Builds the curl-pipe-bash invocation that re-bootstraps the skill bundle. Pure function so
     * tests can exercise the version-arg quoting without spawning a subprocess.
     */
    internal fun buildPipeline(targetVersion: String?, noModifyPath: Boolean = false): String {
      val installArgs =
        listOfNotNull(
          targetVersion?.let(::shellQuote),
          "--no-modify-path".takeIf { noModifyPath },
        )
      val installUrl = "https://raw.githubusercontent.com/$SKILLS_REPO/main/scripts/install.sh"
      return buildString {
        // raw.githubusercontent.com applies an IP-wide throttle. Retry 429s rather than turning a
        // transient shared-network limit into a failed CLI update; curl retries 429 by default.
        append("curl --retry 8 --retry-max-time 300 -fsSL ")
        append(installUrl)
        append(" | bash")
        if (installArgs.isNotEmpty()) {
          append(" -s -- ")
          append(installArgs.joinToString(" "))
        }
      }
    }

    /**
     * Shell-quote the version arg (anything outside `[A-Za-z0-9._-]`), so it can never expand in
     * the parent pipeline.
     */
    internal fun shellQuote(s: String): String {
      if (s.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }) return s
      return "'" + s.replace("'", "'\\''") + "'"
    }
  }
}
