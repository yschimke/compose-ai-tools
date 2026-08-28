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
  /**
   * The top-level sites this server publishes ([ServeSites]), so a catalog a hostname depends on
   * cannot be retired out from under it. The LIVE map ([ServeSiteRegistry]), not a startup
   * snapshot: a site published at runtime has to protect its catalog from the moment it exists, or
   * the two admin routes race to leave a hostname pointing at a system that was just retired. Empty
   * by default — a server with no sites is unaffected.
   */
  private val sites: ServeSiteRegistry = ServeSiteRegistry.empty(),
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
   * Guards [groups] across the write that refreshes it, so a concurrent [register] can't read a
   * table rebuilt from a document a different request is still replacing.
   *
   * The file's own read-modify-write is serialised by [ServeCatalogsConfigFile.update] — it moved
   * there when [ServeSiteAdmin] started editing the same document, because a lock held here only
   * ever serialised this administrator against itself.
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

  /** The front-page sections a catalog entry may claim. */
  fun listGroups(): List<ServeCatalogsConfig.Group> = groups

  /**
   * Define [group], or update the heading/noun of one that already exists.
   *
   * Groups used to be the one part of the catalog config with no runtime path at all: adding a
   * section meant editing the box's `catalogs.json` and restarting, and a catalog claiming a
   * section the server didn't know about was rejected outright. That made a committed config
   * genuinely unable to converge — the gap flagged on #2967.
   */
  fun upsertGroup(group: ServeCatalogsConfig.Group): Result {
    ServeCatalogsConfig.validateGroup(group)?.let {
      return Result.Invalid(it)
    }
    if (groups.any { it == group }) {
      return Result.Conflict("group '${group.id}' is already defined identically")
    }
    val warning = persist { it.withGroup(group) }
    // Re-resolve the claims of catalogs ALREADY registered: a section defined after its catalogs
    // were published must still collect them, or defining it does nothing visible.
    val moved = reapplyGroupClaims()
    onLog("serve: group ${group.id} defined via admin API (regrouped $moved catalog(s))")
    return Result.Ok(group.id, warning)
  }

  /** Delete group [id]. Catalogs claiming it fall back to their source repo's owner heading. */
  fun removeGroup(id: String): Result {
    if (groups.none { it.id == id }) {
      return Result.Conflict("group '$id' is not defined here")
    }
    val warning = persist { it.withoutGroup(id) }
    val moved = reapplyGroupClaims()
    onLog("serve: group $id removed via admin API (regrouped $moved catalog(s))")
    return Result.Ok(id, warning)
  }

  /**
   * Re-resolve every registered catalog's front-page placement against the current group table and
   * the persisted entry that declares it. Returns how many changed.
   *
   * Reads the entries from the config file rather than the tracker, because the tracker holds the
   * *resolved* [ServeWeb.HomeGroup] and not the `group` id that produced it — so the declared claim
   * only survives on disk. A catalog with no config entry (a `--catalogs` flag addition, say)
   * declares no group and is left alone.
   */
  private fun reapplyGroupClaims(): Int {
    val declared = configFile?.let { runCatching { it.load() }.getOrNull() } ?: return 0
    val table = groups
    var changed = 0
    for (entry in declared.catalogs) {
      val current = tracker.configFor(entry.system) ?: continue
      val resolved = homeGroup(entry, current.repo, table)
      if (
        current.group == resolved &&
          current.listed == entry.listed &&
          current.loadPriority == entry.loadPriority
      ) {
        continue
      }
      if (
        tracker.relist(
          entry.system,
          listed = entry.listed,
          group = resolved,
          loadPriority = entry.loadPriority,
        )
      ) {
        changed++
      }
    }
    return changed
  }

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
    val repo = entry.repo?.takeIf { it.isNotBlank() } ?: defaultRepo
    // Already published? Converge its LISTING rather than refusing outright. A flat conflict here
    // is
    // what made a committed config unable to catch up with a running box: re-posting an entry whose
    // group or listed flag had changed was rejected, so the box kept its original placement
    // forever.
    // The content is untouched — no re-fetch, no dropped load state. A repo change is handled
    // separately just below: it DOES decide what bytes get served, so it re-fetches, and it does
    // that before anything is dropped.
    // `loadPriority` converges the same way: it changes nothing about a catalog already registered,
    // but the point of writing it back is the NEXT boot's fetch order, and the deployment reconcile
    // (.github/scripts/publish-config-to-box.sh) is additive — without this, re-declaring a
    // priority on an already-published catalog would 409 and never reach the box's config.
    tracker.configFor(entry.system)?.let { current ->
      val resolved = homeGroup(entry, repo, declared)
      // A REPO CHANGE is a swap, not a conflict — and the order here is the whole point.
      //
      // This used to answer 409 "retire it before re-publishing from …", which made re-pointing a
      // catalog a two-step dance every caller had to get right: DELETE, then POST. It is not a safe
      // dance. `load` fetches before anything is persisted, and the failure path below drops the
      // entry it added — so a retire that succeeds followed by a publish that cannot fetch leaves
      // the system published NOWHERE, and the deployment reconcile that drives this is
      // non-blocking, so it stays that way. The 409 also read as success to that reconcile
      // (.github/scripts/publish-config-to-box.sh), which is how a moved catalog went on being
      // served from the repository it had left, with a green log either side of it.
      //
      // Loading FIRST removes the window instead of narrowing it. A load that fails returns before
      // it touches any registration, so the old catalog is still serving and this returns Failed
      // with that said plainly; a load that succeeds re-registers the host in place — exactly what
      // the branch refresher does on every poll — so the swap is one atomic replacement of content
      // followed by [CatalogLoadTracker.repoint] recording where it now comes from.
      //
      // It works for a catalog published as a top-level site, too, which the retire-first route
      // could not: `unregister` refuses those outright to keep a hostname from being stranded, and
      // a swap never strands one because the system never stops existing.
      if (current.repo != repo) {
        val failure = runCatching {
          load(entry.system, repo)
        }
          .getOrElse { it.message ?: "load failed" }
        if (failure != null) {
          return Result.Failed(
            entry.system,
            "could not re-point '${entry.system}' to $repo, still serving ${current.repo}: $failure",
          )
        }
        tracker.repoint(entry.system, repo = repo, branch = "$branchPrefix${entry.system}")
        tracker.relist(
          entry.system,
          listed = entry.listed,
          group = resolved,
          loadPriority = entry.loadPriority,
        )
        onLog("serve: catalog ${entry.system} re-pointed ${current.repo} -> $repo via admin API")
        return Result.Ok(entry.system, persist { it.withEntry(entry.copy(repo = repo)) })
      }
      if (
        current.group == resolved &&
          current.listed == entry.listed &&
          current.loadPriority == entry.loadPriority
      ) {
        return Result.Conflict("catalog '${entry.system}' is already published")
      }
      tracker.relist(
        entry.system,
        listed = entry.listed,
        group = resolved,
        loadPriority = entry.loadPriority,
      )
      onLog("serve: catalog ${entry.system} listing updated via admin API")
      return Result.Ok(entry.system, persist { it.withEntry(entry.copy(repo = repo)) })
    }
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
    // A site is a hostname pointing at this catalog, and retiring it would strand that hostname:
    // its root 404s immediately (the mapping still routes, the session is gone), and after a
    // restart `ServeSites.of` drops the now-unserved mapping so the host falls THROUGH to the
    // global front door — a domain published as one app quietly becoming an index of every other.
    // Fail closed instead: drop the site first, then retire.
    sites.hostFor(system)?.let { host ->
      return Result.Conflict(
        "catalog '$system' is published as the top-level site '$host'; remove the site first"
      )
    }
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
      loadPriority = entry.loadPriority,
    )

  /**
   * Apply [mutate] to the on-disk config. Returns null on success (or when no config file is
   * configured), else the warning to hand back with an otherwise-successful result.
   */
  private fun persist(mutate: (ServeCatalogsConfig) -> ServeCatalogsConfig): String? {
    val file = configFile ?: return "not persisted: no catalogs config file is configured"
    return synchronized(configLock) {
      runCatching {
        // The read-modify-write itself is serialised by the FILE (every administrator that edits
        // this document shares one instance); configLock additionally guards `groups`, which is
        // refreshed from what was written.
        groups = file.update(mutate).groups
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
        priority = group.priority,
      )
    }
  }
}

/** This config with [group] added, or replacing a same-id group, preserving section order. */
internal fun ServeCatalogsConfig.withGroup(group: ServeCatalogsConfig.Group): ServeCatalogsConfig {
  val known = groups.any { it.id == group.id }
  val updated = if (known) groups.map { if (it.id == group.id) group else it } else groups + group
  return copy(groups = updated)
}

/**
 * This config with group [id] removed. Entries claiming it are left declaring it: an unknown group
 * id is already a tolerated condition ([ServeCatalogsConfig.problems] reports it, and the card
 * falls back to its owner heading), and silently rewriting an operator's catalog entries because a
 * section was deleted would lose the claim they'd have to retype if the group came back.
 */
internal fun ServeCatalogsConfig.withoutGroup(id: String): ServeCatalogsConfig =
  copy(groups = groups.filterNot { it.id == id })

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
