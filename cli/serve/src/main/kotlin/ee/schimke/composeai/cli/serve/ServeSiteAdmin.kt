package ee.schimke.composeai.cli.serve

/**
 * Publish and retire **top-level sites** ([ServeSites]) on a running server, and persist the
 * result.
 *
 * The last piece of the deployment config that could only be changed by restarting the box. A
 * catalog, a front-page group and a trusted producer each have an admin route, so a committed
 * change reconciles onto a running server on every publish
 * (`.github/scripts/publish-config-to-box.sh`); `sites` had none, so a hostname committed to
 * `catalogs.json` reached nothing. The operator had to edit `SERVE_SITES` in the box's untracked
 * `.env` and recreate the container — config drift by construction, and the reason the file's own
 * comment had to warn that committing a site does not deliver it.
 *
 * What this does *not* do is make a site reachable on its own: DNS still has to point at the box,
 * and the edge still has to route the hostname and hold a certificate for it — `SITE_DOMAINS`,
 * which the caddy container now derives from this same file instead of the operator's `.env`. This
 * closes the half that was genuinely a restart, the app's own map, and leaves the two that are
 * genuinely external.
 *
 * Validation is [ServeSites]' own, applied to the *candidate* map rather than re-implemented here:
 * a host that isn't a hostname, a system this server doesn't serve, an id that collides with a
 * built-in route and a duplicate host are all rejected with the reason [ServeSites.of] gives. An
 * entry accepted here therefore behaves exactly as the same entry would have on a restart — there
 * is no second, drifting copy of the rules.
 *
 * Persistence is best-effort and reported, never fatal — matching [ServeCatalogAdmin], a site that
 * is serving but couldn't be written back says so instead of being rolled back.
 */
class ServeSiteAdmin(
  /** The live map the request path reads. Swapped wholesale on every accepted mutation. */
  private val registry: ServeSiteRegistry,
  /** The catalogs this server currently serves — a site may only name one of these. */
  private val servedSystems: () -> Set<String>,
  /** The operator's config file; null ⇒ mutations are runtime-only and don't survive a restart. */
  private val configFile: ServeCatalogsConfigFile?,
  private val onLog: (String) -> Unit = { System.err.println(it) },
) {
  /** The outcome of a mutation, mapped to an HTTP status by the caller. */
  sealed interface Result {
    /** The site is serving (or gone). [warning] flags a non-fatal persistence failure. */
    data class Ok(val host: String, val warning: String? = null) : Result

    /** Malformed, or naming a system this server doesn't serve — a 400. */
    data class Invalid(val reason: String) : Result

    /** Already configured exactly as asked (add) or not configured at all (remove) — 409 / 404. */
    data class Conflict(val reason: String) : Result
  }

  /** The configured sites, in configuration order. */
  fun list(): List<ServeCatalogsConfig.Site> =
    registry.current.pairs.map { (host, system) -> ServeCatalogsConfig.Site(host, system) }

  /**
   * Publish [site], or re-point a host that is already configured at a different system.
   *
   * Re-pointing converges in place rather than being refused, because the reconcile is additive and
   * a host whose system changed in the committed file has no other way to reach the box. An entry
   * that is already exactly what was asked for comes back [Result.Conflict], which the reconcile
   * script reads as "already present" — the same 409-is-success contract `/admin/catalogs` uses.
   */
  fun add(site: ServeCatalogsConfig.Site): Result {
    val host =
      ServeSites.normalizeHost(site.host)
        ?: return Result.Invalid("site host '${site.host}' is not a hostname")
    val current = registry.current
    if (current.systemFor(host) == site.system) {
      return Result.Conflict("site '$host' already serves '${site.system}'")
    }
    val problems = mutableListOf<String>()
    val candidate = current.pairs.filterNot { it.first == host } + (host to site.system)
    val next =
      ServeSites.of(candidate, knownSystems = servedSystems(), onProblem = { problems += it })
    if (next.systemFor(host) == null) {
      // Every rejection reason names the host, so the first one that does is this entry's.
      return Result.Invalid(
        problems.firstOrNull { it.contains("'$host'") }
          ?: problems.firstOrNull()
          ?: "site '$host' was rejected"
      )
    }
    return commit(next, host, "published")
  }

  /** Retire the site on [rawHost]. The catalog itself is untouched — only the hostname goes. */
  fun remove(rawHost: String): Result {
    val host =
      ServeSites.normalizeHost(rawHost)
        ?: return Result.Invalid("site host '$rawHost' is not a hostname")
    val current = registry.current
    if (current.systemFor(host) == null) {
      return Result.Conflict("site '$host' is not configured here")
    }
    val remaining = current.pairs.filterNot { it.first == host }
    return commit(ServeSites.of(remaining, knownSystems = servedSystems()), host, "retired")
  }

  /** Swap [next] in for the live map, write it back, and report what persistence made of it. */
  private fun commit(next: ServeSites, host: String, verb: String): Result {
    registry.replace(next)
    val warning = persist(next)
    onLog("serve: site $host $verb via admin API")
    return Result.Ok(host, warning)
  }

  /**
   * Write the whole site list back to `catalogs.json`, so the swap survives a restart.
   *
   * The *map* is the source of truth for the write, not an incremental edit of the file: a site
   * retired at runtime has to disappear from the file too, and rewriting the list wholesale is the
   * only spelling of that which can't drift from what the server is actually serving.
   */
  private fun persist(sites: ServeSites): String? {
    val file = configFile ?: return "not persisted: no catalogs config file is configured"
    return runCatching {
      file.update { config ->
        config.withSites(sites.pairs.map { (h, s) -> ServeCatalogsConfig.Site(h, s) })
      }
      null
    }
      .getOrElse { e ->
        onLog("serve: could not update ${file.displayPath}: ${e.message}")
        "not persisted: ${e.message ?: "write failed"}"
      }
  }
}

/** This config with its `sites` list replaced by [sites] — the whole list, in map order. */
internal fun ServeCatalogsConfig.withSites(
  sites: List<ServeCatalogsConfig.Site>
): ServeCatalogsConfig = copy(sites = sites)
