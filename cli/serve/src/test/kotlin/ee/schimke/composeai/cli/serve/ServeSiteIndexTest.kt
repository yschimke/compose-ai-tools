package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit coverage for the crawler-facing pair. The end-to-end wiring (routes, gating, which catalogs
 * reach the generator) is in [ServeHttpRoutingTest]; this file pins the generation rules that are
 * easy to get subtly wrong and impossible to notice afterwards — a crawler never reports back.
 */
class ServeSiteIndexTest {

  @Test
  fun `a token-gated server disallows everything`() {
    val robots = ServeSiteIndex.robotsTxt(isPublic = false, sitemapUrl = null)
    assertTrue(robots.contains("User-agent: *"), robots)
    assertTrue(robots.contains("Disallow: /"), robots)
    // No permissive group may survive: every URL on such a host needs a token, so an unfurler
    // allowed through would only ever get a 404 and cache it.
    assertFalse(
      robots.contains("Slackbot"),
      "no preview-fetcher exemption on a private host: $robots",
    )
    assertFalse(robots.contains("Sitemap:"), "nothing to advertise on a private host: $robots")
  }

  /**
   * The rule that actually protects the render daemons. `/…/render/<id>.png` is a baked file, but
   * the same path with an override query re-renders — and the grid links the override forms, so a
   * crawler that followed them would render every preview in every theme.
   */
  /**
   * The non-PNG render products have no bake to serve — each is made through a daemon-backed
   * producer and the shared render semaphore — and the viewer assets name them, so a crawler that
   * walks a page will find them. Blocking only `.svg` and `.rc` left three that execute exactly the
   * work this policy exists to suppress.
   */
  @Test
  fun `every made-on-request render product is closed to crawlers`() {
    val robots = ServeSiteIndex.robotsTxt(isPublic = true, sitemapUrl = null)
    for (suffix in listOf("svg", "slots", "a11y", "annotations", "rc")) {
      assertTrue(robots.contains("Disallow: /*.$suffix$"), "closes .$suffix: $robots")
    }
    // The baked PNG is the one render URL that stays open — it is the og:image.
    assertFalse(robots.contains("Disallow: /*.png"), "the bake stays crawlable: $robots")
  }

  @Test
  fun `query strings are closed on the render lane only`() {
    val robots = ServeSiteIndex.robotsTxt(isPublic = true, sitemapUrl = null)
    assertTrue(robots.contains("Disallow: /*/render/*?"), robots)
    assertTrue(robots.contains("Disallow: /render/*?"), robots)
    // The bare render path is the og:image an unfurler fetches — it must stay open.
    assertFalse(robots.contains("Disallow: /*/render/\n"), "the baked PNG stays fetchable: $robots")
    assertFalse(robots.contains("Disallow: /*/p/"), "the viewer page stays crawlable: $robots")
    // And the rule must NOT be the blanket form. Googlebot obeys this group, so closing every query
    // string would block it from reading the Open Graph block on a link someone shared after
    // picking a tab or a theme — which is most of the links people actually share.
    assertFalse(
      robots.lines().any { it.trim() == "Disallow: /*?" },
      "a page URL carrying state must stay fetchable: $robots",
    )
  }

  /**
   * The link-preview group must not contain a wildcard rule. Those parsers do simple prefix
   * matching, so a wildcard rule would either be read as a literal path (harmless) or, for the
   * variant beginning `Disallow: /`, as "block the entire site" — which is the exact bug this whole
   * change exists to fix, reintroduced by the file meant to fix it.
   */
  @Test
  fun `the link-preview group carries no wildcard rules`() {
    val robots = ServeSiteIndex.robotsTxt(isPublic = true, sitemapUrl = null)
    val previewGroup = robots.substringAfter("User-agent: Slackbot-LinkExpanding")
    val rules = previewGroup.lines().filter { it.startsWith("Disallow:") }
    assertTrue(rules.isNotEmpty(), "the group still closes the private lanes: $robots")
    for (rule in rules) {
      assertFalse(rule.contains("*"), "wildcard in the preview-fetcher group: $rule")
      assertFalse(rule.contains("$"), "end-anchor in the preview-fetcher group: $rule")
      assertTrue(
        rule != "Disallow: /",
        "the preview group must never close the whole site: $robots",
      )
    }
  }

  /**
   * A general-purpose indexer must never be named in the permissive group. Robots groups are
   * winner-takes-all: naming `Googlebot` there would exempt Google's entire crawl fleet from the
   * crawl delay and from every dynamic-lane rule, which is the opposite of what the file is for.
   * Google's previews don't need the exemption — the general group already leaves pages and their
   * `og:image` open.
   */
  @Test
  fun `general-purpose crawlers stay in the restricted group`() {
    val robots = ServeSiteIndex.robotsTxt(isPublic = true, sitemapUrl = null)
    val previewGroup = robots.substringAfter("User-agent: Slackbot-LinkExpanding")
    for (indexer in listOf("Googlebot", "GoogleOther", "Applebot", "Bingbot", "DuckDuckBot")) {
      assertFalse(
        previewGroup.contains(indexer),
        "$indexer must not skip the crawler rules: $robots",
      )
    }
    // …and the restricted group is the one that actually carries the pacing.
    assertTrue(robots.substringBefore("User-agent: Slackbot").contains("Crawl-delay:"), robots)
  }

