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

  /**
   * [choice], unless it is a cached copy older than [minimum], in which case the newest release is
   * fetched in its place.
   *
   * Only the cache is second-guessed. A binary named by the flag, the environment or `PATH` was
   * chosen by someone, and so was a release pinned with `COMPOSE_PREVIEW_SERVER_VERSION`
   * ([requested]); those are launched as they are, and a server that lacks the command says so
   * itself. The cache is different because nobody chose it: it is whatever was newest the last time
   * this machine fetched, and without this a command added since then would never reach a server
   * that has it. If the fetch fails the old copy is still launched, with a note, rather than
   * nothing.
   */
  fun meetsMinimum(
    choice: Choice,
    minimum: String?,
    command: String,
    requested: String?,
    provision: () -> Choice?,
    log: (String) -> Unit = { System.err.println(it) },
  ): Choice {
    if (minimum == null || !isUnchosenCache(choice, requested)) return choice
    val version = cachedVersionOf(choice.binary) ?: return choice
    if (compareSemver(version, minimum) >= 0) return choice
    log(
      "compose-preview: the cached server $version predates `$command` (added in $minimum); " +
        "fetching the newest"
    )
    return provision()?.takeIf { it.binary != choice.binary }
      ?: choice.also {
        log("compose-preview: could not fetch a newer server; launching the cached $version")
      }
  }

  /** How often [refreshed] may ask for the newest release: once a day. */
  const val REFRESH_INTERVAL_MS: Long = 24L * 60 * 60 * 1000

  /** The file under a distribution's cache root whose mtime records the last [refreshed] check. */
  const val REFRESH_STAMP: String = ".latest-check"

  /**
   * [choice], unless it is a cached copy older than the newest release, in which case that release
   * is fetched and launched instead (#5602).
   *
   * [meetsMinimum]'s rule about *which* choices may be second-guessed applies unchanged — only an
   * unpinned cache — but the trigger is time rather than a command: at most once per
   * [REFRESH_INTERVAL_MS], recorded in [stamp], the newest release is resolved and compared. The
   * stamp is written before asking, so a machine that cannot reach GitHub pays for one failed
   * lookup a day rather than one per launch. Offline mode never asks. Any failure launches the
   * cached copy with one line of explanation.
   */
  fun refreshed(
    choice: Choice,
    requested: String?,
    offline: Boolean,
    stamp: File,
    latest: () -> String?,
    provision: (String) -> Choice?,
    now: Long = System.currentTimeMillis(),
    log: (String) -> Unit = { System.err.println(it) },
  ): Choice {
    if (offline || !isUnchosenCache(choice, requested)) return choice
    val version = cachedVersionOf(choice.binary) ?: return choice
    val last = stamp.lastModified()
    if (last > 0 && now - last in 0 until REFRESH_INTERVAL_MS) return choice
    try {
      stamp.parentFile?.mkdirs()
      if (!stamp.exists()) stamp.createNewFile()
      stamp.setLastModified(now)
    } catch (_: Exception) {
      // An unwritable stamp only means the check repeats next launch.
    }
    val newest =
      latest()
        ?: return choice.also {
          log("compose-preview: could not check for a newer server; launching the cached $version")
        }
    if (compareSemver(newest, version) <= 0) return choice
    log(
      "compose-preview: the cached server $version is older than the newest, $newest; fetching it"
    )
    return provision(newest)?.takeIf { it.binary != choice.binary }
      ?: choice.also {
        log("compose-preview: could not fetch server $newest; launching the cached $version")
      }
  }

  /**
   * One stderr line naming what is about to be launched: the version when it is a cached release,
   * and where it came from — the cache, a fresh download, or an override (flag, environment,
   * `PATH`).
   */
  fun describeLaunch(choice: Choice, downloaded: Boolean, label: String): String {
    val origin =
      when {
        choice.source != CACHE -> "override: ${choice.source}"
        downloaded -> "downloaded"
        else -> "cache"
      }
    val version = if (choice.source == CACHE) cachedVersionOf(choice.binary) else null
    return "compose-preview: launching $label ${version ?: choice.binary} ($origin)"
  }

  /**
   * Nobody chose it: a cached copy, with no release pinned by `COMPOSE_PREVIEW_SERVER_VERSION`. The
   * only kind of choice [meetsMinimum] and [refreshed] replace.
   */
  private fun isUnchosenCache(choice: Choice, requested: String?): Boolean =
    choice.source == CACHE && requested == null

  /** The release a cached launcher belongs to: `<cache>/<version>/bin/<binary>`. */
  internal fun cachedVersionOf(binary: String): String? =
    File(binary).parentFile?.parentFile?.name?.takeIf { it.isNotBlank() }

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
