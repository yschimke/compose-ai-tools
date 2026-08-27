package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the "open this preview in the playground" seed: which URL it reads, what it refuses, and
 * that it never lets a request choose what the host fetches.
 */
class PlaygroundSeedResolverTest {

  private val fetched = mutableListOf<String>()
  private val log = mutableListOf<String>()

  private val m3 =
    PlaygroundSeedResolver.Location(
      repo = "yschimke/compose-ai-tools",
      ref = "main",
      module = ":samples:design-catalog-compose-m3",
      sourceFile = "src/main/kotlin/buttons/FilledButton.kt",
    )

  private var now = 0L

  private fun resolver(
    locate: (String, String) -> PlaygroundSeedResolver.Location? = { _, _ -> m3 },
    body: (String) -> ByteArray? = { "@Preview @Composable fun P() {}".toByteArray() },
    maxBytes: Int = PlaygroundSeedResolver.DEFAULT_MAX_BYTES,
    maxEntries: Int = PlaygroundSeedResolver.DEFAULT_MAX_ENTRIES,
    ttlSeconds: Long = PlaygroundSeedResolver.DEFAULT_TTL_SECONDS,
  ) =
    PlaygroundSeedResolver(
      locate = locate,
      fetch = {
        fetched += it
        body(it)
      },
      maxBytes = maxBytes,
      maxEntries = maxEntries,
      ttlSeconds = ttlSeconds,
      clock = { now },
      onLog = { log += it },
    )

  @Test
  fun `a preview seeds from its own source file on the catalog's source ref`() {
    val seed = resolver().seed("compose-m3", "buttons.FilledButton")
    assertNotNull(seed)
    assertEquals("compose-m3", seed.catalog)
    assertEquals("buttons.FilledButton", seed.previewId)
    assertEquals("FilledButton.kt", seed.fileName)
    assertEquals("@Preview @Composable fun P() {}", seed.text)
    // The RAW url is what gets read…
    assertEquals(
      listOf(
        "https://raw.githubusercontent.com/yschimke/compose-ai-tools/main/" +
          "samples/design-catalog-compose-m3/src/main/kotlin/buttons/FilledButton.kt"
      ),
      fetched,
    )
    // …and the human-readable blob is what the note links to.
    assertEquals(
      "https://github.com/yschimke/compose-ai-tools/blob/main/" +
        "samples/design-catalog-compose-m3/src/main/kotlin/buttons/FilledButton.kt",
      seed.blobUrl,
    )
  }

  @Test
  fun `a preview this server cannot place is not fetched at all`() {
    // The whole safety property: a request names a system and a preview id, and if THIS server
    // can't resolve them to catalog metadata, no URL is formed and nothing leaves the box.
    val r = resolver(locate = { _, _ -> null })
    assertNull(r.seed("nope", "whatever"))
    assertEquals(emptyList(), fetched)
    assertTrue(log.any { "nope/whatever" in it }, log.toString())
  }

  @Test
  fun `a failed fetch is a missing seed, not a failure`() {
    val r = resolver(body = { null })
    assertNull(r.seed("compose-m3", "buttons.FilledButton"))
    assertTrue(log.any { "could not read" in it }, log.toString())
  }

  @Test
  fun `a throwing fetch is contained`() {
    val r = resolver(body = { throw java.io.IOException("connection reset") })
    assertNull(r.seed("compose-m3", "buttons.FilledButton"))
    assertTrue(log.any { "connection reset" in it }, log.toString())
  }

  @Test
  fun `an oversized file is refused rather than opened in the editor`() {
    val r = resolver(body = { ByteArray(2048) { 'x'.code.toByte() } }, maxBytes = 1024)
    assertNull(r.seed("compose-m3", "buttons.FilledButton"))
    assertTrue(log.any { "seed cap" in it }, log.toString())
  }

  @Test
  fun `a non-UTF8 file is refused rather than opened as replacement characters`() {
    val r = resolver(body = { byteArrayOf(0xC3.toByte(), 0x28) })
    assertNull(r.seed("compose-m3", "buttons.FilledButton"))
    assertTrue(log.any { "not valid UTF-8" in it }, log.toString())
  }