  @Test
  fun `sitemap stamps each URL with its catalog's generation date`() {
    val xml =
      ServeSiteIndex.sitemapXml(
        "https://preview.example",
        listOf(
          ServeSiteIndex.CatalogEntry(
            "compose-m3",
            listOf("Button", "Card"),
            "2026-07-17T12:34:56Z",
          )
        ),
      )
    assertTrue(xml.contains("<loc>https://preview.example/compose-m3/</loc>"), xml)
    assertTrue(xml.contains("<loc>https://preview.example/compose-m3/p/Button</loc>"), xml)
    assertTrue(xml.contains("<loc>https://preview.example/compose-m3/p/Card</loc>"), xml)
    // Front door + the catalog landing + one per preview, all carrying the catalog's own date.
    assertEquals(4, Regex("<lastmod>2026-07-17T12:34:56Z</lastmod>").findAll(xml).count(), xml)
    // The front door carries the newest catalog date, so it re-crawls when anything under it moves.
    assertTrue(
      xml.substringAfter("<loc>https://preview.example/</loc>").startsWith("\n    <lastmod>"),
      "front door is dated: $xml",
    )
  }

  /**
   * A malformed date must cost that entry its `<lastmod>`, not cost the file every URL in it —
   * `generatedAt` is read out of a third-party delivery branch's `catalog.json`, and a crawler that
   * fails to parse a sitemap discards the whole document.
   */
  @Test
  fun `an unparseable generation date drops only the timestamp`() {
    val xml =
      ServeSiteIndex.sitemapXml(
        "https://preview.example",
        listOf(ServeSiteIndex.CatalogEntry("reply", listOf("Inbox"), "last tuesday")),
      )
    assertTrue(xml.contains("<loc>https://preview.example/reply/p/Inbox</loc>"), xml)
    assertFalse(xml.contains("<lastmod>"), "no timestamp rather than a bad one: $xml")
    assertFalse(xml.contains("last tuesday"), xml)
  }

  /**
   * Shape-checking alone lets `2026-13-40T25:99:99+99:99` through — digit-shaped and impossible. A
   * date a crawler rejects invalidates the whole document, which is the exact failure the
   * validation exists to prevent, so the value has to be a real instant and not merely look like
   * one.
   */
  @Test
  fun `a well-shaped but impossible date is rejected too`() {
    fun lastmodOf(raw: String): String? =
      ServeSiteIndex.sitemapXml(
          "https://preview.example",
          listOf(ServeSiteIndex.CatalogEntry("m3", emptyList(), raw)),
        )
        .let { Regex("<lastmod>([^<]+)</lastmod>").find(it)?.groupValues?.get(1) }

    assertEquals(null, lastmodOf("2026-13-40T25:99:99+99:99"), "month 13 / day 40 / hour 25")
    assertEquals(null, lastmodOf("2026-02-30"), "February never has 30 days")
    assertEquals(null, lastmodOf("2026-07-17T12:34:56"), "no offset is not the sitemap profile")
    // The real shapes still pass, including a date-only value and minute precision.
    assertEquals("2026-07-17", lastmodOf("2026-07-17"))
    assertEquals("2026-07-17T12:34Z", lastmodOf("2026-07-17T12:34Z"))
    assertEquals("2026-07-17T12:34:56.789+02:00", lastmodOf("2026-07-17T12:34:56.789+02:00"))
  }

  /** A preview id can carry `&`, `?` and spaces; both the URL and the XML have to survive it. */
  @Test
  fun `ids that need encoding are escaped for both URL and XML`() {
    val xml =
      ServeSiteIndex.sitemapXml(
        "https://preview.example",
        listOf(ServeSiteIndex.CatalogEntry("m3", listOf("Chip & Co?x"), null)),
      )
    assertTrue(xml.contains("/p/Chip%20%26%20Co%3Fx"), "id is percent-encoded: $xml")
    assertFalse(xml.contains("Chip & Co"), "no raw ampersand in the XML: $xml")
  }

  @Test
  fun `an empty catalog set still yields a valid sitemap with just the front door`() {
    val xml = ServeSiteIndex.sitemapXml("https://preview.example", emptyList())
    assertTrue(xml.contains("<loc>https://preview.example/</loc>"), xml)
    assertEquals(1, Regex("<loc>").findAll(xml).count(), xml)
    assertTrue(xml.trimEnd().endsWith("</urlset>"), xml)
  }
}
