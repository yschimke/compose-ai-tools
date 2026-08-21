package ee.schimke.composeai.cli.serve

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * End-to-end check that a catalog page's footer offers its **Changelog** — the catalog change feed
 * ([ServeCatalogChangeFeed]) the server already serves at `/<system>/feed.xml`, which until now no
 * page linked, so the only way to reach it was to know the URL.
 *
 * A route test rather than a fixture assertion, for the reason written up on
 * [ServeViewerIssueReportRouteTest]: `ServeWebFixtureTest` renders the goldens by handing
 * [ServeWeb.landingPage] arguments of its own, so a golden showing the entry proves only that the
 * renderer draws it. What has to hold is that the *handler* passes a href, that it points at a feed
 * this server would actually answer, and that a session with no feed behind it gets no link.
 */
class ServeChangelogFooterRouteTest {

  private fun png(): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it) }
      .toByteArray()

  private fun bundle(label: String): ServeBundleHost {
    val dir =
      Files.createTempDirectory("changelog-footer-$label").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    File(dir, "previews").apply { mkdirs() }
    File(dir, "previews/button-filled.png").writeBytes(png())
    return ServeBundleHost(
      dir,
      label = label,
      title = label,
      declaredBaked = listOf("button-filled"),
    )
  }

  private val cache = Files.createTempDirectory("changelog-footer-feed").toFile()
  private val registry = ServeSessionRegistry(open = { null })
  private val feed =
    ServeCatalogChangeFeed(
      // Only `demo` is a published catalog; `local` is the module session beside it.
      entries = {
        listOf(CatalogLoadTracker.Config("demo", false, "example/catalog", "design-artifacts/demo"))
      },
      cacheRoot = cache,
      idleTimeoutMillis = 60_000,
      pollIntervalMillis = 60_000,
      source = CatalogFeedSource { _, _ -> null },
      onLog = {},
      startScheduler = false,
    )
  private val server =
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused",
        sessions =
          registry.also {
            it.register("demo", host = bundle("demo"), pinned = true)
            it.register("local", host = bundle("local"), pinned = true)
          },
        defaultSessionId = "demo",
        isPublic = true,
        catalogSessions = listOf("demo"),
        catalogFeed = feed,
      )
      .also { it.start() }
  private val client = OkHttpClient()

  @AfterTest
  fun close() {
    server.stop()
    feed.close()
    registry.close()
    cache.deleteRecursively()
  }

  @Test
  fun `a catalog page footer links the change feed and declares it for readers`() {
    for (path in listOf("/demo/", "/demo/p/button-filled")) {
      val (status, body) = get(path)
      assertEquals(200, status, path)
      val footer = body.substringAfter("<footer class=\"cp-site-footer\">", "")
      assertTrue(footer.contains("href=\"/demo/feed.xml\""), "$path footer links the feed")
      assertTrue(footer.contains(">$CHANGELOG</a>"), "$path names it Changelog: $footer")
      assertTrue(
        body
          .substringBefore("</head>")
          .contains(
            "<link rel=\"alternate\" type=\"application/rss+xml\" title=\"Catalog changes\"" +
              " href=\"/demo/feed.xml\">"
          ),
        "$path declares the feed for a reader's subscribe affordance",
      )
    }
    // The link is not decorative: the href it offers is a document this server answers.
    val feedResponse = get("/demo/feed.xml")
    assertEquals(200, feedResponse.first)
    assertTrue(feedResponse.second.contains("<rss"), feedResponse.second)
  }

  @Test
  fun `a session with no published history is offered no changelog`() {
    val (status, body) = get("/local/")
    assertEquals(200, status)
    assertFalse(
      body.contains("feed.xml"),
      "a local module has no delivery branch to have a history",
    )
    assertFalse(body.contains(">$CHANGELOG</a>"), body.substringAfter("cp-site-footer-links"))
  }

  private fun get(path: String): Pair<Int, String> {
    val url = "http://127.0.0.1:${server.port}$path"
    client.newCall(Request.Builder().url(url).build()).execute().use { r ->
      return r.code to r.body.string()
    }
  }

  private companion object {
    const val CHANGELOG = " Changelog"
  }
}