  @Test
  fun `a seed is fetched once and served from cache after`() {
    val r = resolver()
    val first = r.seed("compose-m3", "buttons.FilledButton")
    val second = r.seed("compose-m3", "buttons.FilledButton")
    assertEquals(first, second)
    assertEquals(1, fetched.size, "a page reload must not re-fetch: $fetched")
  }

  @Test
  fun `the cache is bounded, and stops caching rather than growing`() {
    val r = resolver(maxEntries = 2)
    r.seed("compose-m3", "a")
    r.seed("compose-m3", "b")
    r.seed("compose-m3", "c")
    // The first two stay cached; the third is served but not retained, so a crawler walking every
    // preview cannot grow the map past the cap.
    fetched.clear()
    r.seed("compose-m3", "a")
    r.seed("compose-m3", "b")
    assertEquals(emptyList(), fetched, "the first entries stay cached")
    assertNotNull(r.seed("compose-m3", "c"))
    assertEquals(1, fetched.size, "the uncached one is re-fetched, not refused")
  }

  @Test
  fun `a source with no module links straight off the ref`() {
    val r = resolver(locate = { _, _ -> m3.copy(module = null) })
    assertNotNull(r.seed("x", "y"))
    assertEquals(
      listOf(
        "https://raw.githubusercontent.com/yschimke/compose-ai-tools/main/" +
          "src/main/kotlin/buttons/FilledButton.kt"
      ),
      fetched,
    )
  }

  @Test
  fun `a refreshed catalog misses the cache instead of serving the old source`() {
    // The staleness this closes: a catalog refreshed, retired, or republished under the same system
    // id would otherwise keep serving what it pointed at on first read — the viewer showing the new
    // catalog while the handoff opens the old file, for the life of the process.
    var ref = "v1"
    val r = resolver(locate = { _, _ -> m3.copy(ref = ref) })
    assertNotNull(r.seed("compose-m3", "p"))
    assertNotNull(r.seed("compose-m3", "p"))
    assertEquals(1, fetched.size, "unchanged metadata still caches")

    ref = "v2"
    assertNotNull(r.seed("compose-m3", "p"))
    assertEquals(2, fetched.size, "a moved ref must re-read")
    assertTrue(fetched.last().contains("/v2/"), fetched.last())
  }

  @Test
  fun `a cached seed expires, because a branch ref is stable while its file is not`() {
    val r = resolver(ttlSeconds = 60)
    assertNotNull(r.seed("compose-m3", "p"))
    now += 59_000
    assertNotNull(r.seed("compose-m3", "p"))
    assertEquals(1, fetched.size, "still fresh")
    now += 2_000
    assertNotNull(r.seed("compose-m3", "p"))
    assertEquals(2, fetched.size, "past the TTL it is re-read")
  }

  @Test
  fun `a full cache reclaims expired entries rather than wedging at the cap`() {
    val r = resolver(maxEntries = 2, ttlSeconds = 60)
    r.seed("compose-m3", "a")
    r.seed("compose-m3", "b")
    now += 61_000
    // Both entries are stale now, so a third caller reclaims their space instead of being served
    // uncached forever.
    r.seed("compose-m3", "c")
    fetched.clear()
    r.seed("compose-m3", "c")
    assertEquals(emptyList(), fetched, "the newest entry was actually cached")
  }

  @Test
  fun `locations that would join to the same string are still distinct keys`() {
    // Every field in the key is a repository path component, and paths may contain whatever
    // separator a joined string would use: `("a", "b c.kt")` and `("a b", "c.kt")` concatenate
    // identically. A catalog moving between those two must still miss the cache.
    var where = m3.copy(module = "a", sourceFile = "b c.kt")
    val r = resolver(locate = { _, _ -> where })
    assertNotNull(r.seed("s", "p"))
    where = m3.copy(module = "a b", sourceFile = "c.kt")
    assertNotNull(r.seed("s", "p"))
    assertEquals(2, fetched.size, "the second location must be fetched, not served from cache")
    assertTrue(fetched[0].endsWith("/a/b%20c.kt"), fetched[0])
    assertTrue(fetched[1].endsWith("/a%20b/c.kt"), fetched[1])
  }

