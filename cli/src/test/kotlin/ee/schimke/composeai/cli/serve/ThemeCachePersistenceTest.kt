package ee.schimke.composeai.cli.serve

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The disk tier for warmed theme renders, and the identity that decides when it may be read.
 *
 * The measurement this exists to make impossible again: `m3-catalog` needs ~28 hours of lane time
 * to warm its 10,120 targets, and on 2026-08-17 its cache was dropped seven times — three
 * delivery-branch regenerations and four server releases. It had never once had a window long
 * enough to finish. Persistence only helps if the identity is right, so most of what is asserted
 * here is about *when a generation must not be reused*.
 */
class ThemeCachePersistenceTest {

  private val temps = mutableListOf<File>()

  private fun tempDir(): File =
    createTempDirectory("theme-cache-test").toFile().also { temps += it }

  @AfterTest
  fun cleanUp() {
    temps.forEach { it.deleteRecursively() }
  }

  private fun jar(dir: File, name: String, content: String): File =
    File(dir, name).apply {
      parentFile.mkdirs()
      writeText(content)
    }

  private fun fingerprint(
    classpath: List<File>,
    variant: String = "desktop",
    version: String = "1.14.0",
    renderConfig: String = "density=2",
  ) = ThemeCacheFingerprint.of(classpath, variant, version, renderConfig)

  // ---- fingerprint ----------------------------------------------------------------------------

  @Test
  fun `the same bytes staged in a different directory are the same generation`() {
    // The property the whole design rests on. A catalog load stages its bundle into a fresh
    // directory every time, so a fingerprint that looked at paths would call every load a new
    // generation and persistence would buy exactly nothing.
    val first = tempDir()
    val second = tempDir()
    val a = listOf(jar(first, "catalog.jar", "CLASSES"), jar(first, "compose.jar", "DEPS"))
    val b = listOf(jar(second, "catalog.jar", "CLASSES"), jar(second, "compose.jar", "DEPS"))

    assertEquals(fingerprint(a), fingerprint(b))
  }

  @Test
  fun `a changed jar is a different generation`() {
    val dir = tempDir()
    val before = fingerprint(listOf(jar(dir, "catalog.jar", "v1")))
    val after = fingerprint(listOf(jar(dir, "catalog.jar", "v2")))

    assertNotEquals(before, after, "changed catalog code must not read a stale cache")
  }

  @Test
  fun `classpath order does not invent a new generation`() {
    val dir = tempDir()
    val one = jar(dir, "a.jar", "A")
    val two = jar(dir, "b.jar", "B")

    assertEquals(fingerprint(listOf(one, two)), fingerprint(listOf(two, one)))
  }

  @Test
  fun `renderer version, daemon variant and render config each change the generation`() {
    val dir = tempDir()
    val cp = listOf(jar(dir, "catalog.jar", "CLASSES"))
    val base = fingerprint(cp)

    // The version stands proxy for the whole container image — JVM, Skia, fonts — so a release must
    // never read the previous release's pixels.
    assertNotEquals(base, fingerprint(cp, version = "1.15.0"))
    // Desktop and Android/Robolectric read the same classpath and do not agree pixel-for-pixel.
    assertNotEquals(base, fingerprint(cp, variant = "android"))
    // The inputs that never appear in a cache key, and are therefore the easiest to forget.
    assertNotEquals(base, fingerprint(cp, renderConfig = "density=3"))
  }

  @Test
  fun `an unreadable classpath declines to name the generation at all`() {
    // Null means "do not persist". Inventing an identity for a classpath we could not read is how
    // two different generations end up agreeing on a name, which is the origin of every wrong pixel
    // this cache could serve.
    val dir = tempDir()
    assertNull(fingerprint(listOf(File(dir, "missing.jar"))))
    assertNull(fingerprint(emptyList()))
  }

  @Test
  fun `exploded class directories are hashed by content, not skipped`() {
    // A from-source catalog puts a directory on the classpath. Skipping it would fingerprint the
    // generation by its dependencies alone — so editing the catalog's own code would reuse the old
    // renders.
    val dir = tempDir()
    val classes = File(dir, "classes").apply { mkdirs() }
    jar(classes, "Button.class", "v1")
    val before = fingerprint(listOf(classes))
    jar(classes, "Button.class", "v2")

    assertNotEquals(before, fingerprint(listOf(classes)))
  }

