package ee.schimke.composeai.cli.serve

/**
 * The crawler-facing pair: `/robots.txt` and `/sitemap.xml`.
 *
 * Both exist for the same reason the Open Graph block in [ServeWeb.document] does — a link dropped
 * into Slack or Google Chat is fetched by a robot, not a browser, and what that robot is allowed to
 * ask for decides whether the card appears. Neither file had ever been served, so `/robots.txt`
 * answered with the styled HTML 404 and the crawlers that look for one (Slackbot documents that it
 * does) had to guess.
 *
 * The split this file encodes is **cheap published bytes vs. work**. A catalog landing, a viewer
 * page, and their baked PNGs are static files behind a map lookup, and they are exactly what a
 * shared link points at — those stay open. Everything that costs the box something is closed: the
 * playground compiles Kotlin, `/bundle.zip` packs a catalog, the wasm tier is megabytes per fetch,
 * `/history/render` reads the git object store, and any render URL carrying override query params
 * (`?theme=`, `?w=`) re-renders on demand instead of serving the bake. A crawler walking the whole
 * grid with overrides would keep the render daemons permanently hot for no one's benefit.
 *
 * Wildcard (`*`) and end-anchor (`$`) patterns are used for the per-system lanes, because the
 * system id is the *first* path segment (`/compose-m3/compare`) and a literal prefix list would
 * have to be regenerated for every published catalog. They are an extension to the original
 * standard, honoured by every crawler that matters today (Google, Bing, Yandex, DuckDuckGo) and
 * ignored — matching nothing, so failing *open* rather than blocking the site — by a naive prefix
 * parser. That asymmetry is why the link-preview group below is written without them.
 */
internal object ServeSiteIndex {

  /**
   * Link-preview fetchers, as opposed to indexing crawlers: they fetch once, when someone shares a
   * URL, and want the page plus its `og:image`. They get their own permissive group rather than
   * being folded into `User-agent: *`, for two reasons. They must not inherit `Crawl-delay` (there
   * is nothing to pace — it is one fetch, and a delayed unfurl is a missing unfurl), and their
   * robots parsers are the simple prefix kind, so the wildcard rules in the general group would be
   * read as literal paths and silently match nothing. Writing their group as a short list of
   * literal prefixes means it says the same thing to a simple parser and a modern one.
   *
   * `Slack-ImgProxy` is listed alongside `Slackbot-LinkExpanding` because Slack fetches the page
   * and its image with two different agents; blocking the second yields a text-only card.
   *
   * **Only single-fetch preview agents belong here.** Robots groups are not additive — a crawler
   * obeys the most specific group that names it and ignores `User-agent: *` entirely — so listing a
   * general-purpose indexer here would exempt its whole fleet from the crawl delay and from every
   * dynamic-lane rule below. That is why `Googlebot` is deliberately absent even though Google is
   * one of the services whose previews this change is meant to fix: nothing in the general group
   * blocks a page or its `og:image`, so Google's fetchers get everything an unfurl needs *and* stay
   * bound by the render-lane rules. The same reasoning keeps `Applebot` and `Bingbot` out.
   */
  private val PREVIEW_FETCHERS =
    listOf(
      "Slackbot-LinkExpanding",
      "Slackbot",
      "Slack-ImgProxy",
      "Twitterbot",
      "facebookexternalhit",
      "LinkedInBot",
      "Discordbot",
      "TelegramBot",
      "WhatsApp",
      "SkypeUriPreview",
      "Iframely",
    )

  /**
   * Lanes closed to *everyone*, link-preview fetchers included: a signed-in-only surface, an
   * expiring capability URL, or a lane that runs code. These are literal prefixes so they mean the
   * same thing to every parser.
   */
  private val CLOSED_TO_ALL =
    listOf(
      // Token-gated admin API — 404s without the header anyway, but naming it keeps it off the
      // crawl budget and out of any "URLs we found" report.
      "/admin/",
      // The GitHub OAuth dance. A crawler following it burns a state nonce and lands on an error.
      "/auth/",
      // Uploaded documents behind expiring capability URLs. Not secret, but not ours to publish,
      // and every one of them is dead by the time a crawler would revisit.
      "/docs",
      "/d/",
      // Compiles Kotlin on demand. The single most expensive thing this server can be asked to do.
      "/playground",
    )