  @Test
  fun `the editor tab is named by the source basename`() {
    assertEquals("FilledButton.kt", PlaygroundSeedResolver.fileNameFor("a/b/FilledButton.kt"))
    assertEquals("FilledButton.kt", PlaygroundSeedResolver.fileNameFor("a\\b\\FilledButton.kt"))
    // Whatever a catalog put in `sourceFile`, the tab name goes through the same sanitiser a
    // client-supplied file name does — the seed is staged as an ordinary run request, so no path
    // survives into the name and nothing traversal-shaped reaches the staging dir.
    assertEquals("passwd.kt", PlaygroundSeedResolver.fileNameFor("../../etc/passwd"))
    assertEquals("Snippet.kt", PlaygroundSeedResolver.fileNameFor("a/b/../"))
  }

  // -----------------------------------------------------------------------------------------
  // Cleaning: the handoff a visitor actually gets when they click "playground" from a catalog
  // card. Everything above pins how the source is *found*; this pins what lands in the editor.
  // -----------------------------------------------------------------------------------------

  private val stickerFile =
    """
    @file:CatalogGroup(name = "Buttons", section = "Actions")

    package ee.schimke.m3catalog.sections

    import androidx.compose.material3.Button
    import androidx.compose.material3.Text
    import androidx.compose.runtime.Composable
    import ee.schimke.composeai.preview.CatalogComponent
    import ee.schimke.composeai.preview.CatalogGroup
    import ee.schimke.m3catalog.CatalogModes
    import ee.schimke.m3catalog.Sticker
    import ee.schimke.m3catalog.counted
    import ee.schimke.m3catalog.generated.resources.Res
    import ee.schimke.m3catalog.generated.resources.label_filled
    import org.jetbrains.compose.resources.stringResource

    @CatalogComponent(id = "Button/Filled", caption = "Highest emphasis.")
    @CatalogModes
    @Composable
    fun FilledButton() = Sticker {
      val c = counted(stringResource(Res.string.label_filled))
      Button(onClick = c.onClick) { Text(c.label) }
    }
    """
      .trimIndent()

  private val usageRules =
    """
    {
      "scaffoldAnnotationPackages": ["ee.schimke.composeai.preview", "ee.schimke.m3catalog"],
      "stringsPath": "src/main/composeResources/values/strings.xml",
      "scaffolds": {
        "Sticker": {
          "kind": "RENAME",
          "renameTo": "MaterialTheme",
          "addImport": "androidx.compose.material3.MaterialTheme"
        },
        "counted": { "kind": "INLINE", "members": { "label": "${'$'}0", "onClick": "{}" } }
      }
    }
    """
      .trimIndent()

  private val stringsXml = """<resources><string name="label_filled">Filled</string></resources>"""

  /** The catalog's source ref, with the anchor discovery records inside the sticker's body. */
  private val anchored =
    m3.copy(bodyLine = stickerFile.lines().indexOfFirst { it.contains("val c =") } + 1)

  private fun cleaningResolver() =
    resolver(
      locate = { _, _ -> anchored },
      body = { url ->
        when {
          url.endsWith(PlaygroundSeedResolver.USAGE_RULES_FILE) -> usageRules.toByteArray()
          url.endsWith("strings.xml") -> stringsXml.toByteArray()
          else -> stickerFile.toByteArray()
        }
      },
    )

  @Test
  fun `the handoff seeds usage code, not the sticker`() {
    val seed = assertNotNull(cleaningResolver().seed("m3-catalog", "sections.FilledButton"))
    assertTrue(seed.cleaned)
    assertEquals(emptyList(), seed.residue)
    assertEquals(
      """
      import androidx.compose.material3.Button
      import androidx.compose.material3.MaterialTheme
      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import androidx.compose.ui.tooling.preview.Preview

      @Preview
      @Composable
      fun FilledButton() = MaterialTheme {
        Button(onClick = {}) { Text("Filled") }
      }
      """
        .trimIndent(),
      seed.text,
    )
  }

