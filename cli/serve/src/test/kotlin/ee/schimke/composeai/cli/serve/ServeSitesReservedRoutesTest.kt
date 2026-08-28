package ee.schimke.composeai.cli.serve

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [ServeSites.RESERVED_SYSTEMS] must name **every** constant first path segment [ServeHttpServer]
 * routes, and this reads the routing block to check it rather than trusting anyone to remember.
 *
 * The list has always been maintained by hand from that block, and the hand has slipped three times
 * now — `pg` (playground redemption swallowed by a site claiming the prefix), `motion` (every
 * published capture 404ing on every site host), and `report-bug` (issue #4319, the footer's own
 * link, on the deployments where visitors actually press it). Each was found in production, months
 * apart, because the failure is invisible on the main host: a missing entry only misbehaves on a
 * site hostname, which is exactly where nobody is running the test suite.
 *
 * [ServeTopLevelSiteTest] stays as it is and is the *other* half of this: it drives a live server
 * and asserts real routes reach real handlers, which is the only way to catch a list entry that is
 * spelled right and routed wrong. This one catches the omission itself, which that test cannot — a
 * route missing from the allowlist is missing from its loop too.
 *
 * Scanning source rather than the Ktor route tree is deliberate: the tree is built inside
 * `embeddedServer`'s private application, and a registration guarded by a config flag (`/feed.xml`
 * needs a feed configured, the `/admin/…` pair needs a token) simply would not be in it — so a
 * tree-walk would quietly under-report exactly the routes a fresh deployment is most likely to
 * enable later.
 */
class ServeSitesReservedRoutesTest {

  /**
   * Registrations whose path is built from a constant instead of written out. These are the ones
   * that get missed — a text search for `get("/report-bug` finds nothing — so the test resolves
   * them by name and [routeRegistrations] fails on any expression that is not here, rather than
   * skipping what it cannot read.
   */
  private val constantPaths =
    mapOf(
      "ServeBugReport.PATH" to ServeBugReport.PATH,
      "ServeGithubAuth.START_PATH" to ServeGithubAuth.START_PATH,
      "ServeGithubAuth.CALLBACK_PATH" to ServeGithubAuth.CALLBACK_PATH,
      "ServeSiteIcon.SVG_PATH" to ServeSiteIcon.SVG_PATH,
      "ServeSiteIcon.ICO_PATH" to ServeSiteIcon.ICO_PATH,
      "ServeSiteIcon.APPLE_TOUCH_PATH" to ServeSiteIcon.APPLE_TOUCH_PATH,
      "ServeRcFonts.URL_BASE" to ServeRcFonts.URL_BASE,
      "ServeAgentGrants.BASE_PATH" to ServeAgentGrants.BASE_PATH,
      "ServeAgentGrants.REQUEST_PATH" to ServeAgentGrants.REQUEST_PATH,
      "ServeAgentGrants.POLL_PATH" to ServeAgentGrants.POLL_PATH,
      "ServeAgentGrants.REVOKE_PATH" to ServeAgentGrants.REVOKE_PATH,
      "ServeAgentGrants.WHOAMI_PATH" to ServeAgentGrants.WHOAMI_PATH,
    )

  private val source: String
    get() =
      File(
          repoRoot(),
          "cli/serve/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeHttpServer.kt",
        )
        .also { assertTrue(it.isFile, "routing block not found at $it") }
        .readText()

  /**
   * `get(…)` / `post(…)` / `webSocket(…)` and its single path argument.
   *
   * Not anchored to the line start: `/pg/{pgToken}` is registered inside a `?.let { redeem -> … }`,
   * which is exactly the kind of registration that goes missing. A leading boundary keeps `.get(`
   * on a map out, and requiring a non-empty argument keeps Kotlin's own `get() = …` accessors out.
   */
  private val registration =
    Regex("""(?:^|[\s{])(?:get|post|put|delete|head|options|webSocket|sse)\(([^)]*[^)\s])\)\s*\{""")

  /** A plain `"/path/{param}"`. */
  private val literal = Regex("""^"(/[^"]*)"$""")

  /** A path opening with a constant — `"${'$'}{ServeRcFonts.URL_BASE}/{name}"`. */
  private val templated = Regex("""^"\$\{([A-Za-z][A-Za-z0-9_.]*)}([^"]*)"$""")

  /** A bare constant — `get(ServeBugReport.PATH)`. */
  private val bare = Regex("""^[A-Za-z][A-Za-z0-9_.]*$""")

  /** Every registered path, with the constants resolved. */
  private fun routeRegistrations(): List<String> =
    registration.findAll(source).map { match -> resolve(match.groupValues[1].trim()) }.toList()

  private fun resolve(argument: String): String {
    literal.find(argument)?.let {
      return it.groupValues[1]
    }
    templated.find(argument)?.let {
      val base =
        constantPaths[it.groupValues[1]]
          ?: fail("unknown path constant '${it.groupValues[1]}' — add it to constantPaths")
      return base + it.groupValues[2]
    }
    if (bare.matches(argument)) {
      return constantPaths[argument] ?: fail("unknown path constant '$argument' — add it")
    }
    fail(
      "a route is registered from an expression this test cannot read: '$argument'. Resolve it in " +
        "constantPaths (and make sure its first segment is in ServeSites.RESERVED_SYSTEMS)."
    )
  }

  @Test
  fun `every constant top-level route segment is reserved`() {
    val paths = routeRegistrations()
    // A sanity floor on the scan itself: a regex that silently stops matching would otherwise turn
    // this test into an assertion about the empty list, which passes forever.
    assertTrue(paths.size > 80, "the routing scan found only ${paths.size} routes — regex rotted?")
    val missing =
      paths
        .map { it.trimStart('/').substringBefore('/') }
        .filter { it.isNotEmpty() && !it.startsWith("{") }
        .distinct()
        .filterNot { it in ServeSites.RESERVED_SYSTEMS }
    assertTrue(
      missing.isEmpty(),
      "these top-level routes are missing from ServeSites.RESERVED_SYSTEMS, so a site host " +
        "answers its own 404 for each of them (and a catalog could claim the prefix): $missing",
    )
  }

  @Test
  fun `the reserved list names no segment the server does not route`() {
    // The other direction, kept as a warning rather than a hard match: an entry that no longer
    // corresponds to a route only costs a catalog the right to that id, which is harmless — but it
    // is usually the trace of a route that was renamed and whose new spelling was never added.
    val routed = routeRegistrations().map { it.trimStart('/').substringBefore('/') }.toSet()
    val stale = ServeSites.RESERVED_SYSTEMS.filterNot { it in routed }
    assertTrue(
      stale.isEmpty(),
      "reserved but no longer routed — did a route get renamed without updating the list? $stale",
    )
  }
}