  /** Lanes closed to indexing crawlers: work, machine formats, or operational endpoints. */
  private val CLOSED_TO_CRAWLERS =
    listOf(
      // Machine lanes. Nothing here renders as a page, and `/api/previews` is the whole grid.
      "/api/",
      "/*/api/",
      // Operational. `/status` covers `/status.json`; a crawler polling readiness is pure noise.
      "/status",
      "/healthz",
      "/readyz",
      "/version",
      // The parity dashboard recomputes per request against the live catalog.
      "/parity",
      "/*/parity",
      // Comparison surfaces: the reference/RC lanes decode and diff images, and the compare page
      // itself drops to a dynamic render while its RC lane is still pending.
      "/compare",
      "/*/compare",
      "/reference/",
      "/*/reference/",
      "/rc-compare/",
      "/*/rc-compare/",
      // Reads an old render out of the local git object store, per request.
      "/history/render/",
      "/*/history/render/",
      // Multi-megabyte downloads. A crawler pulling every catalog's zip would saturate the box.
      "/bundle.zip",
      "/*/bundle.zip",
      "/bundle/",
      "/*/bundle/",
      "/wasm/",
      "/rc-player-wasm/",
      "/rc-player/",
      "/doc-player/",
      // Storybook-compatibility surface: a JSON index and a chrome-less render frame. Both exist
      // for screenshot tools that are pointed at them deliberately, and neither is a page.
      "/index.json",
      "/*/index.json",
      "/iframe.html",
      "/*/iframe.html",
      // Non-PNG render products. Every one of these is *made* on request — the figma-svg export,
      // the slot / accessibility / annotation inspection layers, the captured Remote Compose
      // document — so unlike `<id>.png` there is no bake to serve and each goes through a
      // daemon-backed producer and the shared render semaphore. The viewer assets name them, so a
      // crawler that walks the page finds them; without these rules it would execute exactly the
      // work the rest of this policy exists to suppress.
      "/*.svg$",
      "/*.slots$",
      "/*.a11y$",
      "/*.annotations$",
      "/*.rc$",
    )

  /**
   * Render URLs that carry a query string. This is the rule that actually protects the daemons:
   * `/…/render/<id>.png` with no query serves baked bytes off disk, while the same path with
   * `?themeProvider=`, `?fontScale=`, `?device=` or any other override is a live re-render. The
   * grid links the override forms, so a crawler that followed them would render the entire catalog
   * in every theme.
   *
   * Scoped to the **render lane**, not to every query string on the server. A blanket "disallow
   * anything with a query" also closed the browser-facing pages, and a page URL with state in it is
   * precisely what people share: `…/compose-m3/?tab=components&theme=…` is the link someone pastes
   * into a chat after picking a theme. Since the general group is the one Googlebot obeys (see
   * [PREVIEW_FETCHERS]), blanket-closing query strings would have blocked Google from reading the
   * Open Graph block on exactly the links this change exists to make unfurl.
   */
  private val NO_QUERY_ON_RENDER = listOf("/render/*?", "/*/render/*?")

  /**
   * `robots.txt` for this server.
   *
   * A non-public (token-gated) server disallows everything: its URLs only work with a token the
   * crawler doesn't have, so every crawl is a 404 and every indexed URL is a dead one. Fail-closed
   * is also the right default for a box someone put a token in front of deliberately.
   *
   * [sitemapUrl] is advertised only when there is a sitemap to point at — an absolute URL, which
   * the standard requires even though every other line here is a path.
   */
  fun robotsTxt(isPublic: Boolean, sitemapUrl: String?): String = buildString {
    appendLine("# compose-preview — https://github.com/yschimke/compose-ai-tools")
    if (!isPublic) {
      appendLine("# Token-gated server: every URL needs a token this crawler does not have.")
      appendLine()
      appendLine("User-agent: *")
      appendLine("Disallow: /")
      return@buildString
    }
    appendLine("# Catalog landings, preview viewers and their baked PNGs are open to crawl.")
    appendLine("# Render-on-demand, code-running and bulk-download lanes are not.")
    appendLine()

    appendLine("User-agent: *")
    CLOSED_TO_ALL.forEach { appendLine("Disallow: $it") }
    CLOSED_TO_CRAWLERS.forEach { appendLine("Disallow: $it") }
    appendLine("# Override params re-render a preview; the bare path serves the bake.")
    NO_QUERY_ON_RENDER.forEach { appendLine("Disallow: $it") }
    appendLine("Crawl-delay: 10")
    appendLine()

    appendLine("# Link unfurlers: one fetch per shared URL, and they need the og:image too.")
    PREVIEW_FETCHERS.forEach { appendLine("User-agent: $it") }
    CLOSED_TO_ALL.forEach { appendLine("Disallow: $it") }
    sitemapUrl?.let {
      appendLine()
      appendLine("Sitemap: $it")
    }
  }

  /** One crawlable catalog: its system id, its preview ids, and when it was generated. */
  data class CatalogEntry(
    val system: String,
    val previewIds: List<String>,
    /** The catalog's `generatedAt` provenance, already ISO-8601. Null when it declared none. */
    val lastModified: String?,
  )

