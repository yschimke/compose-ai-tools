package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request

class ServeCatalogChangeFeedTest {
  private val temporary = mutableListOf<File>()

  private fun tempDir(): File =
    Files.createTempDirectory("catalog-feed-test").toFile().also(temporary::add)

  @AfterTest
  fun cleanUp() {
    temporary.forEach { it.deleteRecursively() }
  }

  private val oldRevision =
    CatalogFeedRevision(
      commit = "a".repeat(40),
      date = "2026-08-14T10:00:00Z",
      subject = "chore(design-artifacts): regenerate demo catalog (2026-08-14, 1111111)",
      sourceSha = "1111111",
    )
  private val newRevision =
    CatalogFeedRevision(
      commit = "b".repeat(40),
      date = "2026-08-15T10:00:00Z",
      subject = "chore(design-artifacts): regenerate demo catalog (2026-08-15, 2222222)",
      sourceSha = "2222222",
    )

  @Test
  fun `snapshot diff reports additions deletions pixel changes metadata and figma effect`() {
    val before =
      CatalogSnapshot.parse(
        catalogJson =
          """{"title":"Demo","components":[
            {"componentId":"Old","images":[{"path":"images/old/default.png"}]},
            {"componentId":"Button","section":"Controls","images":[{"path":"images/button/default.png","theme":"light"}]},
            {"componentId":"Label","images":[{"path":"images/label/default.png"}]}
          ]}""",
        referencesJson =
          """{"references":[{"id":"button-spec","previewId":"button__default","label":"Button spec",
            "raster":{"path":"references/button.png","sha256":"old-spec"},
            "source":{"provider":"figma","revision":"4"},"match":{"percent":80.0}}]}""",
        blobs =
          mapOf(
            "images/old/default.png" to "1".repeat(40),
            "images/button/default.png" to "2".repeat(40),
            "images/label/default.png" to "3".repeat(40),
          ),
      )
    val after =
      CatalogSnapshot.parse(
        catalogJson =
          """{"title":"Demo","components":[
            {"componentId":"Button","section":"Controls","images":[{"path":"images/button/default.png","theme":"light"}]},
            {"componentId":"Label","section":"Typography","images":[{"path":"images/label/default.png"}]},
            {"componentId":"New","images":[{"path":"images/new/default.png"}]}
          ]}""",
        referencesJson =
          """{"references":[{"id":"button-spec","previewId":"button__default","label":"Button spec",
            "raster":{"path":"references/button.png","sha256":"new-spec"},
            "source":{"provider":"figma","revision":"5"},"match":{"percent":92.5}}]}""",
        blobs =
          mapOf(
            "images/button/default.png" to "4".repeat(40),
            "images/label/default.png" to "3".repeat(40),
            "images/new/default.png" to "5".repeat(40),
          ),
      )

    val batch = CatalogFeedDiff.between(oldRevision, before, newRevision, after)
    assertEquals(
      listOf(
        CatalogPreviewChangeKind.CHANGED,
        CatalogPreviewChangeKind.METADATA,
        CatalogPreviewChangeKind.ADDED,
        CatalogPreviewChangeKind.DELETED,
      ),
      batch.previews.map { it.kind },
      "changes retain current authored catalog order, then former order for removals",
    )
    val figma = batch.references.single()
    assertTrue(figma.specChanged)
    assertEquals(80.0, figma.beforeMatch)
    assertEquals(92.5, figma.afterMatch)
  }

  @Test
  fun `rss includes immutable before after images and figma score delta`() {
    val batch =
      CatalogFeedBatch(
        before = oldRevision,
        after = newRevision,
        previews =
          listOf(
            CatalogPreviewChange(
              CatalogPreviewChangeKind.CHANGED,
              "button__default",
              "Button",
              "1".repeat(40),
              "2".repeat(40),
            )
          ),
        references =
          listOf(
            CatalogReferenceChange("spec", "Button spec", "button__default", true, 80.0, 92.5)
          ),
      )
    val xml =
      CatalogFeedXml.render(
        "demo",
        "https://preview.example/demo",
        CatalogFeedHistory("Demo app", listOf(newRevision, oldRevision), listOf(batch)),
      )
    assertTrue(xml.contains("Demo app catalog changes"))
    assertTrue(xml.contains("at=${oldRevision.commit}"))
    assertTrue(xml.contains("at=${newRevision.commit}"))
    assertTrue(xml.contains("80.00% → 92.50%"))
    assertTrue(xml.contains("+12.50 pp"))
    assertTrue(xml.contains("Before design reference"))
    assertTrue(xml.contains("After design reference"))
    assertTrue(xml.indexOf("Before") < xml.indexOf("After"))
  }