  /**
   * One cold key, many callers, one GitHub read.
   *
   * The panel is one click on a page anyone browsing a catalog is already on, so a popular preview
   * after a restart or a TTL expiry can have a dozen viewers arrive together — each otherwise
   * performing its own 10 s-connect / 10 s-read GET for the same file.
   */
  @Test
  fun `concurrent first reads of one preview fetch it once`() {
    val reads = java.util.concurrent.atomic.AtomicInteger()
    val start = java.util.concurrent.CountDownLatch(1)
    val resolver =
      resolver(
        locate = { _, _ -> anchored },
        body = { url ->
          if (url.endsWith(".kt")) {
            reads.incrementAndGet()
            // Hold the fetch open so every other caller is definitely inside seed() while it runs;
            // without this the test could pass by the threads simply not overlapping.
            Thread.sleep(150)
          }
          when {
            url.endsWith(PlaygroundSeedResolver.USAGE_RULES_FILE) -> usageRules.toByteArray()
            url.endsWith("strings.xml") -> stringsXml.toByteArray()
            else -> stickerFile.toByteArray()
          }
        },
      )
    val threads =
      (1..8).map {
        Thread {
          start.await()
          resolver.seed("m3-catalog", "sections.FilledButton")
        }
      }
    threads.forEach { it.start() }
    start.countDown()
    threads.forEach { it.join(10_000) }
    assertEquals(1, reads.get(), "the source file was read once per caller instead of once")
  }

  /**
   * The two outcomes that never reach the cache — a failed fetch, and a success dropped because the
   * cache is full — must still be shared with the callers waiting on the same flight. Signalling
   * only through the cache left each of them repeating the same round trip, sequentially, which is
   * the pile-up the coalescing exists to prevent.
   */
  @Test
  fun `a failed fetch is shared with waiters rather than retried by each`() {
    val reads = java.util.concurrent.atomic.AtomicInteger()
    val start = java.util.concurrent.CountDownLatch(1)
    val resolver =
      resolver(
        locate = { _, _ -> anchored },
        body = { url ->
          if (url.endsWith(".kt")) {
            reads.incrementAndGet()
            Thread.sleep(150)
            null // the source could not be read: never cached, so nothing to wake waiters with
          } else null
        },
      )
    val threads =
      (1..6).map {
        Thread {
          start.await()
          assertNull(resolver.seed("m3-catalog", "sections.FilledButton"))
        }
      }
    threads.forEach { it.start() }
    start.countDown()
    threads.forEach { it.join(10_000) }
    assertEquals(1, reads.get(), "each waiter repeated the failed fetch instead of sharing it")
  }

  /** One rules file per catalog, not per card: browsing a group must not re-ask GitHub for it. */
  @Test
  fun `the rules file is fetched once per catalog ref`() {
    val resolver = cleaningResolver()
    resolver.seed("m3-catalog", "sections.FilledButton")
    resolver.seed("m3-catalog", "sections.TonalButton")
    assertEquals(1, fetched.count { it.endsWith(PlaygroundSeedResolver.USAGE_RULES_FILE) })
    assertEquals(1, fetched.count { it.endsWith("strings.xml") })
  }

  /** A catalog that declares nothing still loses this repo's annotations. */
  @Test
  fun `a catalog with no rules file falls back to generic cleaning`() {
    val resolver =
      resolver(
        locate = { _, _ -> anchored },
        body = { url ->
          if (url.endsWith(PlaygroundSeedResolver.USAGE_RULES_FILE)) null
          else stickerFile.toByteArray()
        },
      )
    val seed = assertNotNull(resolver.seed("m3-catalog", "sections.FilledButton"))
    assertTrue(seed.cleaned)
    assertFalse(seed.text.contains("@CatalogComponent"), seed.text)
    assertTrue(seed.text.contains("Sticker {"), "generic rules must not invent a rename")
    // Empty, and correctly so: residue reports *declared* scaffolding that survived a rule. Under
    // generic rules the catalog's own package was never declared as scaffolding, so `Sticker` is an
    // ordinary reference that keeps its import — noisy, but not a gap in anyone's rules.
    assertEquals(emptyList(), seed.residue)
    assertTrue(seed.text.contains("import ee.schimke.m3catalog.Sticker"), seed.text)
  }

  /** No anchor ⇒ no cleaning, and no request for rules describing a declaration we cannot name. */
  @Test
  fun `a manifest with no anchor seeds verbatim and asks for no rules`() {
    val seed = assertNotNull(resolver(body = { stickerFile.toByteArray() }).seed("m3", "p"))
    assertFalse(seed.cleaned)
    assertFalse(seed.sliced)
    assertEquals(stickerFile, seed.text)
    assertTrue(fetched.none { it.endsWith(PlaygroundSeedResolver.USAGE_RULES_FILE) })
  }
}
