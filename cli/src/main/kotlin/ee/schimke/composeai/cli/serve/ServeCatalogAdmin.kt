package ee.schimke.composeai.cli.serve

/**
 * Publish and retire catalogs on a **running** server, and persist the result.
 *
 * The catalog set used to be a startup-only decision: a comma-separated flag the container
 * entrypoint baked in, so adding a catalog meant editing the image's compose file and recreating
 * the container. This is the runtime half of making it config ([ServeCatalogsConfig]) — the admin
 * API's `POST`/`DELETE` land here, which:
 * 1. validates the entry (id/repo shape, unknown group, duplicate system),
 * 2. fetches + registers (or unregisters) the catalog through the same [ServeCatalogStore] path
 *    startup uses, so a runtime catalog is in every way an ordinary one,
 * 3. records it in the [CatalogLoadTracker] — the configured-set source of truth the home index,
 *    `/status`, and the branch refresher all read, so the change is visible everywhere at once,
 * 4. rewrites the operator's `catalogs.json` so it survives a restart.
 *
 * Persistence is best-effort and reported, never fatal: a registration that worked but couldn't be
 * written back is still serving, and says so, rather than being rolled back.
 */
class ServeCatalogAdmin(
  private val tracker: CatalogLoadTracker,
  /** The server's `--catalog-repo`, used when an entry names no repo of its own. */
  private val defaultRepo: String,
  /** The server's `--catalog-branch-prefix` (`design-artifacts/`), for the watched branch name. */
  private val branchPrefix: String,
  /**
   * The operator's config file; null ⇒ registrations are runtime-only and don't survive restart.
   */
  private val configFile: ServeCatalogsConfigFile?,
  /** Fetch + register one catalog. Returns null on success, else the failure reason. */
  private val load: (system: String, repo: String) -> String?,
  /** Drop a registered catalog's session (and its daemon), if any. */
  private val unload: (system: String) -> Unit,
  /** Group table for resolving [ServeCatalogsConfig.Entry.group]; seeded from the config file. */
  groups: List<ServeCatalogsConfig.Group> = emptyList(),
  private val onLog: (String) -> Unit = { System.err.println(it) },
) {
  /**
   * The group table, refreshed from the file each time [persist] rewrites it (an operator can add a
   * section by hand between admin calls). Replaced wholesale rather than mutated in place, so a
   * concurrent [register] reading it can't observe a half-rebuilt list and reject a group that does
   * exist.
   */
  @Volatile private var groups: List<ServeCatalogsConfig.Group> = groups

  /**
   * Serialises the config file's **whole** read-modify-write, not just the write. Two admin
   * requests land on different threads; each would otherwise load the same original document, apply
   * its own edit, and atomically move — last one wins, both report success, and the loser's catalog
   * silently vanishes on the next restart. Atomicity of the individual save doesn't help, because
   * the lost update happens between the load and the save. Also guards [groups], which `persist`
   * refreshes from the file it just wrote.
   */
  private val configLock = Any()

  /** The outcome of an admin mutation, mapped to an HTTP status by the caller. */
  sealed interface Result {
    /** The catalog is serving (or gone). [warning] flags a non-fatal persistence failure. */
    data class Ok(val system: String, val warning: String? = null) : Result

    /** The request was malformed / named an unknown group — a 400. */
    data class Invalid(val reason: String) : Result

    /** The system is already published (register) or isn't (unregister) — a 409 / 404. */
    data class Conflict(val reason: String) : Result

    /** The entry was accepted but the catalog couldn't be fetched — a 502. */
    data class Failed(val system: String, val reason: String) : Result
  }

  /** The currently configured catalogs, in front-page order. */
  fun list(): List<CatalogLoadTracker.State> = tracker.snapshot()

  /**
   * Publish [entry]. The catalog is fetched before it's persisted, so a typo'd repo fails loudly
   * instead of leaving an unservable entry in the config for every future boot to retry.
   */
  fun register(entry: ServeCatalogsConfig.Entry): Result {
    ServeCatalogsConfig.validateEntry(entry)?.let {
      return Result.Invalid(it)
    }
    // One snapshot for both the check and the resolution, so a concurrent config rewrite can't
    // make this request validate against one group table and register against another.
    val declared = groups
    if (entry.group != null && declared.none { g -> g.id == entry.group }) {
      return Result.Invalid("unknown group '${entry.group}'")
    }
    if (tracker.configFor(entry.system) != null) {
      return Result.Conflict("catalog '${entry.system}' is already published")
    }
    val repo = entry.repo?.takeIf { it.isNotBlank() } ?: defaultRepo
    val config = configOf(entry, repo, declared)
    if (!tracker.add(config)) {
      return Result.Conflict("catalog '${entry.system}' is already published")
    }
    val failure = runCatching { load(entry.system, repo) }.getOrElse { it.message ?: "load failed" }
    if (failure != null) {
      // Never leave a half-published catalog behind: a failed fetch retires the entry it added.
      tracker.remove(entry.system)
      runCatching { unload(entry.system) }
      return Result.Failed(entry.system, failure)
    }
    onLog("serve: catalog ${entry.system} published via admin API (repo=$repo)")
    return Result.Ok(entry.system, persist { it.withEntry(entry.copy(repo = repo)) })
  }

  /** Retire [system] — its session is dropped and the entry removed from the config file. */
  fun unregister(system: String): Result {
    if (!tracker.remove(system)) {
      return Result.Conflict("catalog '$system' is not published here")
    }
    runCatching { unload(system) }
      .onFailure { onLog("serve: catalog $system unload failed: ${it.message}") }
    onLog("serve: catalog $system retired via admin API")
    return Result.Ok(system, persist { it.withoutEntry(system) })
  }

  private fun configOf(
    entry: ServeCatalogsConfig.Entry,
    repo: String,
    declaredGroups: List<ServeCatalogsConfig.Group>,
  ): CatalogLoadTracker.Config =
    CatalogLoadTracker.Config(
      system = entry.system,
      listed = entry.listed,
      repo = repo,
      branch = "$branchPrefix${entry.system}",
      group = homeGroup(entry, repo, declaredGroups),
    )

  /**
   * Apply [mutate] to the on-disk config. Returns null on success (or when no config file is
   * configured), else the warning to hand back with an otherwise-successful result.
   */
  private fun persist(mutate: (ServeCatalogsConfig) -> ServeCatalogsConfig): String? {
    val file = configFile ?: return "not persisted: no catalogs config file is configured"
    return synchronized(configLock) {
      runCatching {
          val updated = mutate(file.load())
          file.save(updated)
          groups = updated.groups
          null
        }
        .getOrElse { e ->
          onLog("serve: could not update ${file.displayPath}: ${e.message}")
          "not persisted: ${e.message ?: "write failed"}"
        }
    }
  }

  companion object {
    /**
     * The front-page section [entry] claims, with the repos allowed to satisfy that claim: the repo
     * the catalog is actually fetched from plus any operator-declared
     * [ServeCatalogsConfig.Entry.attributionRepos]. Null when the entry claims no group (or names
     * one the config doesn't define), which leaves the card to the source-repo fallback in
     * [ServeWeb.homeSections].
     */
    fun homeGroup(
      entry: ServeCatalogsConfig.Entry,
      repo: String,
      groups: List<ServeCatalogsConfig.Group>,
    ): ServeWeb.HomeGroup? {
      val group = entry.group?.let { id -> groups.firstOrNull { it.id == id } } ?: return null
      return ServeWeb.HomeGroup(
        heading = group.heading,
        noun = group.noun,
        repos = (entry.attributionRepos + repo).toSet(),
      )
    }
  }
}

/** This config with [entry] added (or replacing a same-system entry), preserving order. */
internal fun ServeCatalogsConfig.withEntry(entry: ServeCatalogsConfig.Entry): ServeCatalogsConfig {
  val known = catalogs.any { it.system == entry.system }
  val updated =
    if (known) catalogs.map { if (it.system == entry.system) entry else it } else catalogs + entry
  return copy(catalogs = updated)
}

/** This config with [system]'s entry removed. */
internal fun ServeCatalogsConfig.withoutEntry(system: String): ServeCatalogsConfig =
  copy(catalogs = catalogs.filterNot { it.system == system })
