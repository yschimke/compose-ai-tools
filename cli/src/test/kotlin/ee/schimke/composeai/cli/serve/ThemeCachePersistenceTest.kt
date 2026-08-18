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
  fun `classpath order is part of the generation, because precedence decides the pixels`() {
    // When two entries carry the same class or resource the JVM resolves the earlier one, so the
    // same jars in a different order can genuinely render differently. Hashing order-insensitively
    // would let a render be reused from the wrong resolution order — a wrong pixel, where being
    // order-sensitive costs at worst an unnecessary re-warm.
    val dir = tempDir()
    val one = jar(dir, "a.jar", "A")
    val two = jar(dir, "b.jar", "B")

    assertNotEquals(fingerprint(listOf(one, two)), fingerprint(listOf(two, one)))
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

  @Test
  fun `the catalog's own classes are fingerprinted, not just its framework dependencies`() {
    // The collision this closes. `splitBundleRuntime` puts the bundle's own classes/ directory into
    // `composeai.daemon.userClassDirs` and leaves `classpath` holding parent overlays and daemon
    // sidecars only — so hashing `classpath` alone gave two catalog revisions with unchanged
    // dependencies the SAME name, and the new revision would adopt the old one's pixels. That is
    // exactly the failure this whole mechanism exists to prevent, and it is invisible from the
    // parent classpath.
    val dir = tempDir()
    val framework = jar(dir, "compose-runtime.jar", "UNCHANGED")
    val classes = File(dir, "classes").apply { mkdirs() }
    jar(classes, "Buttons.class", "revision-1")

    fun fingerprintNow() =
      fingerprint(
        ThemeCacheFingerprint.renderedClasspath(
          classpath = listOf(framework.absolutePath),
          systemProperties =
            mapOf(ThemeCacheFingerprint.USER_CLASS_DIRS_PROPERTY to classes.absolutePath),
        )
      )

    val before = fingerprintNow()
    jar(classes, "Buttons.class", "revision-2")

    assertNotEquals(before, fingerprintNow(), "a catalog code change must be a new generation")
  }

  @Test
  fun `a descriptor with no user classpath still fingerprints its parent classpath`() {
    val dir = tempDir()
    val framework = jar(dir, "compose-runtime.jar", "DEPS")

    val resolved =
      ThemeCacheFingerprint.renderedClasspath(
        classpath = listOf(framework.absolutePath),
        systemProperties = emptyMap(),
      )

    assertEquals(listOf(framework), resolved)
  }

  @Test
  fun `declared themes persist even when the eager optimizer pass is switched off`() {
    // With `-Dcomposeai.serve.themeOptimization=false` the pass never declares its targets, so
    // gating persistence on the target set refused every render a visitor actually asked for and
    // each restart began again — persistence doing nothing on precisely the configuration where the
    // renders it does get are most worth keeping.
    val root = tempDir()
    val fp = "a".repeat(64)
    val generation = store(root).open("m3-catalog", fp, inputs(fp))!!
    val cache = CatalogThemeCache(persistence = generation)
    cache.configurePersistable(listOf("declared-theme"))

    cache.put("declared-theme", byteArrayOf(5))

    assertTrue(generation.contains("declared-theme"))
    // And no optimization row is claimed, which is what a disabled pass should report.
    assertNull(cache.snapshot().takeIf { it.total > 0 })
  }

  @Test
  fun `the alias routing is part of the generation`() {
    // Persisted keys name the published catalog id, but a render resolves it through the alias map
    // first. A delivery-branch update can repoint an id at a different daemon preview while
    // shipping
    // a byte-identical bundle — same classpath, same key, different pixels.
    val dir = tempDir()
    val cp = listOf(jar(dir, "catalog.jar", "CLASSES"))
    fun fp(alias: Map<String, String>) =
      ThemeCacheFingerprint.of(
        cp,
        variant = "desktop",
        toolVersion = "1.14.0",
        renderConfig = "density=2",
        routing = ThemeCacheFingerprint.routingDigest(alias),
      )

    assertNotEquals(fp(mapOf("button" to "daemon-a")), fp(mapOf("button" to "daemon-b")))
    assertEquals(
      fp(mapOf("a" to "x", "b" to "y")),
      fp(mapOf("b" to "y", "a" to "x")),
      "map iteration order is not part of what the routing means",
    )
  }

  // ---- store ----------------------------------------------------------------------------------

  /**
   * A store whose sweep grace window is disabled, so a test can assert reclamation directly.
   *
   * Production keeps a grace window for the zero-downtime rollout case — see the dedicated test
   * below — but every other assertion here is about what the sweep decides, not about how long it
   * waits to decide it.
   */
  private fun store(root: File, maxBytes: Long = ThemeCacheStore.DEFAULT_MAX_BYTES) =
    ThemeCacheStore(root, maxBytes = maxBytes, graceMillis = 0)

  private fun inputs(fingerprint: String) =
    GenerationInputs(
      system = "m3-catalog",
      fingerprint = fingerprint,
      toolVersion = "1.14.0",
      variant = "desktop",
      renderConfig = "density=2",
    )

  @Test
  fun `a generation young enough to belong to another replica is not reclaimed`() {
    // The image deployment rolls out zero-downtime: a new replica boots beside the running one on
    // the same volume and sees the old one's generations as unreferenced. Sweeping them would
    // delete
    // a possibly 28-hour cache belonging to the replica still serving production — and still
    // serving
    // it if the new replica fails readiness.
    val root = tempDir()
    val theirs = "a".repeat(64)
    val ours = "b".repeat(64)
    var now = 1_000_000L
    val rolling = ThemeCacheStore(root, graceMillis = 60 * 60_000, clock = { now })
    rolling.open("m3-catalog", theirs, inputs(theirs))!!.put("k", ByteArray(32))
    rolling.open("m3-catalog", ours, inputs(ours))!!.put("k", ByteArray(32))

    val during =
      rolling.sweep(
        setOf(ThemeCacheStore.GenerationId("m3-catalog", ours)),
        onlySystems = setOf("m3-catalog"),
      )
    assertEquals(0, during.deletedGenerations, "the other replica's cache must survive the rollout")

    // Once the window has passed there is no replica left that could be using it.
    now += 2 * 60 * 60_000
    val after =
      rolling.sweep(
        setOf(ThemeCacheStore.GenerationId("m3-catalog", ours)),
        onlySystems = setOf("m3-catalog"),
      )
    assertEquals(1, after.deletedGenerations)
  }

  @Test
  fun `captured IR payloads are part of the generation`() {
    // A bundle can regenerate a Remote Compose / protolayout capture without touching a class. The
    // daemon renders FROM those bytes, and they arrive as system-property paths rather than
    // classpath entries — so anything reading only the classpath calls two different scenes one
    // generation.
    val dir = tempDir()
    val jarFile = jar(dir, "catalog.jar", "UNCHANGED")
    val ir = File(dir, "ir").apply { mkdirs() }
    jar(ir, "scene.rc", "capture-1")

    fun fingerprintNow() =
      fingerprint(
        ThemeCacheFingerprint.renderedClasspath(
          classpath = listOf(jarFile.absolutePath),
          systemProperties = mapOf(ThemeCacheFingerprint.PAYLOAD_PROPERTIES[0] to ir.absolutePath),
        )
      )

    val before = fingerprintNow()
    jar(ir, "scene.rc", "capture-2")

    assertNotEquals(before, fingerprintNow(), "a regenerated capture must be a new generation")
  }

  @Test
  fun `a render written by one process is read by the next`() {
    // The whole point: a server restart is a new process over the same disk.
    val root = tempDir()
    val fp = "a".repeat(64)

    val first = store(root).open("m3-catalog", fp, inputs(fp))!!
    first.put("button-filled__brand", byteArrayOf(1, 2, 3))

    val second = store(root).open("m3-catalog", fp, inputs(fp))!!
    assertEquals(1, second.loadedEntries)
    assertTrue(second.contains("button-filled__brand"))
    assertContentEquals(byteArrayOf(1, 2, 3), second.get("button-filled__brand"))
  }

  @Test
  fun `adopted renders are withheld from reads until verification settles`() {
    // Verification is asynchronous — it needs a lane and a warm daemon — so between adopting a
    // generation and checking it there is a window where the fingerprint might be wrong. Serving
    // those bytes in that window is the one thing the safety check exists to prevent, and traffic
    // can hold the window open by keeping the box non-idle.
    val root = tempDir()
    val fp = "a".repeat(64)
    store(root).open("m3-catalog", fp, inputs(fp))!!.also { it.put("one", byteArrayOf(1)) }

    // A NEW process adopts that generation.
    val adopted = store(root).open("m3-catalog", fp, inputs(fp))!!
    val cache = CatalogThemeCache(persistence = adopted)
    cache.configurePersistable(listOf("one"))

    assertNull(cache.get("one"), "an unverified adopted render must not be served")
    // But it still counts as warm, so the optimizer does not re-render what is already on disk.
    assertTrue(cache.contains("one"))

    assertEquals(CatalogThemeCache.VerifyOutcome.VERIFIED, cache.verifySample { byteArrayOf(1) })
    assertContentEquals(byteArrayOf(1), cache.get("one"))
  }

  @Test
  fun `a cache that adopted nothing serves its own renders immediately`() {
    // The quarantine must not cost anything on a cold generation: there is nothing to distrust when
    // every entry was rendered by this process.
    val root = tempDir()
    val fp = "a".repeat(64)
    val cache = CatalogThemeCache(persistence = store(root).open("m3-catalog", fp, inputs(fp))!!)
    cache.configurePersistable(listOf("one"))

    cache.put("one", byteArrayOf(7))

    assertContentEquals(byteArrayOf(7), cache.get("one"))
  }

  @Test
  fun `concurrent writers do not share a temporary file`() {
    // The zero-downtime rollout puts two processes on this volume at once. A temp path shared by
    // cache key lets one replica rename the inode while the other is still writing it, publishing a
    // half-PNG under a name that claims to be complete.
    val root = tempDir()
    val fp = "a".repeat(64)
    val one = store(root).open("m3-catalog", fp, inputs(fp))!!
    val two = store(root).open("m3-catalog", fp, inputs(fp))!!
    val payload = ByteArray(64) { 5 }

    val threads =
      listOf(one, two).map { generation ->
        Thread { repeat(20) { generation.put("shared-key", payload) } }.also(Thread::start)
      }
    threads.forEach { it.join(10_000) }

    assertContentEquals(payload, store(root).open("m3-catalog", fp, inputs(fp))!!.get("shared-key"))
    // No temp files left behind under either writer's name.
    val leftovers =
      File(File(root, "m3-catalog"), fp).listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
    assertEquals(emptyList(), leftovers)
  }

  @Test
  fun `a different generation cannot read the previous one's renders`() {
    val root = tempDir()
    val old = "a".repeat(64)
    val new = "b".repeat(64)
    store(root).open("m3-catalog", old, inputs(old))!!.put("k", byteArrayOf(9))

    val fresh = store(root).open("m3-catalog", new, inputs(new))!!

    assertEquals(0, fresh.loadedEntries)
    assertNull(fresh.get("k"), "a new fingerprint must start clean, not inherit")
  }

  @Test
  fun `two catalogs with identical keys do not share renders`() {
    val root = tempDir()
    val fp = "c".repeat(64)
    val store = store(root)
    store.open("m3-catalog", fp, inputs(fp))!!.put("shared-key", byteArrayOf(1))

    assertNull(store.open("wear-m3", fp, inputs(fp))!!.get("shared-key"))
  }

  @Test
  fun `sweeping reclaims dead generations and keeps the live one`() {
    val root = tempDir()
    val live = "a".repeat(64)
    val dead = "b".repeat(64)
    val store = store(root)
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
    val store = store(root, maxBytes = 1)
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
    val generation = store(root).open("m3-catalog", fp, inputs(fp))!!
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
    val generation = store(root).open("m3-catalog", fp, inputs(fp))!!
    val cache = CatalogThemeCache(persistence = generation)
    cache.configureTargets(listOf("one", "two"))
    cache.put("one", byteArrayOf(1))
    cache.put("two", byteArrayOf(2))

    val outcome = cache.verifySample { byteArrayOf(99) }

    assertEquals(CatalogThemeCache.VerifyOutcome.MISMATCH, outcome)
    assertEquals(0, cache.snapshot().cached, "a failed verification leaves nothing behind")
    assertNull(cache.get("one"))
  }

  @Test
  fun `verification keeps a generation whose renders still match`() {
    val root = tempDir()
    val fp = "a".repeat(64)
    val generation = store(root).open("m3-catalog", fp, inputs(fp))!!
    val cache = CatalogThemeCache(persistence = generation)
    cache.configureTargets(listOf("one"))
    cache.put("one", byteArrayOf(7))

    assertEquals(CatalogThemeCache.VerifyOutcome.VERIFIED, cache.verifySample { byteArrayOf(7) })
    assertEquals(1, cache.snapshot().cached)
  }

  @Test
  fun `a daemon that cannot render verifies nothing rather than wiping the cache`() {
    // A null render is "could not answer", not "answered differently". Treating the two alike would
    // let a busy or cold daemon at startup throw away a fully warmed catalog — the exact loss this
    // whole change exists to prevent.
    val root = tempDir()
    val fp = "a".repeat(64)
    val generation = store(root).open("m3-catalog", fp, inputs(fp))!!
    val cache = CatalogThemeCache(persistence = generation)
    cache.configureTargets(listOf("one"))
    cache.put("one", byteArrayOf(7))

    assertEquals(
      CatalogThemeCache.VerifyOutcome.NO_EVIDENCE,
      cache.verifySample { null },
      "a daemon that cannot answer leaves the question open, it does not settle it",
    )
    assertEquals(1, cache.snapshot().cached)
  }

  @Test
  fun `only configured targets are written to disk`() {
    // `put` also takes foreground renders with arbitrary overrides — widths, locales, devices, knob
    // values — and those are unbounded where `previews × declaredThemes` is not. Since a live
    // generation is never evicted to honour the cap, persisting them would let a visitor on a
    // public
    // box grow the store until the volume filled.
    val root = tempDir()
    val fp = "a".repeat(64)
    val generation = store(root).open("m3-catalog", fp, inputs(fp))!!
    val cache = CatalogThemeCache(persistence = generation)
    cache.configureTargets(listOf("declared-theme"))

    cache.put("declared-theme", byteArrayOf(1))
    cache.put("ad-hoc?width=999&locale=fr", byteArrayOf(2))

    assertTrue(generation.contains("declared-theme"))
    assertFalse(
      generation.contains("ad-hoc?width=999&locale=fr"),
      "an arbitrary override render must not reach the durable tier",
    )
    // It is still served from memory, exactly as before persistence existed.
    assertContentEquals(byteArrayOf(2), cache.get("ad-hoc?width=999&locale=fr"))
  }

  @Test
  fun `a render too large for the memory window is still persisted`() {
    // The disk tier has its own budget and is the authoritative store behind a deliberately smaller
    // memory window. Gating the durable write on the memory cap made a small-memory deployment
    // silently re-render everything after each restart.
    val root = tempDir()
    val fp = "a".repeat(64)
    val generation = store(root).open("m3-catalog", fp, inputs(fp))!!
    val cache = CatalogThemeCache(maxBytes = 8, persistence = generation)
    cache.configureTargets(listOf("big"))

    cache.put("big", ByteArray(64) { 3 })

    assertTrue(generation.contains("big"))
    assertEquals(1, cache.snapshot().cached)
    assertContentEquals(ByteArray(64) { 3 }, cache.get("big"))
  }

  @Test
  fun `a discarded generation can still be rebuilt`() {
    // Discarding deletes the stale PNGs but must leave a writable directory: the same Generation
    // stays attached to the live cache, and if its directory vanished every later write would fail
    // silently and the catalog would re-render into memory alone, losing it all again at restart.
    val root = tempDir()
    val fp = "a".repeat(64)
    val generation = store(root).open("m3-catalog", fp, inputs(fp))!!
    val cache = CatalogThemeCache(persistence = generation)
    cache.configureTargets(listOf("one"))
    cache.put("one", byteArrayOf(1))

    assertEquals(CatalogThemeCache.VerifyOutcome.MISMATCH, cache.verifySample { byteArrayOf(99) })

    cache.put("one", byteArrayOf(42))
    assertTrue(generation.contains("one"), "the generation must accept writes again")
    assertContentEquals(
      byteArrayOf(42),
      store(root).open("m3-catalog", fp, inputs(fp))!!.get("one"),
    )
  }

  @Test
  fun `a system absent from the live set keeps its generations`() {
    // A catalog whose load failed this pass — a transient fetch error, a shutdown before the loader
    // reached it — has no live generation. Sweeping it would make the refresher's later success
    // restart ~28 hours of warming, punishing a catalog for a network blip.
    val root = tempDir()
    val fp = "a".repeat(64)
    val store = store(root)
    store.open("m3-catalog", fp, inputs(fp))!!.put("k", ByteArray(32))
    store.open("did-not-load", fp, inputs(fp))!!.put("k", ByteArray(32))

    val result =
      store.sweep(
        setOf(ThemeCacheStore.GenerationId("m3-catalog", fp)),
        onlySystems = setOf("m3-catalog"),
      )

    assertEquals(0, result.deletedGenerations)
    assertTrue(store.open("did-not-load", fp, inputs(fp))!!.contains("k"))
  }

  @Test
  fun `a superseded generation of a loaded system is still reclaimed`() {
    // The other half of the same rule: scoping the sweep to loaded systems must not stop it
    // reclaiming that system's own previous fingerprint, or a branch regenerating several times a
    // day accumulates generations until the volume fills.
    val root = tempDir()
    val old = "a".repeat(64)
    val new = "b".repeat(64)
    val store = store(root)
    store.open("m3-catalog", old, inputs(old))!!.put("k", ByteArray(32))
    store.open("m3-catalog", new, inputs(new))!!.put("k", ByteArray(32))

    val result =
      store.sweep(
        setOf(ThemeCacheStore.GenerationId("m3-catalog", new)),
        onlySystems = setOf("m3-catalog"),
      )

    assertEquals(1, result.deletedGenerations)
    assertEquals(0, store(root).open("m3-catalog", old, inputs(old))!!.loadedEntries)
  }

  @Test
  fun `a cache with no disk tier behaves exactly as it did before`() {
    val cache = CatalogThemeCache()
    cache.configureTargets(listOf("one"))
    cache.put("one", byteArrayOf(1))

    assertEquals(
      CatalogThemeCache.VerifyOutcome.NOTHING_TO_VERIFY,
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
