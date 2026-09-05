package ee.schimke.composeai.cli

import java.io.File

/**
 * Finds the `compose-preview-server` binary that `serve` and `browse` exec.
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
    env: (String) -> String? = System::getenv,
    pathLookup: (String) -> File? = ::onPath,
    cacheLookup: () -> File? = { ServerDistributionProvision.cached(env) },
  ): Choice? {
    flagValue(args)?.let {
      return Choice(it, FLAG)
    }
    env(ENV)
      ?.takeIf { it.isNotBlank() }
      ?.let {
        return Choice(it.trim(), ENV)
      }
    pathLookup(BINARY)?.let {
      return Choice(it.path, "PATH")
    }
    return cacheLookup()?.let { Choice(it.path, CACHE) }
  }

  private fun flagValue(args: List<String>): String? {
    val index = args.indexOf(FLAG)
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
  fun installationHint(): String =
    """
    compose-preview serve needs the preview server, which ships separately from this CLI.

    The CLI normally fetches it for you on first use, from the pinned release of
    yschimke/compose-preview-server, into its own cache. That did not work this time — the
    line above says why (no network, a proxy, or no room on disk are the usual ones). Running
    the command again retries it.

    To supply one yourself instead: the server is published as
    `ee.schimke.composeai:compose-preview-serve`, and its distribution
    (`$BINARY-<version>.tar.gz`, on that repository's releases) provides `$BINARY`.
    Unpack it, then either put it on PATH, set $ENV=/path/to/$BINARY, or pass
    $FLAG /path/to/$BINARY. `compose-preview doctor` reports which one it finds.

    Everything else this CLI does — render, show, bundle, history, a11y, mcp — is unaffected
    and needs no server.
    """
      .trimIndent()
}
