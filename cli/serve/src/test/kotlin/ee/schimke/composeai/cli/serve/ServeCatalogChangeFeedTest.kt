package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
  fun `a score minted by another kernel is not reported as a moved score`() {
    // The scorer's pixel path changed once, deliberately, and every published number moved with it.
    // The feed's job is to say what changed *in the catalog* between two revisions, so comparing
    // across that boundary would announce every reference in the catalog as having shifted in the
    // one batch where none of them had — a whole page of findings with no design behind any of
    // them.
    fun snapshot(percent: Double, version: Int, sha: String) =
      CatalogSnapshot.parse(
        catalogJson =
          """{"title":"Demo","components":[
            {"componentId":"Button","images":[{"path":"images/button/default.png"}]}
          ]}""",
        referencesJson =
          """{"references":[{"id":"button-spec","previewId":"button__default","label":"Button spec",
            "raster":{"path":"references/button.png","sha256":"$sha"},
            "match":{"percent":$percent,"scoreVersion":$version}}]}""",
        blobs = mapOf("images/button/default.png" to "2".repeat(40)),
      )

    // Same reference, same raster, same everything a reader can see — only the kernel moved.
    val rebaselined =
      CatalogFeedDiff.between(
        oldRevision,
        snapshot(percent = 80.0, version = 1, sha = "spec"),
        newRevision,
        snapshot(percent = 92.5, version = 2, sha = "spec"),
      )
    assertEquals(
      emptyList(),
      rebaselined.references,
      "no design changed: ${rebaselined.references}",
    )

    // A score that ARRIVES is not a rival kernel. The publish-time scorer is optional — no
    // Playwright, no Chromium, an undecodable pair ⇒ no `match` at all — so a score appearing or
    // going away is an ordinary catalog change and was reported as one long before versions
    // existed. Reading the absent side's null version as a mismatch would silence exactly that.
    fun unscored(sha: String) =
      CatalogSnapshot.parse(
        catalogJson =
          """{"title":"Demo","components":[
            {"componentId":"Button","images":[{"path":"images/button/default.png"}]}
          ]}""",
        referencesJson =
          """{"references":[{"id":"button-spec","previewId":"button__default","label":"Button spec",
            "raster":{"path":"references/button.png","sha256":"$sha"}}]}""",
        blobs = mapOf("images/button/default.png" to "2".repeat(40)),
      )
    val appeared =
      CatalogFeedDiff.between(
        oldRevision,
        unscored("spec"),
        newRevision,
        snapshot(percent = 92.5, version = 2, sha = "spec"),
      )
    assertEquals(92.5, appeared.references.single().afterMatch, "an arriving score is reported")
    val vanished =
      CatalogFeedDiff.between(
        oldRevision,
        snapshot(percent = 92.5, version = 2, sha = "spec"),
        newRevision,
        unscored("spec"),
      )
    assertEquals(92.5, vanished.references.single().beforeMatch, "a vanishing score is reported")

    // …and within one kernel the same move is exactly what the feed is for.
    val moved =
      CatalogFeedDiff.between(
        oldRevision,
        snapshot(percent = 80.0, version = 2, sha = "spec"),
        newRevision,
        snapshot(percent = 92.5, version = 2, sha = "spec"),
      )
    assertEquals(80.0, moved.references.single().beforeMatch)
    assertEquals(92.5, moved.references.single().afterMatch)
  }

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
        "token=s3cret",
      )
    assertTrue(xml.contains("Demo app catalog changes"))
    assertTrue(xml.contains("at=${oldRevision.commit}"))
    assertTrue(xml.contains("at=${newRevision.commit}"))
    assertTrue(xml.contains("80.00% → 92.50%"))
    assertTrue(xml.contains("+12.50 pp"))
    assertTrue(xml.contains("Before design reference"))
    assertTrue(xml.contains("After design reference"))
    assertTrue(xml.contains("feed.xml?token=s3cret"))
    assertTrue(xml.contains("token=s3cret"))
    assertTrue(
      xml.indexOf("alt=&quot;After&quot;") < xml.indexOf("alt=&quot;Before&quot;"),
      "the current render leads; a reader showing one image must not show the superseded one",
    )
  }

  @Test
  fun `one component changing every variant collapses to a representative plus links`() {
    val ids =
      listOf("default", "ambient", "loading").flatMap { state ->
        listOf("192dp", "240dp").map { size -> "media-playerscreen__ideal__${state}__$size" }
      }
    val batch =
      CatalogFeedBatch(
        before = oldRevision,
        after = newRevision,
        previews =
          ids.mapIndexed { index, id ->
            CatalogPreviewChange(
              CatalogPreviewChangeKind.CHANGED,
              id,
              "Media/PlayerScreen",
              "1".repeat(40),
              "2".repeat(40),
              order = index,
            )
          },
        references = emptyList(),
      )
    val xml =
      CatalogFeedXml.render(
        "demo",
        "https://preview.example/demo",
        CatalogFeedHistory("Demo app", listOf(newRevision, oldRevision), listOf(batch)),
      )

    assertEquals(1, Regex("&lt;li&gt;").findAll(xml).count(), "one entry for the whole component")
    assertTrue(xml.contains("6 previews visually changed"))
    assertEquals(
      2,
      Regex("&lt;img alt=").findAll(xml).count(),
      "only the representative variant carries images",
    )
    assertTrue(xml.contains("render/${ids.first()}.png?at=${newRevision.commit}"))
    assertFalse(xml.contains("render/${ids[1]}.png"), "the other variants are links, not images")
    for (id in ids.drop(1)) assertTrue(xml.contains("/p/$id?at=${newRevision.commit}"), id)
    assertTrue(xml.contains("&gt;ambient__192dp&lt;/a&gt;"), "links drop the shared component head")
  }

  @Test
  fun `collapsed groups cap their variant links`() {
    val previews =
      (1..CatalogFeedXml.MAX_GROUP_LINKS + 5).map {
        CatalogPreviewChange(
          CatalogPreviewChangeKind.ADDED,
          "button__ideal__variant-%03d".format(it),
          "Button",
          afterBlob = "2".repeat(40),
          order = it,
        )
      }
    val xml =
      CatalogFeedXml.render(
        "demo",
        "https://preview.example/demo",
        CatalogFeedHistory(
          "Demo app",
          listOf(newRevision, oldRevision),
          listOf(CatalogFeedBatch(oldRevision, newRevision, previews, emptyList())),
        ),
      )

    // The representative is shown, CatalogFeedXml.MAX_GROUP_LINKS of the rest are linked, and the
    // tail is counted.
    assertTrue(xml.contains("${previews.size} previews added"))
    assertEquals(CatalogFeedXml.MAX_GROUP_LINKS + 1, Regex("&lt;a href=").findAll(xml).count())
    assertTrue(xml.contains(", and 4 more"))
  }

  @Test
  fun `a deleted group links the revision that still has the pixels`() {
    val ids = listOf("card__ideal__default", "card__ideal__pressed", "card__ideal__disabled")
    val xml =
      CatalogFeedXml.render(
        "demo",
        "https://preview.example/demo",
        CatalogFeedHistory(
          "Demo app",
          listOf(newRevision, oldRevision),
          listOf(
            CatalogFeedBatch(
              oldRevision,
              newRevision,
              ids.mapIndexed { index, id ->
                CatalogPreviewChange(
                  CatalogPreviewChangeKind.DELETED,
                  id,
                  "Card",
                  beforeBlob = "1".repeat(40),
                  order = index,
                )
              },
              emptyList(),
            )
          ),
        ),
      )

    // Pinning a deleted id to the publication that removed it lands on "not published in this
    // revision" — the pinned viewer takes that revision as authoritative.
    for (id in ids) assertTrue(xml.contains("/p/$id?at=${oldRevision.commit}"), id)
    assertFalse(xml.contains("/p/${ids[1]}?at=${newRevision.commit}"))
  }

  @Test
  fun `references sharing a label across components stay separate entries`() {
    val references =
      listOf("checkboxbutton__ideal__default", "radiobutton__ideal__default").mapIndexed { i, id ->
        CatalogReferenceChange(
          id = "$id-spec",
          // A label a producer repeats across components: presentation text, not identity.
          label = "Figma",
          previewId = id,
          specChanged = true,
          beforeMatch = null,
          afterMatch = 80.0,
          order = i,
          beforePresent = false,
        )
      }
    val xml =
      CatalogFeedXml.render(
        "demo",
        "https://preview.example/demo",
        CatalogFeedHistory(
          "Demo app",
          listOf(newRevision, oldRevision),
          listOf(CatalogFeedBatch(oldRevision, newRevision, emptyList(), references)),
        ),
      )

    assertEquals(
      2,
      Regex("&lt;li&gt;").findAll(xml).count(),
      "one entry per component, not per label",
    )
    for (change in references) {
      assertTrue(xml.contains("reference/${change.id}.png"), "${change.previewId} keeps its image")
    }
  }

  @Test
  fun `a removed reference links its preview instead of a comparison page it has left`() {
    val references =
      listOf("default", "disabled").mapIndexed { i, variant ->
        CatalogReferenceChange(
          id = "checkboxbutton-$variant-spec",
          label = "CheckboxButton — figma",
          previewId = "checkboxbutton__ideal__$variant",
          specChanged = true,
          beforeMatch = 80.0,
          afterMatch = null,
          order = i,
          afterPresent = false,
        )
      }
    val xml =
      CatalogFeedXml.render(
        "demo",
        "https://preview.example/demo",
        CatalogFeedHistory(
          "Demo app",
          listOf(newRevision, oldRevision),
          listOf(CatalogFeedBatch(oldRevision, newRevision, emptyList(), references)),
        ),
      )

    // `/compare/…` resolves the reference from the catalog on disk, so a removed one has no page to
    // pin; the preview at the before revision is what still answers.
    assertFalse(xml.contains("/compare/"), "no link into a page the reference has left")
    assertTrue(
      xml.contains("/p/checkboxbutton__ideal__disabled?at=${oldRevision.commit}"),
      xml,
    )
  }

  @Test
  fun `references collapse per label and link the rest with their scores`() {
    val references =
      listOf("default", "disabled", "split").map { variant ->
        CatalogReferenceChange(
          "checkboxbutton-$variant-spec",
          "CheckboxButton — figma",
          "checkboxbutton__ideal__$variant",
          specChanged = true,
          beforeMatch = null,
          afterMatch = 70.0,
          beforePresent = false,
          afterPresent = true,
        )
      }
    val xml =
      CatalogFeedXml.render(
        "demo",
        "https://preview.example/demo",
        CatalogFeedHistory(
          "Demo app",
          listOf(newRevision, oldRevision),
          listOf(CatalogFeedBatch(oldRevision, newRevision, emptyList(), references)),
        ),
      )

    assertEquals(1, Regex("&lt;li&gt;").findAll(xml).count())
    assertTrue(xml.contains("3 references, spec changed"))
    assertEquals(
      1,
      Regex("&lt;img alt=").findAll(xml).count(),
      "only the representative reference carries an image",
    )
    assertTrue(
      xml.contains(
        "/compare/checkboxbutton__ideal__disabled?reference=checkboxbutton-disabled-spec"
      )
    )
    assertTrue(xml.contains("(n/a → 70.00%)"), "each linked variant keeps its own score")
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
          CatalogFeedSource { _, _ ->
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
  fun `active requests renew the lease without triggering another fetch`() {
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
        source = CatalogFeedSource { _, _ -> reads.incrementAndGet().let { history } },
        onLog = {},
        startScheduler = false,
      )
    try {
      service.request("demo", "https://preview.example/demo")
      await { reads.get() == 1 }
      clock += 50
      repeat(20) { service.request("demo", "https://preview.example/demo") }
      Thread.sleep(30)
      assertEquals(1, reads.get())

      service.tick()
      await { reads.get() == 2 }
    } finally {
      service.close()
    }
  }

  @Test
  fun `feed addresses are bounded and expired addresses are evicted`() {
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
        source = CatalogFeedSource { _, _ -> reads.incrementAndGet().let { history } },
        onLog = {},
        startScheduler = false,
      )
    try {
      repeat(ServeCatalogChangeFeed.MAX_FEED_ADDRESSES) {
        service.request("demo", "https://$it.example/demo")
      }
      await { reads.get() == ServeCatalogChangeFeed.MAX_FEED_ADDRESSES }
      assertEquals(ServeCatalogChangeFeed.MAX_FEED_ADDRESSES, service.stateCount())
      service.request("demo", "https://overflow.example/demo")
      assertEquals(ServeCatalogChangeFeed.MAX_FEED_ADDRESSES, service.stateCount())
      assertTrue(
        service.isActive("demo", "https://overflow.example/demo"),
        "a full set of forged origins cannot lock out the next real feed origin",
      )

      clock += 101
      service.request("demo", "https://replacement.example/demo")
      assertEquals(ServeCatalogChangeFeed.MAX_FEED_ADDRESSES, service.stateCount())
      assertTrue(service.isActive("demo", "https://replacement.example/demo"))
    } finally {
      service.close()
    }
  }

  @Test
  fun `pixel and metadata changes are reported together`() {
    val before =
      CatalogSnapshot.parse(
        """{"components":[{"componentId":"Button","section":"Old","images":[{"path":"images/button.png"}]}]}""",
        null,
        mapOf("images/button.png" to "1".repeat(40)),
      )
    val after =
      CatalogSnapshot.parse(
        """{"components":[{"componentId":"Button","section":"New","images":[{"path":"images/button.png"}]}]}""",
        null,
        mapOf("images/button.png" to "2".repeat(40)),
      )

    val change = CatalogFeedDiff.between(oldRevision, before, newRevision, after).previews.single()
    assertEquals(CatalogPreviewChangeKind.VISUAL_AND_METADATA, change.kind)
  }

  @Test
  fun `reference identity changes count as spec changes`() {
    fun snapshot(label: String, previewId: String) =
      CatalogSnapshot.parse(
        """{"components":[]}""",
        """{"references":[{"id":"spec","label":"$label","previewId":"$previewId","raster":{"sha256":"same"},"match":{"percent":80.0}}]}""",
        emptyMap(),
      )

    val change =
      CatalogFeedDiff.between(
          oldRevision,
          snapshot("Old", "old-preview"),
          newRevision,
          snapshot("New", "new-preview"),
        )
        .references
        .single()
    assertTrue(change.specChanged)
  }

  @Test
  fun `partial fetch returns before materialising unchanged snapshots`() {
    val root = tempDir()
    val repo = File(root, "demo").apply { mkdirs() }
    File(repo, "HEAD").writeText("ref: refs/heads/main\n")
    val commands = mutableListOf<List<String>>()
    val head = "c".repeat(40)
    val source =
      GitCatalogFeedSource(root) { _, args ->
        commands += args
        when (args.first()) {
          "rev-parse" -> CatalogFeedGitResult(0, "$head\n", "")
          else -> CatalogFeedGitResult(0, "", "")
        }
      }

    assertEquals(null, source.read(config(), head))
    assertTrue(commands.any { "--filter=blob:none" in it })
    assertFalse(
      commands.any { it.first() == "log" || it.first() == "show" || it.first() == "ls-tree" }
    )
  }

  @Test
  fun `lazy blob fetch failures abort the refresh instead of publishing deletions`() {
    val root = tempDir()
    val repo =
      File(root, "demo").apply {
        mkdirs()
        File(this, "HEAD").writeText("ref: refs/heads/main\n")
      }
    val head = "c".repeat(40)
    val source =
      GitCatalogFeedSource(root) { _, args ->
        when (args.first()) {
          "rev-parse" -> CatalogFeedGitResult(0, "$head\n", "")
          "log" ->
            CatalogFeedGitResult(
              0,
              "$head\u001f2026-08-15T12:30:00Z\u001fregenerate catalog\n",
              "",
            )
          "show" -> CatalogFeedGitResult(128, "", "fatal: unable to access promisor remote")
          else -> CatalogFeedGitResult(0, "", "")
        }
      }

    assertFailsWith<IllegalStateException> { source.read(config(), null) }
    assertTrue(repo.isDirectory)
  }

  @Test
  fun `interrupting git terminates the child and restores interrupt status`() {
    val dir = tempDir()
    val result = AtomicReference<CatalogFeedGitResult>()
    val interruptRestored = AtomicReference(false)
    val worker = Thread {
      result.set(runCatalogFeedGit(dir, listOf("hash-object", "--stdin")))
      interruptRestored.set(Thread.currentThread().isInterrupted)
    }
    worker.start()
    Thread.sleep(100)
    worker.interrupt()
    worker.join(5_000)

    assertFalse(worker.isAlive)
    assertEquals(130, result.get().exitCode)
    assertTrue(interruptRestored.get())
  }

  @Test
  fun `stale cache schema does not reuse its saved head`() {
    val root = tempDir()
    val history = CatalogFeedHistory("Demo", listOf(newRevision, oldRevision), emptyList())
    val first =
      ServeCatalogChangeFeed(
        entries = { listOf(config()) },
        cacheRoot = root,
        idleTimeoutMillis = 100,
        pollIntervalMillis = 10_000,
        source = CatalogFeedSource { _, _ -> history },
        onLog = {},
        startScheduler = false,
      )
    first.request("demo", "https://preview.example/demo")
    val version = awaitFile(root, "version")
    first.close()
    version.writeText("old\n")

    val observedKnownHead = AtomicReference("unset")
    val second =
      ServeCatalogChangeFeed(
        entries = { listOf(config()) },
        cacheRoot = root,
        idleTimeoutMillis = 100,
        pollIntervalMillis = 10_000,
        source =
          CatalogFeedSource { _, knownHead ->
            observedKnownHead.set(knownHead ?: "<null>")
            history
          },
        onLog = {},
        startScheduler = false,
      )
    try {
      second.request("demo", "https://preview.example/demo")
      await { observedKnownHead.get() != "unset" }
      assertEquals("<null>", observedKnownHead.get())
    } finally {
      second.close()
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

  private fun awaitFile(root: File, name: String): File {
    repeat(100) {
      root
        .walkTopDown()
        .firstOrNull { it.isFile && it.name == name }
        ?.let {
          return it
        }
      Thread.sleep(10)
    }
    error("$name did not appear")
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
        CatalogFeedSource { _, _ ->
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
        token = "s3cret",
        sessions = registry,
        defaultSessionId = "demo",
        isPublic = false,
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
      val response = get("/demo/feed.xml?token=s3cret")
      assertEquals(200, response.first)
      assertTrue(response.second.startsWith("application/rss+xml"))
      body = response.third
      if (body.contains("<item>")) break
      Thread.sleep(10)
    }
    assertTrue(body.contains("<item>"), body)
    assertTrue(body.contains("token=s3cret"), body)
    assertTrue(body.contains("https://127.0.0.1:").not(), "forwarded scheme is not invented")
    assertEquals(404, get("/unknown/feed.xml?token=s3cret").first)

    var queryBody = ""
    for (attempt in 0 until 100) {
      queryBody = get("/feed.xml?session=demo&token=s3cret").third
      if (queryBody.contains("<item>")) break
      Thread.sleep(10)
    }
    assertTrue(queryBody.contains("session=demo"), queryBody)
    assertTrue(queryBody.contains("token=s3cret"), queryBody)
  }

  private fun get(path: String): Triple<Int, String, String> {
    val request = Request.Builder().url("http://127.0.0.1:${server.port}$path").build()
    client.newCall(request).execute().use {
      return Triple(it.code, it.header("Content-Type").orEmpty(), it.body.string())
    }
  }
}