  @Test
  fun `combining module fingerprints is order-independent and needs every part`() {
    assertEquals(
      ThemeCacheFingerprint.combine(listOf("aaa", "bbb")),
      ThemeCacheFingerprint.combine(listOf("bbb", "aaa")),
    )
    assertNotEquals(
      ThemeCacheFingerprint.combine(listOf("aaa", "bbb")),
      ThemeCacheFingerprint.combine(listOf("aaa", "ccc")),
    )
    // One unknown module makes the whole multi-module generation unknown.
    assertNull(ThemeCacheFingerprint.combine(listOf("aaa", "")))
    assertNull(ThemeCacheFingerprint.combine(emptyList()))
  }

  // ---- store ----------------------------------------------------------------------------------

  private fun inputs(fingerprint: String) =
    GenerationInputs(
      system = "m3-catalog",
      fingerprint = fingerprint,
      toolVersion = "1.14.0",
      variant = "desktop",
      renderConfig = "density=2",
    )

  @Test
  fun `a render written by one process is read by the next`() {
    // The whole point: a server restart is a new process over the same disk.
    val root = tempDir()
    val fp = "a".repeat(64)

    val first = ThemeCacheStore(root).open("m3-catalog", fp, inputs(fp))!!
    first.put("button-filled__brand", byteArrayOf(1, 2, 3))

    val second = ThemeCacheStore(root).open("m3-catalog", fp, inputs(fp))!!
    assertEquals(1, second.loadedEntries)
    assertTrue(second.contains("button-filled__brand"))
    assertContentEquals(byteArrayOf(1, 2, 3), second.get("button-filled__brand"))
  }

  @Test
  fun `a different generation cannot read the previous one's renders`() {
    val root = tempDir()
    val old = "a".repeat(64)
    val new = "b".repeat(64)
    ThemeCacheStore(root).open("m3-catalog", old, inputs(old))!!.put("k", byteArrayOf(9))

    val fresh = ThemeCacheStore(root).open("m3-catalog", new, inputs(new))!!

    assertEquals(0, fresh.loadedEntries)
    assertNull(fresh.get("k"), "a new fingerprint must start clean, not inherit")
  }

  @Test
  fun `two catalogs with identical keys do not share renders`() {
    val root = tempDir()
    val fp = "c".repeat(64)
    val store = ThemeCacheStore(root)
    store.open("m3-catalog", fp, inputs(fp))!!.put("shared-key", byteArrayOf(1))

    assertNull(store.open("wear-m3", fp, inputs(fp))!!.get("shared-key"))
  }

  @Test
  fun `sweeping reclaims dead generations and keeps the live one`() {
    val root = tempDir()
    val live = "a".repeat(64)
    val dead = "b".repeat(64)
    val store = ThemeCacheStore(root)
    store.open("m3-catalog", live, inputs(live))!!.put("k", ByteArray(64))
    store.open("m3-catalog", dead, inputs(dead))!!.put("k", ByteArray(64))

    val result = store.sweep(setOf(ThemeCacheStore.GenerationId("m3-catalog", live)))

    assertEquals(1, result.deletedGenerations)
    assertTrue(result.reclaimedBytes > 0)
    assertFalse(result.overCap)
    assertTrue(store.open("m3-catalog", live, inputs(live))!!.contains("k"), "live must survive")
  }

  @Test
  fun `a live set over the cap is reported, never evicted`() {
    // Deleting what is currently being warmed to fit a cap turns the cap into a treadmill: the
    // optimizer re-renders exactly what the sweep discarded, forever, and the box looks busy while
    // making no progress.
    val root = tempDir()
    val fp = "a".repeat(64)
    val store = ThemeCacheStore(root, maxBytes = 1)
    store.open("m3-catalog", fp, inputs(fp))!!.put("k", ByteArray(4096))

    val result = store.sweep(setOf(ThemeCacheStore.GenerationId("m3-catalog", fp)))

    assertTrue(result.overCap, "an operator must be told the cap is too small")
    assertEquals(0, result.deletedGenerations)
    assertTrue(store.open("m3-catalog", fp, inputs(fp))!!.contains("k"))
  }

