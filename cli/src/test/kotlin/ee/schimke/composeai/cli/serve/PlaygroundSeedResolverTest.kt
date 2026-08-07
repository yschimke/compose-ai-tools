package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
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
  fun `the editor tab is named by the source basename`() {
    assertEquals("FilledButton.kt", PlaygroundSeedResolver.fileNameFor("a/b/FilledButton.kt"))
    assertEquals("FilledButton.kt", PlaygroundSeedResolver.fileNameFor("a\\b\\FilledButton.kt"))
    // Whatever a catalog put in `sourceFile`, the tab name goes through the same sanitiser a
    // client-supplied file name does — the seed is staged as an ordinary run request, so no path
    // survives into the name and nothing traversal-shaped reaches the staging dir.
    assertEquals("passwd.kt", PlaygroundSeedResolver.fileNameFor("../../etc/passwd"))
    assertEquals("Snippet.kt", PlaygroundSeedResolver.fileNameFor("a/b/../"))
  }
}
