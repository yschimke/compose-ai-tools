package ee.schimke.composeai.cli.serve

/**
 * **Top-level sites**: a catalog this server already publishes at `/<system>/`, additionally
 * reachable on a hostname of its own where it looks like the only thing on the box — e.g.
 * `m3.preview.coo.ee` serving what `preview.coo.ee/m3-catalog/` serves.
 *
 * This is deliberately a **view**, not a second deployment. The same [ServeSessionRegistry]
 * session, the same baked pixels, the same daemon (if any), the same hero/social/asset caches: a
 * site host costs one map lookup on the request and changes nothing about what the server does with
 * the request afterwards. Adding a site adds no catalog, no render, no memory. What it changes is
 * only what the request *resolves to* and what the pages *say*:
 *
 * - the site's system is the session, so the root-mounted routes (`/`, `/p/<id>`, `/render/<id>`,
 *   `/api/previews`, …) — which already exist for the legacy `?session=` form — answer for it;
 * - links are built with an **empty** base path, so every href stays on the custom domain rather
 *   than pointing back at `preview.coo.ee/<system>/`;
 * - the front-door index, the "← All design systems" back button and the cross-system nav are
 *   suppressed, so the site doesn't advertise its neighbours;
 * - `/status`, `/robots.txt` and `/sitemap.xml` are scoped to this one system.
 *
 * The canonical `/<system>/` form is still served on the *main* host; on a site host it redirects
 * to the root form so the two spellings don't compete for indexing, and another system's path 404s
 * rather than quietly serving a neighbour under the wrong domain.
 *
 * Nothing here grants access: a site can only name a system this server is already configured to
 * serve, and every route stays behind the same token/public gate it always was.
 */
data class ServeSites(private val byHost: Map<String, String>) {

  /** True when no site hosts are configured — the default, and the fast path on every request. */
  val isEmpty: Boolean
    get() = byHost.isEmpty()

  /** The configured site hosts, normalised (lowercase, no port). */
  val hosts: Set<String>
    get() = byHost.keys

  /** The systems reachable as top-level sites, deduplicated. */
  val systems: Set<String>
    get() = byHost.values.toSet()

  /**
   * The configured `host to system` pairs, in configuration order — the map as [of] would take it
   * back. What [ServeSiteAdmin] rebuilds a candidate map from, and what gets written to
   * `catalogs.json`, so a runtime change round-trips through exactly the same validation a restart
   * would apply.
   */
  val pairs: List<Pair<String, String>>
    get() = byHost.entries.map { it.key to it.value }

  /**
   * The system [rawHost] is a top-level site for, or null when it isn't one (the main host, an
   * IP/localhost dev origin, an unknown vhost). [rawHost] is taken straight from `X-Forwarded-Host`
   * / `Host`, so it may carry a port, a trailing dot, or bracketed IPv6 — [normalizeHost] deals
   * with that.
   */
  fun systemFor(rawHost: String?): String? {
    if (byHost.isEmpty() || rawHost == null) return null
    return byHost[normalizeHost(rawHost) ?: return null]
  }

  /** The host this [system] is published on as a top-level site, or null when it isn't. */
  fun hostFor(system: String): String? = byHost.entries.firstOrNull { it.value == system }?.key

