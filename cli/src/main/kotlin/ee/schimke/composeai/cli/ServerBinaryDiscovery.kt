package ee.schimke.composeai.cli

import java.io.File

/**
 * Finds the binary a launcher command execs: the preview server for `serve`, `browse` and
 * `ui-builder`, the MCP server for `mcp serve` (parameterised by [ReleasedDistribution]; [FLAG] /
 * [ENV] / [BINARY] name the server's for `doctor` and tests).
 *
 * Order, most explicit first so the user's choice always wins: the flag, the environment, `PATH`,
 * then the copy [ServerDistributionProvision] cached. A miss isn't yet a failure: the caller asks
 * [ServerDistributionProvision] to fetch the release, and only a failed fetch reports
 * [installationHint].
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
   * [choice], unless it is an unpinned cached copy older than [minimum], in which case the newest
   * release is fetched instead. Explicitly chosen binaries (flag, environment, `PATH`,
   * `COMPOSE_PREVIEW_SERVER_VERSION`) are launched as-is. If the fetch fails the old copy still
   * launches, with a note.
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
   * [choice], unless it is an unpinned cached copy older than the newest release, which is then
   * fetched and launched. Checked at most once per [REFRESH_INTERVAL_MS] via [stamp] (written
   * before asking, so offline machines pay one failed lookup a day). Never in offline mode;
   * failures launch the cached copy.
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
   * One stderr line naming what is launching: the version for cached releases, and whether it came
   * from the cache, a fresh download, or an override.
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
   * A cached copy with no release pinned by `COMPOSE_PREVIEW_SERVER_VERSION` — the only choice
   * [meetsMinimum] and [refreshed] replace.
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
   * The first executable named [binary] on `PATH`; never the working directory, so a checkout can't
   * choose what runs.
   */
  private fun onPath(binary: String): File? =
    System.getenv("PATH")
      ?.split(File.pathSeparator)
      ?.asSequence()
      ?.filter { it.isNotBlank() }
      ?.map { File(it, binary) }
      ?.firstOrNull { it.isFile && it.canExecute() }

  /**
   * What to tell someone who has no binary and couldn't be given one: the manual route, and that
   * the automatic fetch will be retried. The failure reason was already printed.
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