  @Test
  fun `a name that could escape the store root is refused, not sanitised`() {
    // Rejected rather than rewritten: a silently sanitised name would let two catalogs collide on
    // one generation, which is worse than not caching at all.
    val store = ThemeCacheStore(tempDir())
    val fp = "a".repeat(64)
    assertNull(store.open("../escape", fp, inputs(fp)))
    assertNull(store.open("m3-catalog", "../escape", inputs(fp)))
    assertNull(store.open("m3/catalog", fp, inputs(fp)))
  }

  // ---- two-tier cache -------------------------------------------------------------------------

  @Test
  fun `a target evicted from memory still counts as cached while it is on disk`() {
    // The reason `cached` asks both tiers. Memory is capped at 128 MB and a warmed m3-catalog is
    // several times that, so counting memory alone would report a fully warmed catalog as partially
    // cached the moment the window started evicting — and send the optimizer back to re-render what
    // was already on disk.
    val root = tempDir()
    val fp = "a".repeat(64)
    val generation = ThemeCacheStore(root).open("m3-catalog", fp, inputs(fp))!!
    // A memory tier far too small to hold both entries.
    val cache = CatalogThemeCache(maxBytes = 128, persistence = generation)
    cache.configureTargets(listOf("one", "two"))

    cache.put("one", ByteArray(100) { 1 })
    cache.put("two", ByteArray(100) { 2 })

    val snapshot = cache.snapshot()
    assertEquals(2, snapshot.cached, "both targets are warm even though only one fits in memory")
    assertTrue(snapshot.fullyOptimized)
    assertEquals("complete", snapshot.state)
    // And the evicted one still reads back, promoted from disk.
    assertContentEquals(ByteArray(100) { 1 }, cache.get("one"))
  }

  @Test
  fun `verification drops the whole generation when a cached render no longer matches`() {
    // The safety net for the input nobody thought of. A mismatch means the fingerprint failed to
    // capture something, so every entry under it is suspect — keeping the rest would be trusting
    // the same identity that just proved untrustworthy.
    val root = tempDir()
    val fp = "a".repeat(64)
    val generation = ThemeCacheStore(root).open("m3-catalog", fp, inputs(fp))!!
    val cache = CatalogThemeCache(persistence = generation)
    cache.configureTargets(listOf("one", "two"))
    cache.put("one", byteArrayOf(1))
    cache.put("two", byteArrayOf(2))

    val trustworthy = cache.verifySample { byteArrayOf(99) }

    assertFalse(trustworthy)
    assertEquals(0, cache.snapshot().cached, "a failed verification leaves nothing behind")
    assertNull(cache.get("one"))
  }

  @Test
  fun `verification keeps a generation whose renders still match`() {
    val root = tempDir()
    val fp = "a".repeat(64)
    val generation = ThemeCacheStore(root).open("m3-catalog", fp, inputs(fp))!!
    val cache = CatalogThemeCache(persistence = generation)
    cache.configureTargets(listOf("one"))
    cache.put("one", byteArrayOf(7))

    assertTrue(cache.verifySample { byteArrayOf(7) })
    assertEquals(1, cache.snapshot().cached)
  }

  @Test
  fun `a daemon that cannot render verifies nothing rather than wiping the cache`() {
    // A null render is "could not answer", not "answered differently". Treating the two alike would
    // let a busy or cold daemon at startup throw away a fully warmed catalog — the exact loss this
    // whole change exists to prevent.
    val root = tempDir()
    val fp = "a".repeat(64)
    val generation = ThemeCacheStore(root).open("m3-catalog", fp, inputs(fp))!!
    val cache = CatalogThemeCache(persistence = generation)
    cache.configureTargets(listOf("one"))
    cache.put("one", byteArrayOf(7))

    assertTrue(cache.verifySample { null })
    assertEquals(1, cache.snapshot().cached)
  }

  @Test
  fun `a cache with no disk tier behaves exactly as it did before`() {
    val cache = CatalogThemeCache()
    cache.configureTargets(listOf("one"))
    cache.put("one", byteArrayOf(1))

    assertTrue(
      cache.verifySample { byteArrayOf(99) },
      "nothing persisted means nothing to distrust",
    )
    assertEquals(1, cache.snapshot().cached)
  }
}

private fun assertContentEquals(expected: ByteArray, actual: ByteArray?) {
  kotlin.test.assertNotNull(actual)
  kotlin.test.assertTrue(expected.contentEquals(actual), "byte contents differ")
}