  /**
   * The sitemap's URL ceiling. The standard caps a single sitemap file at 50,000 URLs; this server
   * publishes roughly `catalogs × previews`, which today is ~1,700 and has room to grow by an order
   * of magnitude before it needs a sitemap index. Truncating is still better than emitting an
   * oversized file a crawler rejects wholesale — and [sitemapXml] says so in a comment when it
   * happens, rather than silently dropping the tail.
   */
  private const val MAX_URLS = 50_000

  /**
   * `sitemap.xml` for the published catalogs.
   *
   * Only the pages worth landing on: the front door, each catalog landing, and each preview's
   * viewer. Not the images — a crawler finds those through the page's `og:image`, and listing them
   * would triple the file to say nothing new.
   *
   * `<lastmod>` comes from the catalog's own `generatedAt` provenance rather than from the server's
   * refresh clock, and that distinction is the point of publishing a sitemap at all: the refresh
   * poller touches every catalog on a timer whether or not the bytes changed, so a
   * refresh-timestamped sitemap would tell crawlers everything changed every hour and teach them to
   * ignore the field. `generatedAt` moves only when the delivery branch actually regenerated.
   *
   * `changefreq` / `priority` are deliberately absent — Google has said for years that it ignores
   * both, and a field nobody reads is a field that goes stale without anyone noticing.
   *
   * On a **top-level site** ([ServeSites]) the one catalog it publishes is mounted at the root, so
   * [rootedSystem] names it and its URLs drop the `/<system>` segment. There is then no front door
   * to list either — the catalog landing *is* `/`, and emitting both would publish two URLs for one
   * page, which is the duplicate-content problem a sitemap exists to avoid.
   */
  fun sitemapXml(
    origin: String,
    entries: List<CatalogEntry>,
    rootedSystem: String? = null,
  ): String {
    val urls = buildList {
      // The front door changes whenever any catalog it indexes does. A rooted site has none; its
      // landing is emitted below as `<origin>/`.
      if (rootedSystem == null) {
        add(origin + "/" to entries.mapNotNull { it.lastModified }.maxOrNull())
      }
      for (entry in entries) {
        val base =
          if (entry.system == rootedSystem) origin
          else origin + "/" + WebEscaping.urlEncodeSegment(entry.system)
        add("$base/" to entry.lastModified)
        for (id in entry.previewIds) {
          add("$base/p/${WebEscaping.urlEncodeSegment(id)}" to entry.lastModified)
        }
      }
    }
    return buildString {
      appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
      if (urls.size > MAX_URLS) {
        appendLine("<!-- truncated to $MAX_URLS of ${urls.size} URLs (sitemap size limit) -->")
      }
      appendLine("""<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">""")
      for ((loc, lastmod) in urls.take(MAX_URLS)) {
        appendLine("  <url>")
        appendLine("    <loc>${WebEscaping.htmlEscape(loc)}</loc>")
        lastmod?.let(::w3cDateTime)?.let {
          appendLine("    <lastmod>${WebEscaping.htmlEscape(it)}</lastmod>")
        }
        appendLine("  </url>")
      }
      appendLine("</urlset>")
    }
  }

  /**
   * [raw] if it is already a W3C datetime (the profile of ISO-8601 the sitemap standard names),
   * else null so the entry simply carries no `<lastmod>`.
   *
   * Validated rather than trusted because the value is read straight out of a catalog's
   * `catalog.json` — a third-party delivery branch this server fetched — and a malformed date makes
   * a crawler discard the *whole* sitemap, not just the offending entry. Omitting one catalog's
   * timestamp is a far cheaper failure than losing every URL in the file.
   */
  private fun w3cDateTime(raw: String): String? {
    // Shape first: `java.time` accepts spellings the sitemap profile does not (a local date-time
    // with no offset, say), so the regex fixes which forms are allowed at all.
    if (!W3C_DATETIME.matches(raw)) return null
    // Then meaning. The regex alone passes `2026-13-40T25:99:99+99:99` — digit-shaped and entirely
    // impossible — which would land in `<lastmod>` and defeat the whole point of validating: a date
    // a crawler rejects invalidates the document, exactly the failure the fail-safe exists to
    // avoid.
    val parsed = runCatching {
      if (raw.length == 10) java.time.LocalDate.parse(raw) else java.time.OffsetDateTime.parse(raw)
    }
      .isSuccess
    return raw.takeIf { parsed }
  }

  /**
   * `2026-07-17`, `2026-07-17T12:34Z`, `2026-07-17T12:34:56.789+02:00` — the shapes sitemaps take.
   * Shape only; [w3cDateTime] additionally requires the value to be a real instant.
   */
  private val W3C_DATETIME =
    Regex("""^\d{4}-\d{2}-\d{2}(T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2}))?$""")
}
