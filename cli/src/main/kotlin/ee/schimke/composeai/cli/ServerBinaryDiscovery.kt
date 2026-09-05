package ee.schimke.composeai.cli

import java.io.File

/**
 * Finds the binary a launcher command execs — the preview server for `serve`, `browse` and
 * `ui-builder`, the MCP server for `mcp serve`.
 *
 * One implementation for both, parameterised by [ReleasedDistribution]: the two differ only in
 * their names, and a second copy of this ordering would be a second thing to keep in step. The
 * server's names stay available as [FLAG] / [ENV] / [BINARY] because `doctor` and the tests read
 * them.
 *
 * The mirror image of the server's own build-host discovery, and deliberately the same shape, so an
 * operator who has learned one has learned both: an explicit flag, then the environment, then
 * `PATH`. Most explicit first, because the failure this ordering prevents is running a binary the
 * user did not mean.
 *
 * A fourth source sits after those three: the copy [ServerDistributionProvision] has already
 * fetched into the CLI's cache. It is **last** for the same reason `PATH` is above it — an operator
 * who installed a server chose that one, and a cached download must never quietly win over a
 * deliberate choice.
 *
 * A miss is not yet a failure. Nothing installs this binary (#5183 — the documented one-liner
 * fetches the CLI and the skills, and knows nothing about the server), so the caller asks
 * [ServerDistributionProvision] to fetch the pinned release before giving up; only a *failed* fetch
 * reports [installationHint] and exits. `serve` has nothing to degrade to — the server body left
 * this repository.
 */
internal object ServerBinaryDiscovery {

  const val FLAG: String = "--server-binary"
  const val ENV: String = "COMPOSE_PREVIEW_SERVER"
  const val BINARY: String = "compose-preview-server"

  /** Source name a [Choice] carries when it came from the CLI's own provisioned cache. */
  const val CACHE: String = "cache"

  data class Choice(val binary: String, val source: String)

  fun choose(
    args: List<String>,
    distribution: ReleasedDistribution = ReleasedDistribution.SERVER,
    env: (String) -> String? = System::getenv,
    pathLookup: (String) -> File? = ::onPath,
    cacheLookup: () -> File? = { ServerDistributionProvision.cached(distribution, env) },
  ): Choice? {
    flagValue(args, distribution.flag)?.let {
      return Choice(it, distribution.flag)
    }
    env(distribution.env)
      ?.takeIf { it.isNotBlank() }
      ?.let {
        return Choice(it.trim(), distribution.env)
      }
    pathLookup(distribution.binary)?.let {
      return Choice(it.path, "PATH")
    }
    return cacheLookup()?.let { Choice(it.path, CACHE) }
  }

  private fun flagValue(args: List<String>, flag: String): String? {
    val index = args.indexOf(flag)
    if (index < 0 || index + 1 >= args.size) return null
    return args[index + 1].takeIf { it.isNotBlank() }?.trim()
  }

  /**
   * The first executable named [binary] on `PATH`.
   *
   * The working directory is deliberately not consulted: resolving a server from `.` would let a
   * checked-out repository decide what this command executes.
   */
  private fun onPath(binary: String): File? =
    System.getenv("PATH")
      ?.split(File.pathSeparator)
      ?.asSequence()
      ?.filter { it.isNotBlank() }
      ?.map { File(it, binary) }
      ?.firstOrNull { it.isFile && it.canExecute() }

  /**
   * What to tell someone who has not got one *and* could not be given one.
   *
   * Reached only after [ServerDistributionProvision.ensure] has failed and said why, so this does
   * not repeat the reason — it says what a person can do about it. Both halves matter: an offline
   * or firewalled machine needs the manual route, and a machine that can reach GitHub needs to know
   * the automatic one exists and will be retried.
   */
  fun installationHint(distribution: ReleasedDistribution = ReleasedDistribution.SERVER): String =
    """
    ${distribution.usedBy} needs ${distribution.label}, which ships separately from this CLI.

    The CLI normally fetches it for you on first use, from the pinned release of
    $PREVIEW_SERVER_REPO, into its own cache. That did not work this time — the line above
    says why (no network, a proxy, or no room on disk are the usual ones). Running the
    command again retries it.

    To supply one yourself instead: unpack `${distribution.binary}-<version>.tar.gz` from
    that repository's releases, then either put `${distribution.binary}` on PATH, set
    ${distribution.env}=/path/to/${distribution.binary}, or pass
    ${distribution.flag} /path/to/${distribution.binary}. `compose-preview doctor` reports
    which one it finds.

    The offline commands — render, show, bundle, history, a11y, and `mcp install` /
    `mcp doctor` — are unaffected and need neither binary.
    """
      .trimIndent()
}