  companion object {
    /** No sites configured. Every request then behaves exactly as it did before this existed. */
    val EMPTY: ServeSites = ServeSites(emptyMap())

    /**
     * A hostname as it may be written in config: labels of letters/digits/hyphens separated by
     * dots. Deliberately narrow — a site host is compared against an attacker-supplied `Host`
     * header, so the set of strings that can ever match is worth keeping small and boring.
     */
    private val HOST_RE =
      Regex(
        "[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+"
      )

    /**
     * The comparable form of a `Host` / `X-Forwarded-Host` value: lowercased, port dropped, IPv6
     * brackets and the root-zone trailing dot stripped. Null when what's left isn't a hostname we
     * would ever have accepted as config, so a junk header can't match anything.
     *
     * Host headers are case-insensitive and routinely carry a port (`m3.preview.coo.ee:8080` from a
     * local `curl`, or from a reverse proxy that doesn't rewrite it), and `m3.preview.coo.ee.` is a
     * legal absolute spelling of the same name. All three have to land on the same key or a site
     * would work through Caddy and mysteriously not through a direct hit.
     */
    fun normalizeHost(raw: String): String? {
      var host = raw.trim().lowercase()
      if (host.startsWith("[")) {
        // IPv6 literal: never a site host, but strip it cleanly rather than mangling the port cut.
        host = host.substringAfter('[').substringBefore(']')
      } else {
        host = host.substringBefore(':')
      }
      host = host.trimEnd('.')
      return host.takeIf { HOST_RE.matches(it) }
    }

    /**
     * Build a site map from `host=system` pairs, dropping any that are malformed or that name a
     * system this server doesn't serve. [knownSystems] is the served catalog set; an empty set
     * means "don't check" (the caller validates elsewhere). [onProblem] receives one line per
     * dropped entry so startup can report it instead of silently serving less than configured.
     *
     * First host wins on a duplicate — the same rule the catalog config uses — because a host that
     * resolved to two systems would serve whichever the map iteration happened to keep.
     */
    fun of(
      pairs: List<Pair<String, String>>,
      /**
       * The systems this server serves, or null to skip the check entirely (tests, and callers that
       * validate elsewhere). Deliberately nullable rather than "empty means don't check": a
       * module-backed server with no catalogs at all knows an EMPTY set, and a site naming anything
       * on it must be dropped — reading that as "unvalidated" kept a dead hostname that 404s every
       * route instead of reporting the typo at startup.
       */
      knownSystems: Set<String>? = null,
      onProblem: (String) -> Unit = {},
    ): ServeSites {
      val byHost = LinkedHashMap<String, String>()
      for ((rawHost, system) in pairs) {
        val host = normalizeHost(rawHost)
        if (host == null) {
          onProblem("site host '$rawHost' is not a hostname")
          continue
        }
        if (!SYSTEM_RE.matches(system)) {
          onProblem("site '$host' names an invalid system '$system'")
          continue
        }
        if (knownSystems != null && system !in knownSystems) {
          onProblem("site '$host' names '$system', which this server does not serve")
          continue
        }
        // A: a site's system id is ALSO the first path segment its canonical URLs use, and the
        // canonical-path redirect keys off exactly that. An id that collides with a rooted route
        // — a catalog literally called `render`, `p` or `api` — would make the interceptor read
        // this site's own `/render/<id>.png` as a prefixed URL and redirect it to `/<id>.png`,
        // breaking every image on the site. Such an id is already ambiguous on the main host
        // (the constant route outscores `/{system}`), so it is refused here rather than served
        // half-working.
        if (system in RESERVED_SYSTEMS) {
          onProblem("site '$host' names '$system', which collides with a built-in route")
          continue
        }
        if (byHost.putIfAbsent(host, system) != null) {
          onProblem("duplicate site host '$host' (keeping '${byHost[host]}')")
        }
      }
      return if (byHost.isEmpty()) EMPTY else ServeSites(byHost)
    }

    /**
     * Parse the `--sites` flag: `m3.preview.coo.ee=m3-catalog,wear.preview.coo.ee=wear-m3`. Blank
     * or null ⇒ [EMPTY]. Entries the shape can't be read from are reported through [onProblem] and
     * skipped, matching how `--catalogs` treats a bad entry.
     */
    fun parse(
      spec: String?,
      /** As [of]: null skips the served-system check, an empty set fails every entry. */
      knownSystems: Set<String>? = null,
      onProblem: (String) -> Unit = {},
    ): ServeSites {
      if (spec.isNullOrBlank()) return EMPTY
      val pairs =
        spec.split(',').mapNotNull { raw ->
          val entry = raw.trim()
          if (entry.isEmpty()) return@mapNotNull null
          val host = entry.substringBefore('=').trim()
          val system = entry.substringAfter('=', "").trim()
          if (system.isEmpty()) {
            onProblem("site entry '$entry' is not <host>=<system>")
            return@mapNotNull null
          }
          host to system
        }
      return of(pairs, knownSystems, onProblem)
    }

    /** Same alphabet [ServeCatalogsConfig] accepts for a catalog id — a site can only name one. */
    private val SYSTEM_RE = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    /**
     * First path segments the server routes itself, which a site's system id therefore may not be.
     * These are the constant routes registered alongside `/{system}/…`; Ktor scores a constant
     * segment above the parameter, so a catalog with one of these ids is unreachable at its
     * canonical path on ANY host — a site just makes the collision visible.
     *
     * Enumerated from [ServeHttpServer]'s routing block — every `get("/x…` / `post("/x…` /
     * `webSocket("/x…` whose first segment is a literal, **including the ones built from a
     * constant** (`ServeRcFonts.URL_BASE`, `/hero`, `/social`, `/auth/…`), which are the easy ones
     * to miss.
     *
     * The list is load-bearing twice over, so keep it complete when adding a top-level route. A
     * missing entry lets a site *claim* that prefix and swallow the route — `pg` did exactly that,
     * breaking playground redemption — and, because the site interceptor uses this as its allowlist
     * of "not a session", a missing entry also 404s that route on every site host.
     *
     * Two tests keep it honest, because neither can do it alone. `ServeSitesReservedRoutesTest`
     * reads [ServeHttpServer]'s routing block and fails on a registered segment that is missing
     * from this list — the omission itself, which no test driving the list could ever see.
     * `ServeTopLevelSiteTest` drives real routes against a live server on a site host, which is the
     * only way to catch an entry that is listed here and still broken.
     */
    internal val RESERVED_SYSTEMS =
      setOf(
        "healthz",
        "readyz",
        "version",
        "status",
        "status.json",
        // Registered from `ServeBugReport.PATH`, and missed for the same reason `rc-fonts` nearly
        // was: the path is built from a constant, so a text search for `get("/report-bug` finds
        // nothing. Every site host answered its own styled 404 for the one link its footer offers
        // on every page — the affordance was unreachable on exactly the deployments (m3, wear)
        // where a visitor is most likely to press it (issue #4319).
        "report-bug",
        "robots.txt",
        "sitemap.xml",
        "favicon.svg",
        "favicon.ico",
        "apple-touch-icon.png",
        "assets",
        "wasm",
        // `GET /wasm-private/<access>/<system>/…` — the token-in-path twin of `/wasm/…` for
        // auto-discovered local apps. A site host is normally public, but the segment is a route
        // either way and a catalog may not claim it.
        "wasm-private",
        "rc-player",
        "rc-player-wasm",
        // Registered dynamically from `ServeRcFonts.URL_BASE` — the vendored Remote Compose
        // typefaces. Easy to miss precisely because the path is built from a constant.
        "rc-fonts",
        "doc-player",
        "hero",
        "social",
        "admin",
        "auth",
        // `/agent-access/…` — the agent access-grant flow (`--agent-grants`). Reserved
        // unconditionally like every other opt-in lane's segments: what a site host may name
        // itself cannot depend on a flag the operator can turn on later. It matters more here than
        // most, because the route a *human* opens is the approval page, and a site that had
        // claimed the prefix would 404 the link an agent just told someone to click.
        "agent-access",
        "bundles",
        "bundle",
        "bundle.zip",
        "docs",
        "d",
        // `POST /images` + `GET /i/<id>.png` — the image lane. Reserved unconditionally like every
        // other opt-in lane's segments: what a site host may name itself cannot depend on a flag
        // the operator can turn on later.
        "images",
        "i",
        "playground",
        // `GET /pg/<token>` — Stage-2 playground redemption.
        "pg",
        "api",
        "ws",
        "p",
        // `GET /usage/<previewId>` — the viewer's Source panel.
        "usage",
        "render",
        // `GET /motion/<previewId>.apng` — the viewer's Motion lane. Omitted when the lane landed,
        // which made every published capture 404 on a site host: the interceptor runs before
        // routing, so `/motion/…` was read as a neighbour catalog and refused with the site's own
        // styled page rather than reaching `handleMotion` at all.
        "motion",
        "history",
        "compare",
        "reference",
        "pages",
        // `GET /tags/<previewId>` — the published element tag index (see [ServeTagIndex]), which
        // the focused comparison's element selector fetches. Reserved beside its neighbours for the
        // same reason: a site host that had claimed the prefix would answer its own styled 404 and
        // the selector would silently offer no tag targets on exactly the deployments the parity
        // workflow runs on.
        "tags",
        "rc-compare",
        "parity",
        "parity.json",
        "refresh",
        // `GET /feed.xml` — the catalog change feed, registered only when a feed is configured.
        // Reserved unconditionally: what a site host may name itself cannot depend on a flag the
        // operator can turn on later.
        "feed.xml",
        "index.json",
        "iframe.html",
      )
  }
}
