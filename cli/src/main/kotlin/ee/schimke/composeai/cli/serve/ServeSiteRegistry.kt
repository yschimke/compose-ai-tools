package ee.schimke.composeai.cli.serve

import java.util.concurrent.atomic.AtomicReference

/**
 * The **live** top-level site map: a [ServeSites] that can be replaced while the server runs.
 *
 * Sites used to be startup-only — read from `--sites` or `catalogs.json` once and captured in a
 * `val` — which made them the last part of the deployment config with no path to a running box.
 * Catalogs, front-page groups and trusted producers all converge through the admin API
 * ([ServeCatalogAdmin], [ServeTrustAdmin]), so a committed change reaches the box on the next
 * publish; a committed *site* reached nothing, because the file it lives in is seeded once and the
 * reconcile had no route to POST it to. Adding a hostname therefore meant editing `.env` on the
 * host and restarting the server — exactly the drift the admin API exists to remove (the same gap
 * groups had before `/admin/groups`, #2967).
 *
 * This is the seam that closes it. The map is an immutable [ServeSites] swapped wholesale through
 * an [AtomicReference], never mutated in place, so a request either sees the old map or the new one
 * and never a half-built one. Reads are a single volatile load on a path that already does a map
 * lookup, so the fast path costs the same as the `val` did.
 *
 * The read API is delegated rather than exposed as `.current.…` on purpose: every caller wants the
 * live value, and a caller that captured [current] in a local would keep serving a retired site for
 * as long as it held it. Two reads inside one request can straddle a swap, which is harmless — the
 * worst case is a site added mid-request being seen by one lookup and not the next, and the next
 * request sees it consistently.
 */
class ServeSiteRegistry(initial: ServeSites = ServeSites.EMPTY) {

  private val ref = AtomicReference(initial)

  /** The site map as it is right now. Prefer the delegating members below. */
  val current: ServeSites
    get() = ref.get()

  /** Replace the site map wholesale. Returns the map that was in force before the swap. */
  fun replace(sites: ServeSites): ServeSites = ref.getAndSet(sites)

  /** True when no site hosts are configured — the fast path on every request. */
  val isEmpty: Boolean
    get() = current.isEmpty

  /** The configured site hosts, normalised. */
  val hosts: Set<String>
    get() = current.hosts

  /** The system [rawHost] is a top-level site for, or null when it isn't one. */
  fun systemFor(rawHost: String?): String? = current.systemFor(rawHost)

  /** The host [system] is published on as a top-level site, or null when it isn't. */
  fun hostFor(system: String): String? = current.hostFor(system)

  companion object {
    /** A registry that starts empty — the default, and what a server with no sites uses. */
    fun empty(): ServeSiteRegistry = ServeSiteRegistry()

    /** A registry seeded from `host to system` pairs, for tests and startup wiring. */
    fun of(
      pairs: List<Pair<String, String>>,
      knownSystems: Set<String>? = null,
      onProblem: (String) -> Unit = {},
    ): ServeSiteRegistry = ServeSiteRegistry(ServeSites.of(pairs, knownSystems, onProblem))
  }
}