  @Test
  fun `feed interest expires and a later request reactivates it`() {
    var clock = 1_000L
    val reads = AtomicInteger()
    val history = CatalogFeedHistory("Demo", listOf(newRevision, oldRevision), emptyList())
    val service =
      ServeCatalogChangeFeed(
        entries = { listOf(config()) },
        cacheRoot = tempDir(),
        idleTimeoutMillis = 100,
        pollIntervalMillis = 10_000,
        now = { clock },
        source =
          CatalogFeedSource {
            reads.incrementAndGet()
            history
          },
        onLog = {},
        startScheduler = false,
      )
    try {
      service.request("demo", "https://preview.example/demo")
      await { reads.get() == 1 }
      assertTrue(service.isActive("demo", "https://preview.example/demo"))

      clock += 101
      service.tick()
      Thread.sleep(30)
      assertEquals(1, reads.get(), "an expired feed does not poll")
      assertFalse(service.isActive("demo", "https://preview.example/demo"))

      service.request("demo", "https://preview.example/demo")
      // The cached head is current, but reactivation still performs the cheap fetch that
      // establishes
      // whether it needs to catch up.
      await { reads.get() == 2 }
      assertTrue(service.isActive("demo", "https://preview.example/demo"))
    } finally {
      service.close()
    }
  }

  @Test
  fun `git log and tree parsers preserve commit metadata and blobs`() {
    val revision =
      GitCatalogFeedSource.parseRevision(
        "${"c".repeat(40)}\u001f2026-08-15T12:30:00Z\u001f" +
          "chore(design-artifacts): regenerate demo catalog (2026-08-15, deadbee)"
      )
    assertNotNull(revision)
    assertEquals("deadbee", revision.sourceSha)
    assertEquals(
      mapOf("images/button/default.png" to "d".repeat(40)),
      GitCatalogFeedSource.parseTree("100644 blob ${"d".repeat(40)}\timages/button/default.png\n"),
    )
  }

  private fun config() =
    CatalogLoadTracker.Config(
      system = "demo",
      listed = false,
      repo = "example/catalog",
      branch = "design-artifacts/demo",
    )

  private fun await(condition: () -> Boolean) {
    repeat(100) {
      if (condition()) return
      Thread.sleep(10)
    }
    error("condition did not become true")
  }
}

class ServeCatalogChangeFeedRoutingTest {
  private val registry = ServeSessionRegistry(open = { null })
  private val cache = Files.createTempDirectory("catalog-feed-routing").toFile()
  private val feed =
    ServeCatalogChangeFeed(
      entries = {
        listOf(
          CatalogLoadTracker.Config(
            "demo",
            false,
            "example/catalog",
            "design-artifacts/demo",
          )
        )
      },
      cacheRoot = cache,
      idleTimeoutMillis = 60_000,
      pollIntervalMillis = 60_000,
      source =
        CatalogFeedSource {
          val old = CatalogFeedRevision("a".repeat(40), "2026-08-14T10:00:00Z", "old", null)
          val new = CatalogFeedRevision("b".repeat(40), "2026-08-15T10:00:00Z", "new", null)
          CatalogFeedHistory(
            "Demo",
            listOf(new, old),
            listOf(
              CatalogFeedBatch(
                old,
                new,
                listOf(CatalogPreviewChange(CatalogPreviewChangeKind.ADDED, "new", "New")),
                emptyList(),
              )
            ),
          )
        },
      onLog = {},
      startScheduler = false,
    )
  private val server =
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused",
        sessions = registry,
        defaultSessionId = "demo",
        isPublic = true,
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
  fun `catalog feed route returns rss and unknown catalog is absent`() {
    var body = ""
    for (attempt in 0 until 100) {
      val response = get("/demo/feed.xml")
      assertEquals(200, response.first)
      assertTrue(response.second.startsWith("application/rss+xml"))
      body = response.third
      if (body.contains("<item>")) break
      Thread.sleep(10)
    }
    assertTrue(body.contains("<item>"), body)
    assertTrue(body.contains("https://127.0.0.1:").not(), "forwarded scheme is not invented")
    assertEquals(404, get("/unknown/feed.xml").first)
  }

  private fun get(path: String): Triple<Int, String, String> {
    val request = Request.Builder().url("http://127.0.0.1:${server.port}$path").build()
    client.newCall(request).execute().use {
      return Triple(it.code, it.header("Content-Type").orEmpty(), it.body.string())
    }
  }
}
