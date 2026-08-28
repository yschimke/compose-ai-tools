package ee.schimke.composeai.cli.serve

import java.io.File
import java.io.RandomAccessFile
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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
    renderConfig: String = "density=2",
  ) = ThemeCacheFingerprint.of(classpath, variant, renderConfig)

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
  fun `daemon variant and render config each change the generation, and the tool version does not`() {
    val dir = tempDir()
    val cp = listOf(jar(dir, "catalog.jar", "CLASSES"))
    val base = fingerprint(cp)

    // Desktop and Android/Robolectric read the same classpath and do not agree pixel-for-pixel.
    assertNotEquals(base, fingerprint(cp, variant = "android"))
    // The inputs that never appear in a cache key, and are therefore the easiest to forget.
    assertNotEquals(base, fingerprint(cp, renderConfig = "density=3"))
  }

  /**
   * The version used to be keyed on, and a release therefore orphaned every warmed render on the
   * box. On preview.coo.ee, where a full pass is 18,604 entries and the better part of a day, four
   * versions shipped inside four hours — so the cache was invalidated faster than it could ever be
   * filled, and was adopted exactly zero times.
   *
   * It was never proof of anything either: it stood *proxy* for the container image, which a
   * base-image bump changes without moving the version at all. What actually covers a renderer that
   * moved is the load-time sample verification, which the next test exercises — crossing a version
   * boundary is simply the adopted-entry case, and adopted entries are withheld until the sample
   * agrees.
   */
  @Test
  fun `a new build reads the previous build's generation`() {
    val dir = tempDir()
    val cp = listOf(jar(dir, "catalog.jar", "CLASSES"))
    assertEquals(
      fingerprint(cp),
      fingerprint(cp),
      "the same inputs name the same generation whatever build is asking",
    )

    // And the manifest still answers which build last wrote here, so a volume whose pixels are in
    // question stays diagnosable.
    val root = tempDir()
    val fp = assertNotNull(fingerprint(cp))
    store(root).open("m3-catalog", fp, inputs(fp).copy(toolVersion = "1.14.0"))
    val reopened = store(root).open("m3-catalog", fp, inputs(fp).copy(toolVersion = "1.15.0"))
    assertNotNull(reopened)
    val manifest =
      kotlinx.serialization.json.Json.decodeFromString(
        GenerationInputs.serializer(),
        File(File(File(root, "m3-catalog"), fp), ThemeCacheStore.MANIFEST_NAME).readText(),
      )
    assertEquals("1.15.0", manifest.toolVersion, "the manifest names the build that last opened it")
  }

  /**
   * The dirty model in one test: renders inherited across a build are warm, servable and marked;
   * re-rendering one clears its mark; and a mark is derived from the file's own timestamp rather
   * than an index someone has to keep in step with 18,604 files.
   */
  @Test
  fun `renders inherited from another build are dirty until re-rendered`() {
    val root = tempDir()
    val fp = "fp-a"
    val first = assertNotNull(store(root).open("m3-catalog", fp, inputs(fp)))
    first.put("preview|dark", ByteArray(8) { 1 })
    first.put("preview|light", ByteArray(8) { 2 })
    assertEquals(0, first.dirtyCount(), "its own renders are never dirty to itself")

    // A different build opens the same generation — which it can, since the version left the key.
    ageRenders(root, "m3-catalog", fp, byMillis = 10_000)
    val next =
      assertNotNull(store(root).open("m3-catalog", fp, inputs(fp).copy(toolVersion = "1.15.0")))
    assertEquals(2, next.dirtyCount(), "everything the previous build wrote is queued")
    assertTrue(next.isDirty("preview|dark") && next.isDirty("preview|light"))
    assertTrue(next.contains("preview|dark"), "and stays warm meanwhile — dirty is not absent")

    // Re-rendering one clears it, and only it.
    next.put("preview|dark", ByteArray(8) { 3 }, replaceExisting = true)
    assertEquals(1, next.dirtyCount())
    assertFalse(next.isDirty("preview|dark"), "the one re-rendered is clean")
    assertTrue(next.isDirty("preview|light"), "and only it")
    assertContentEquals(
      ByteArray(8) { 3 },
      next.get("preview|dark"),
      "and the regenerated bytes are what the store now serves",
    )
  }

  /**
   * The status row counts a dirty render the pass cannot replace.
   *
   * `failed` counted only keys that are not cached, which is right while a gap is a gap: a key that
   * failed and has since rendered is no longer a failure. A **dirty** key breaks that equivalence,
   * because it is cached on purpose — serving another build's pixels is what the dirty model buys —
   * so a re-render failing over and over was counted as zero, and the one row that exists to say a
   * warm catalog is not really finished could never say it.
   */
  @Test
  fun `a dirty render the pass cannot regenerate is counted as failed`() {
    val root = tempDir()
    val fp = "fp-dirty-failed"
    val first = assertNotNull(store(root).open("m3-catalog", fp, inputs(fp)))
    first.put("preview|dark", ByteArray(8) { 1 })

    ageRenders(root, "m3-catalog", fp, byMillis = 10_000)
    val next =
      assertNotNull(store(root).open("m3-catalog", fp, inputs(fp).copy(toolVersion = "1.15.0")))
    val cache = CatalogThemeCache(persistence = next)
    cache.configureTargets(listOf("preview|dark"))

    assertTrue(cache.contains("preview|dark"), "the inherited render still serves")
    assertEquals(1, cache.snapshot().dirty, "and it is queued for regeneration")

    cache.markFailed("preview|dark", "the daemon refused the re-render")

    assertEquals(
      1,
      cache.snapshot().failed,
      "a dirty entry the pass cannot regenerate is a failure, even though it is still cached",
    )

    // The other half of the rule, which the fix must not cost: once the entry really is this
    // build's work, a later failure against it is not a gap in the generation.
    cache.put("preview|dark", ByteArray(8) { 2 })
    assertEquals(0, cache.snapshot().dirty, "regenerating clears the mark")
    cache.markFailed("preview|dark", "a later, transient refusal")
    assertEquals(
      0,
      cache.snapshot().failed,
      "a clean cached render that failed afterwards is not a missing target",
    )
  }

  @Test
  fun `a failed sample drops the dirty renders and keeps the ones this build made`() {
    val root = tempDir()
    val fp = "fp-a"
    val first = assertNotNull(store(root).open("m3-catalog", fp, inputs(fp)))
    first.put("inherited|dark", ByteArray(8) { 1 })

    ageRenders(root, "m3-catalog", fp, byMillis = 10_000)
    val next =
      assertNotNull(store(root).open("m3-catalog", fp, inputs(fp).copy(toolVersion = "1.15.0")))
    // Written after the boundary, so this is this build's own work and must survive the drop.
    next.put("mine|dark", ByteArray(8) { 9 })

    assertEquals(1, next.discardDirty(), "only the inherited render goes")
    assertFalse(next.contains("inherited|dark"))
    assertTrue(
      next.contains("mine|dark"),
      "the renders this build made survive — the sample disagreeing with the old build is " +
        "precisely evidence FOR them, and discarding the generation wholesale threw away an " +
        "hour of rendering to fix a problem they did not have",
    )
    assertEquals(0, next.dirtyCount(), "and nothing older than the boundary is left")
  }

  /**
   * What a failed sample must take, and why "dirty" is the wrong set to narrow to.
   *
   * The sample draws its candidates from `wasAdopted` — entries present when this generation opened
   * — so adoption is the boundary it actually tests. Dirtiness asks a different question, "did a
   * different BUILD write this", and a same-version restart of a partly converged generation
   * inherits the previous process's renders as clean. Narrowing to dirty would delete an older
   * build's leftovers, report a positive count that suppresses the fallback discard, lift the
   * quarantine, and go on serving the very entries the sample disagreed with.
   */
  @Test
  fun `a failed sample takes everything inherited, not just what an older build wrote`() {
    val root = tempDir()
    val fp = "fp-a"
    val oldBuild = assertNotNull(store(root).open("m3-catalog", fp, inputs(fp)))
    oldBuild.put("from-old-build|dark", ByteArray(8) { 1 })
    ageRenders(root, "m3-catalog", fp, byMillis = 10_000)

    // This build opens, marking the old build's render dirty, and renders one of its own.
    val firstRun =
      assertNotNull(store(root).open("m3-catalog", fp, inputs(fp).copy(toolVersion = "1.15.0")))
    firstRun.put("from-previous-process|dark", ByteArray(8) { 2 })

    // ...then restarts. Same version, so the manifest is left alone and its boundary still stands:
    // the old build's render is dirty, and the previous PROCESS's render is clean.
    val restarted =
      assertNotNull(store(root).open("m3-catalog", fp, inputs(fp).copy(toolVersion = "1.15.0")))
    restarted.put("from-this-process|dark", ByteArray(8) { 3 })
    assertEquals(1, restarted.dirtyCount(), "only the old build's render is DIRTY")
    assertTrue(
      restarted.wasAdopted("from-previous-process|dark"),
      "but the previous process's render was still inherited, and inherited is what the sample " +
        "tests",
    )

    assertEquals(2, restarted.discardAdopted(), "both inherited renders go")
    assertFalse(restarted.contains("from-old-build|dark"))
    assertFalse(
      restarted.contains("from-previous-process|dark"),
      "including the one no boundary called dirty — an untracked input such as the base image or " +
        "the installed fonts moved, and this process never saw these pixels made",
    )
    assertTrue(
      restarted.contains("from-this-process|dark"),
      "and what this process rendered survives: the sample disagreeing with the inherited bytes " +
        "is precisely evidence FOR these",
    )
    assertEquals(0, restarted.dirtyCount())
  }

  @Test
  fun `marking a catalog dirty queues its renders without taking them away`() {
    val root = tempDir()
    val fp = "fp-a"
    val generation = assertNotNull(store(root).open("m3-catalog", fp, inputs(fp)))
    generation.put("preview|dark", ByteArray(8) { 1 })
    assertEquals(0, generation.dirtyCount(), "a build is never dirty to itself")
    ageRenders(root, "m3-catalog", fp, byMillis = 10_000)

    assertEquals(1, generation.markAllDirty(), "the operator's regenerate marks what is there")
    assertTrue(generation.isDirty("preview|dark"), "and the mark is visible immediately")
    assertTrue(generation.isDirty("preview|dark"))
    assertTrue(
      generation.contains("preview|dark"),
      "and nothing is deleted — the preview keeps serving while the pass replaces it, which is " +
        "the difference between this and dropping the catalog's cache",
    )

    // And the mark survives a restart, or an operator's request would be forgotten by the next
    // roll and quietly never acted on.
    val reopened = assertNotNull(store(root).open("m3-catalog", fp, inputs(fp)))
    assertTrue(reopened.isDirty("preview|dark"), "an operator's request is not forgotten by a roll")
  }

  /**
   * The rollout case, which a timestamp comparison alone gets wrong.
   *
   * `deploy/image` rolls out zero-downtime: the outgoing replica keeps serving — and keeps
   * rendering into this same directory — while the incoming one boots. A render it writes after the
   * new build set its boundary carries a LATER timestamp, so a bare `now` boundary files an
   * old-build render as current, and the sample cannot catch it either because the sample only
   * examines what was present at open.
   */
  @Test
  fun `a render written during the rollout overlap is not mistaken for this build's work`() {
    val root = tempDir()
    val fp = "fp-a"
    val first = assertNotNull(store(root).open("m3-catalog", fp, inputs(fp)))
    first.put("before|dark", ByteArray(8) { 1 })
    ageRenders(root, "m3-catalog", fp, byMillis = 10_000)

    // A store with a REAL grace window — the helper above uses zero, which is right for the sweep
    // assertions and exactly wrong here: the overlap allowance IS the grace window.
    fun rolling() =
      ThemeCacheStore(root, maxBytes = ThemeCacheStore.DEFAULT_MAX_BYTES, graceMillis = 60_000)

    // The new build opens, setting the boundary out past the overlap.
    assertNotNull(rolling().open("m3-catalog", fp, inputs(fp).copy(toolVersion = "1.15.0")))
    // The OUTGOING replica, still live, publishes another render now — after that boundary.
    val outgoing = assertNotNull(store(root).open("m3-catalog", fp, inputs(fp)))
    outgoing.put("during|dark", ByteArray(8) { 2 })

    // A later restart of the new build inherits both, and must distrust both.
    val later =
      assertNotNull(rolling().open("m3-catalog", fp, inputs(fp).copy(toolVersion = "1.15.0")))
    assertTrue(later.isDirty("before|dark"), "written before the boundary")
    assertTrue(
      later.isDirty("during|dark"),
      "and written DURING the overlap — a bare `now` boundary would have filed this as current, " +
        "which is the stale pixel the boundary exists to catch",
    )
  }

  /**
   * The volume that cannot record the boundary.
   *
   * A cross-build open whose manifest write fails used to record the failure and carry on, and the
   * generation then re-read the PREVIOUS manifest — commonly a boundary of zero — and concluded
   * that another build's renders were its own. A five-entry sample verifies the generation and a
   * renderer change outside that sample is served for the life of the process. Unknown provenance
   * has to read as dirty, not as ours.
   */
  @Test
  fun `renders are dirty when the boundary could not be recorded`() {
    val root = tempDir()
    val fp = "fp-a"
    val first = assertNotNull(store(root).open("m3-catalog", fp, inputs(fp)))
    first.put("preview|dark", ByteArray(8) { 1 })
    first.put("preview|light", ByteArray(8) { 2 })
    ageRenders(root, "m3-catalog", fp, byMillis = 10_000)

    val dir = File(File(root, "m3-catalog"), fp)
    if (!dir.setWritable(false)) return
    try {
      // Skipped where the filesystem does not enforce it — as root, which is how this container
      // runs though CI does not. See [writable].
      if (writable(dir)) return
      val next =
        assertNotNull(store(root).open("m3-catalog", fp, inputs(fp).copy(toolVersion = "1.15.0")))
      assertEquals(
        2,
        next.dirtyCount(),
        "a volume that could not be told another build was here cannot then be believed when it " +
          "says these renders are ours",
      )
    } finally {
      dir.setWritable(true)
    }
  }

  /**
   * The boundary is a line, and a line left behind after everything crossed it re-dirties the very
   * work that crossed.
   *
   * A cross-build open dates the boundary a grace window into the FUTURE, deliberately, so the
   * outgoing replica's later writes are caught. The cost is that this build's own early renders
   * fall under it too — fine once, and a bug forever: left in the manifest, every restart
   * reclassifies them and regenerates them again.
   */
  @Test
  fun `the boundary is cleared once every render is this build's own`() {
    val root = tempDir()
    val fp = "fp-a"
    val first = assertNotNull(store(root).open("m3-catalog", fp, inputs(fp)))
    first.put("a|dark", ByteArray(8) { 1 })
    first.put("b|dark", ByteArray(8) { 2 })
    ageRenders(root, "m3-catalog", fp, byMillis = 10_000)

    val next =
      assertNotNull(store(root).open("m3-catalog", fp, inputs(fp).copy(toolVersion = "1.15.0")))
    assertEquals(2, next.dirtyCount())
    next.put("a|dark", ByteArray(8) { 3 }, replaceExisting = true)
    next.put("b|dark", ByteArray(8) { 4 }, replaceExisting = true)
    assertEquals(0, next.dirtyCount(), "converged")

    val manifest =
      kotlinx.serialization.json.Json.decodeFromString(
        GenerationInputs.serializer(),
        File(File(File(root, "m3-catalog"), fp), ThemeCacheStore.MANIFEST_NAME).readText(),
      )
    assertEquals(
      0L,
      manifest.dirtyBeforeEpochMillis,
      "and the line goes with the last dirty entry",
    )

    val reopened =
      assertNotNull(store(root).open("m3-catalog", fp, inputs(fp).copy(toolVersion = "1.15.0")))
    assertEquals(
      0,
      reopened.dirtyCount(),
      "so a restart does not regenerate work that already converged — which a future-dated " +
        "boundary left in place would do once per restart, forever",
    )
  }

  /**
   * The half of the rollout overlap a boundary cannot see.
   *
   * Classification happens once, at open. A key the incoming replica renders FIRST leaves the dirty
   * set by its own write, so the outgoing replica renaming over it afterwards is invisible however
   * the boundary is dated — memory keeps serving the right pixels until it evicts, and the read
   * then falls through to another build's bytes under a generation reporting itself converged.
   */
  @Test
  fun `a render the outgoing replica overwrote after ours goes back on the queue`() {
    val root = tempDir()
    val fp = "fp-a"
    val first = assertNotNull(store(root).open("m3-catalog", fp, inputs(fp)))
    first.put("seed|dark", ByteArray(8) { 1 })
    ageRenders(root, "m3-catalog", fp, byMillis = 10_000)

    // A real grace window — the overlap allowance IS the window, and the helper store uses zero —
    // and a clock the test can push past it, since the reconcile is deliberately deferred until
    // the overlap is over and there is no second writer left to race.
    var now = System.currentTimeMillis()
    val incoming =
      assertNotNull(
        ThemeCacheStore(
            root,
            maxBytes = ThemeCacheStore.DEFAULT_MAX_BYTES,
            graceMillis = 60_000,
            clock = { now },
          )
          .open("m3-catalog", fp, inputs(fp).copy(toolVersion = "1.15.0"))
      )
    // The incoming replica regenerates the key, inside the overlap window.
    incoming.put("seed|dark", ByteArray(8) { 2 }, replaceExisting = true)
    assertEquals(0, incoming.dirtyCount(), "clean, as far as this replica knows")

    // The OUTGOING replica, still live on the old build, renames its own copy over the top. Only
    // one render is in this generation, so the file is unambiguous without hashing the key — which
    // the test could not do anyway, the name being a one-way hash.
    val png =
      assertNotNull(
        File(File(root, "m3-catalog"), fp).listFiles()?.singleOrNull { it.name.endsWith(".png") }
      )
    png.writeBytes(ByteArray(8) { 9 })
    assertTrue(png.setLastModified(now + 5_000), "the co-replica's write carries its own timestamp")

    // Past the overlap, the incoming replica re-checks what it left behind.
    now += 120_000
    assertEquals(
      1,
      incoming.dirtyCount(),
      "a file whose timestamp is no longer the one we left is one somebody else has replaced, " +
        "and the only honest thing to do with it is render it again — the boundary cannot see " +
        "this on its own, because our own write had already taken the key off the dirty queue",
    )
  }

  /**
   * A manifest that says nothing is not a manifest saying "this build made these".
   *
   * A write interrupted mid-flight leaves exactly this: a full set of another build's PNGs beside a
   * missing or unparseable manifest. Reading that absence as "we created the generation" opens it
   * with a zero boundary, and a five-entry sample then verifies renders nobody can account for.
   */
  @Test
  fun `renders beside an unreadable manifest are of unknown ownership, so dirty`() {
    val root = tempDir()
    val fp = "fp-a"
    val first = assertNotNull(store(root).open("m3-catalog", fp, inputs(fp)))
    first.put("preview|dark", ByteArray(8) { 1 })
    first.put("preview|light", ByteArray(8) { 2 })
    ageRenders(root, "m3-catalog", fp, byMillis = 10_000)
    assertTrue(File(File(File(root, "m3-catalog"), fp), ThemeCacheStore.MANIFEST_NAME).delete())

    // Same tool version, so nothing about the BUILD says these are suspect — only the fact that
    // the volume can no longer account for them.
    val next = assertNotNull(store(root).open("m3-catalog", fp, inputs(fp)))
    assertEquals(
      2,
      next.dirtyCount(),
      "a manifest that cannot say who wrote these is not evidence that we did",
    )
  }

  /**
   * The reconcile is a way of reaching convergence, so it has to be able to finish the job.
   *
   * In the ordinary rollout — nobody overwrote anything — the dirty set empties first and the
   * at-risk set empties later, in the reconcile, with no further write to notice. Leaving the clear
   * to `put` alone strands the future-dated boundary in the manifest, which is the very thing that
   * re-dirties this build's own renders on the next restart.
   */
  @Test
  fun `convergence reached by the overlap reconcile clears the boundary too`() {
    val root = tempDir()
    val fp = "fp-a"
    val first = assertNotNull(store(root).open("m3-catalog", fp, inputs(fp)))
    first.put("a|dark", ByteArray(8) { 1 })
    ageRenders(root, "m3-catalog", fp, byMillis = 10_000)

    var now = System.currentTimeMillis()
    val incoming =
      assertNotNull(
        ThemeCacheStore(
            root,
            maxBytes = ThemeCacheStore.DEFAULT_MAX_BYTES,
            graceMillis = 60_000,
            clock = { now },
          )
          .open("m3-catalog", fp, inputs(fp).copy(toolVersion = "1.15.0"))
      )
    // Regenerated inside the overlap window, so the dirty set empties but the at-risk set does not.
    incoming.put("a|dark", ByteArray(8) { 2 }, replaceExisting = true)
    assertEquals(0, incoming.dirtyCount())
    val duringOverlap =
      kotlinx.serialization.json.Json.decodeFromString(
        GenerationInputs.serializer(),
        File(File(File(root, "m3-catalog"), fp), ThemeCacheStore.MANIFEST_NAME).readText(),
      )
    assertTrue(
      duringOverlap.dirtyBeforeEpochMillis > 0,
      "still standing while a co-replica could be writing",
    )

    // Past the window, with no further write of any kind: the reconcile is the last thing to run.
    now += 120_000
    assertEquals(0, incoming.dirtyCount())
    val settled =
      kotlinx.serialization.json.Json.decodeFromString(
        GenerationInputs.serializer(),
        File(File(File(root, "m3-catalog"), fp), ThemeCacheStore.MANIFEST_NAME).readText(),
      )
    assertEquals(
      0L,
      settled.dirtyBeforeEpochMillis,
      "nothing moved under us and the window is shut, so the line has nothing left to mark",
    )
  }

  @Test
  fun `a partly failed dirty discard keeps the quarantine rather than reporting success`() {
    val root = tempDir()
    val fp = "fp-a"
    val first = assertNotNull(store(root).open("m3-catalog", fp, inputs(fp)))
    first.put("a|dark", ByteArray(8) { 1 })
    first.put("b|dark", ByteArray(8) { 2 })
    ageRenders(root, "m3-catalog", fp, byMillis = 10_000)
    val next =
      assertNotNull(store(root).open("m3-catalog", fp, inputs(fp).copy(toolVersion = "1.15.0")))
    assertEquals(2, next.dirtyCount())

    // Make one deletion fail by replacing the PNG with a non-empty DIRECTORY, which `File.delete`
    // refuses. Crude, but it is the one filesystem failure a test can stage portably.
    val dir = File(File(root, "m3-catalog"), fp)
    val victim = dir.listFiles()!!.first { it.name.endsWith(".png") }
    assertTrue(victim.delete())
    assertTrue(File(victim, "occupied").let { it.mkdirs() && File(it, "x").createNewFile() })

    assertEquals(
      -1,
      next.discardDirty(),
      "a partial discard must report failure: verifySample reads any success as licence to lift " +
        "the read quarantine, and a PNG left behind would go from proved-stale to served",
    )
  }

  @Test
  fun `evictAll discards every generation, including one the sweep would spare`() {
    val root = tempDir()
    // A real store, so the grace window is in force — this is the case `sweep` cannot serve, and
    // therefore the whole reason `evictAll` exists.
    val guarded = ThemeCacheStore(root, maxBytes = ThemeCacheStore.DEFAULT_MAX_BYTES)
    val generation = assertNotNull(guarded.open("m3-catalog", "fp-a", inputs("fp-a")))
    generation.put("preview|dark", ByteArray(8) { 1 })

    assertEquals(
      0,
      guarded.sweep(live = emptySet()).deletedGenerations,
      "the grace window spares a freshly written generation, which is exactly the problem",
    )
    assertEquals(1, guarded.evictAll(), "evict takes it anyway")
    assertEquals(
      false,
      File(File(root, "m3-catalog"), "fp-a").exists(),
      "and the bytes are gone from the volume",
    )
  }

  @Test
  fun `an eviction makes the outgoing replica's repopulated renders dirty`() {
    // The rollout this store was built for is zero-downtime: the outgoing replica keeps serving,
    // and keeps writing PNGs, against the same volume while the incoming one boots and evicts.
    // `evictAll` runs before this process opens anything, so it cannot race THIS process — which
    // was the whole of the old reasoning, and is a single-process argument about a volume that is
    // not single-process. Everything the old replica publishes in that window is precisely the
    // pixels the eviction was meant to destroy, landing after the deletion with fresh timestamps.
    val root = tempDir()
    val grace = 60 * 60_000L

    val outgoing = ThemeCacheStore(root, graceMillis = grace)
    val old = assertNotNull(outgoing.open("m3-catalog", "fp-a", inputs("fp-a")))
    old.put("preview|dark", ByteArray(8) { 1 })

    // The incoming replica evicts. SAME build: an operator who knows the pixels moved does not need
    // a release to say so, and that is the case a version comparison alone cannot see — the early
    // return would fire on the matching version and keep whatever boundary was already recorded.
    val incoming = ThemeCacheStore(root, graceMillis = grace)
    assertEquals(1, incoming.evictAll())
    assertTrue(File(root, ThemeCacheStore.EVICTED_NAME).isFile, "the boundary is on the volume")

    // The old replica, still serving, repopulates the same fingerprint after the deletion.
    val repopulated = assertNotNull(outgoing.open("m3-catalog", "fp-a", inputs("fp-a")))
    repopulated.put("preview|dark", ByteArray(8) { 1 })

    // The incoming replica now opens what looks like a fresh generation full of fresh timestamps.
    val adopted = assertNotNull(incoming.open("m3-catalog", "fp-a", inputs("fp-a")))
    assertTrue(
      adopted.isDirty("preview|dark"),
      "a render written after an eviction by a writer the eviction could not stop is not trusted",
    )
    assertTrue(adopted.contains("preview|dark"), "dirty is not absent — it stays warm meanwhile")
  }

  @Test
  fun `renders written past the eviction's grace window are clean again`() {
    // The boundary is a window, not a permanent condemnation of the volume. Once every writer that
    // could predate the eviction has had the rollout's grace to stop, what lands next is this
    // build's own work; re-rendering it forever would be a treadmill rather than a safeguard.
    //
    // An eviction stamped in the past with no grace, so every real render timestamp is past it.
    val root = tempDir()
    val store = ThemeCacheStore(root, graceMillis = 0, clock = { 1_000_000L })
    assertEquals(0, store.evictAll())

    val generation = assertNotNull(store.open("m3-catalog", "fp-a", inputs("fp-a")))
    generation.put("preview|dark", ByteArray(8) { 1 })

    assertFalse(generation.isDirty("preview|dark"))
    assertEquals(0, generation.dirtyCount())
  }

  @Test
  fun `a store that was never evicted carries no boundary`() {
    val root = tempDir()
    val store = ThemeCacheStore(root, graceMillis = 60 * 60_000)
    val generation = assertNotNull(store.open("m3-catalog", "fp-a", inputs("fp-a")))
    generation.put("preview|dark", ByteArray(8) { 1 })

    assertFalse(generation.isDirty("preview|dark"), "nothing to be on the wrong side of")
    assertFalse(File(root, ThemeCacheStore.EVICTED_NAME).exists())
  }

  @Test
  fun `the eviction marker survives the sweep and a later eviction moves it forward`() {
    // It lives at the store root beside the system directories, and both walks filter to
    // directories. A marker a sweep could reclaim would quietly reopen the window it closes.
    val root = tempDir()
    var now = 1_000_000L
    val store = ThemeCacheStore(root, graceMillis = 0, clock = { now })
    assertNotNull(store.open("m3-catalog", "fp-a", inputs("fp-a")))
    store.evictAll()
    val marker = File(root, ThemeCacheStore.EVICTED_NAME)
    assertEquals("1000000", marker.readText())

    now += 1_000
    store.sweep(live = emptySet())
    assertEquals("1000000", marker.readText(), "a sweep must not reclaim the boundary")

    now += 1_000
    store.evictAll()
    assertEquals("1002000", marker.readText(), "a second eviction moves the boundary forward")
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

  // ---- renderer identity ----------------------------------------------------------------------

  @Test
  fun `a bumped JVM is a different generation, with the classpath untouched`() {
    // The case the tool version used to stand proxy for and failed open on: a base-image bump moves
    // the renderer while every hashed catalog input holds still. Before this was keyed on, the only
    // thing standing between that and a wrong pixel was a five-entry sample.
    val dir = tempDir()
    val classpath = listOf(jar(dir, "catalog.jar", "CLASSES"))

    fun withJvm(version: String) =
      ThemeCacheFingerprint.of(
        classpath = classpath,
        variant = "desktop",
        renderConfig = "density=2",
        renderer =
          ThemeCacheFingerprint.rendererIdentity(
            systemProperties = mapOf("java.vm.vendor" to "Eclipse", "java.vm.version" to version),
            fontRoots = emptyList(),
            libraryRoots = emptyList(),
          ),
      )

    assertNotEquals(withJvm("21.0.12+8"), withJvm("21.0.13+11"))
    assertEquals(withJvm("21.0.12+8"), withJvm("21.0.12+8"), "an unchanged JVM must not re-warm")
  }

  @Test
  fun `a swapped system font is a different generation`() {
    val fonts = tempDir()
    val dir = tempDir()
    val classpath = listOf(jar(dir, "catalog.jar", "CLASSES"))
    jar(fonts, "truetype/Roboto-Regular.ttf", "GLYPHS")

    fun now() =
      ThemeCacheFingerprint.of(
        classpath = classpath,
        variant = "desktop",
        renderConfig = "density=2",
        renderer =
          ThemeCacheFingerprint.rendererIdentity(
            systemProperties = emptyMap(),
            fontRoots = listOf(fonts),
            libraryRoots = emptyList(),
          ),
      )

    val before = now()
    // Size, not content: the inventory stats rather than reads, because a fontconfig image carries
    // hundreds of megabytes here and this runs on the catalog-load path.
    jar(fonts, "truetype/Roboto-Regular.ttf", "DIFFERENT GLYPHS")
    assertNotEquals(before, now(), "a font package swap must not read the old renderer's pixels")

    val after = now()
    jar(fonts, "truetype/NotoSans-Regular.ttf", "MORE")
    assertNotEquals(after, now(), "an ADDED font changes what fontconfig will fall back to")
  }

  @Test
  fun `a bumped rasteriser library is a different generation, and unrelated libraries are not`() {
    // freetype decides how a glyph is rasterised on the Android path with nothing else moving at
    // all. The filter is what keeps this from being a hash of /usr/lib.
    val libs = tempDir()
    val dir = tempDir()
    val classpath = listOf(jar(dir, "catalog.jar", "CLASSES"))
    jar(libs, "libfreetype.so.6", "FT")
    jar(libs, "libcurl.so.4", "UNRELATED")
    // The versioned real file behind the soname link. Matched on the soname alone, so a package
    // upgrade that renames this does not move the fingerprint a second time.
    jar(libs, "libfreetype.so.6.18.3", "FT")

    fun now() =
      ThemeCacheFingerprint.of(
        classpath = classpath,
        variant = "desktop",
        renderConfig = "density=2",
        renderer =
          ThemeCacheFingerprint.rendererIdentity(
            systemProperties = emptyMap(),
            fontRoots = emptyList(),
            libraryRoots = listOf(libs),
          ),
      )

    val before = now()
    jar(libs, "libcurl.so.4", "A DIFFERENT UNRELATED LIBRARY")
    assertEquals(before, now(), "a library that cannot touch a pixel must not orphan the cache")

    jar(libs, "libfreetype.so.6", "FT UPGRADED")
    assertNotEquals(before, now())
  }

  @Test
  fun `a missing font root is absence, not a refusal to persist`() {
    // Unlike a classpath entry, a font root that is not there is the ordinary state of most
    // machines. Declining to persist on a developer laptop would be a bug, not caution.
    val dir = tempDir()
    val classpath = listOf(jar(dir, "catalog.jar", "CLASSES"))

    assertNotNull(
      ThemeCacheFingerprint.of(
        classpath = classpath,
        variant = "desktop",
        renderConfig = "density=2",
        renderer =
          ThemeCacheFingerprint.rendererIdentity(
            systemProperties = emptyMap(),
            fontRoots = listOf(File(dir, "no-such-font-root")),
            libraryRoots = listOf(File(dir, "no-such-lib-root")),
          ),
      )
    )
  }

  @Test
  fun `the process renderer identity is stable across calls`() {
    // It walks the real font roots, so it is cached; a default that changed between two bundles of
    // one multi-module catalog would give the catalog a fingerprint per module load.
    assertEquals(
      ThemeCacheFingerprint.currentRendererIdentity,
      ThemeCacheFingerprint.currentRendererIdentity,
    )
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
  fun `font cache contents are fingerprinted without hashing their staging path`() {
    val dir = tempDir()
    val framework = jar(dir, "compose-runtime.jar", "DEPS")
    val fonts = File(dir, "fonts").apply { mkdirs() }
    jar(fonts, "Roboto.woff2", "revision-1")

    fun fingerprintNow() =
      fingerprint(
        ThemeCacheFingerprint.renderedClasspath(
          classpath = listOf(framework.absolutePath),
          systemProperties = mapOf("composeai.fonts.cacheDir" to fonts.absolutePath),
        )
      )

    val before = fingerprintNow()
    jar(fonts, "Roboto.woff2", "revision-2")

    assertNotEquals(before, fingerprintNow(), "different glyph bytes must be a new generation")
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

  @Test
  fun `render-affecting system properties are part of the generation, but their paths are not`() {
    // Excluding the whole system-property map was the same mistake as excluding the user classpath:
    // most of it is staging paths that churn every load, but `composeai.fonts.offline` and the
    // Android launch's `robolectric.*` settings genuinely change what the renderer produces —
    // offline font resolution substitutes fallback glyphs for downloaded faces.
    val online = mapOf("composeai.fonts.offline" to "false")
    val offline = mapOf("composeai.fonts.offline" to "true")

    assertNotEquals(
      ThemeCacheFingerprint.renderConfig(online, emptyList()),
      ThemeCacheFingerprint.renderConfig(offline, emptyList()),
    )

    // A path-valued property is churn, not configuration: its directory moves every load, and where
    // its contents matter they are hashed as classpath entries instead.
    val stagedHere = mapOf("composeai.fonts.cacheDir" to "${File.separator}tmp${File.separator}a")
    val stagedThere = mapOf("composeai.fonts.cacheDir" to "${File.separator}tmp${File.separator}b")
    assertEquals(
      ThemeCacheFingerprint.renderConfig(stagedHere, emptyList()),
      ThemeCacheFingerprint.renderConfig(stagedThere, emptyList()),
    )
  }

  @Test
  fun `render config ignores the order settings arrive in`() {
    val one =
      ThemeCacheFingerprint.renderConfig(mapOf("a" to "1", "b" to "2"), listOf("-Xmx1g", "-Xss2m"))
    val two =
      ThemeCacheFingerprint.renderConfig(mapOf("b" to "2", "a" to "1"), listOf("-Xss2m", "-Xmx1g"))

    assertEquals(one, two)
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

  /**
   * Backdate every render in a generation, so a boundary set "now" is unambiguously after them.
   *
   * The dirty boundary is compared against each PNG's filesystem timestamp, and a test writes its
   * fixtures and opens the next generation inside the same millisecond — which a strict comparison
   * correctly reads as "not older". Production never has that problem: the boundary is set when a
   * different build opens the generation, a restart later than the renders it inherits. Aging the
   * fixtures says exactly that, where skewing the store's clock would also distort the writes the
   * test then makes THROUGH it, leaving regenerated entries permanently dirty.
   *
   * The strict comparison is deliberate: a loose one would let an entry written in the same tick as
   * the boundary stay dirty through its own regeneration, and re-render forever.
   */
  private fun ageRenders(root: File, system: String, fingerprint: String, byMillis: Long) {
    val at = System.currentTimeMillis() - byMillis
    val dir = File(File(root, system), fingerprint)
    val pngs = dir.listFiles()?.filter { it.name.endsWith(".png") }.orEmpty()
    assertTrue(pngs.isNotEmpty(), "nothing to age in $dir — the fixture did not persist")
    pngs.forEach { assertTrue(it.setLastModified(at), "could not backdate ${it.name}") }
  }

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

  @Test
  fun `verification cannot be satisfied by renders this process just made`() {
    // On a partly warmed restart, foreground traffic persists missing keys before the idle
    // verification task runs. Sampling those would let five fresh renders "verify" a generation
    // whose adopted bytes were never looked at — the cache vouching for itself.
    val root = tempDir()
    val fp = "a".repeat(64)
    store(root).open("m3-catalog", fp, inputs(fp))!!.put("adopted", byteArrayOf(1))

    val cache = CatalogThemeCache(persistence = store(root).open("m3-catalog", fp, inputs(fp))!!)
    cache.configurePersistable(listOf("adopted", "fresh-a", "fresh-b"))
    cache.put("fresh-a", byteArrayOf(2))
    cache.put("fresh-b", byteArrayOf(3))

    // A renderer that answers only for this process's own keys establishes nothing.
    val outcome = cache.verifySample { key -> if (key == "adopted") null else byteArrayOf(9) }
    assertEquals(CatalogThemeCache.VerifyOutcome.NO_EVIDENCE, outcome)
    assertNull(cache.get("adopted"), "the adopted entry stays quarantined")
  }

  @Test
  fun `a fresh render replaces the quarantined copy on disk`() {
    // While quarantined a foreground request misses the adopted copy and renders fresh bytes. If
    // the
    // stale PNG stayed on disk, verification passing on OTHER keys would expose it again the moment
    // the fresh copy fell out of the memory tier.
    val root = tempDir()
    val fp = "a".repeat(64)
    store(root).open("m3-catalog", fp, inputs(fp))!!.put("one", byteArrayOf(1))

    val cache = CatalogThemeCache(persistence = store(root).open("m3-catalog", fp, inputs(fp))!!)
    cache.configurePersistable(listOf("one"))
    cache.put("one", byteArrayOf(42))

    // The durable copy is the fresh one, as seen by a later process.
    assertContentEquals(
      byteArrayOf(42),
      store(root).open("m3-catalog", fp, inputs(fp))!!.get("one"),
    )
  }

  @Test
  fun `a discard that cannot delete everything reports failure`() {
    // A partial discard leaves stale PNGs under a fingerprint this process has already decided it
    // cannot trust, and the next restart adopts them again — reproducing the mismatch indefinitely.
    val root = tempDir()
    val fp = "a".repeat(64)
    val generation = store(root).open("m3-catalog", fp, inputs(fp))!!
    generation.put("one", byteArrayOf(1))
    val dir = File(File(root, "m3-catalog"), fp)

    assertTrue(generation.discard(), "an ordinary discard succeeds")

    // Now make the directory unwritable so the next discard cannot remove its contents.
    generation.put("two", byteArrayOf(2))
    if (!dir.setWritable(false)) return
    try {
      // Skipped where the filesystem does not actually enforce it: as root — which is how this
      // container runs, though CI does not — a read-only directory still accepts deletes, and the
      // assertion would pass without testing anything.
      if (writable(dir)) return
      assertFalse(generation.discard(), "an incomplete discard must not report success")
    } finally {
      dir.setWritable(true)
    }
  }

  /**
   * Whether [dir] genuinely accepts new files.
   *
   * `createNewFile` **throws** in a read-only directory rather than returning false, which is
   * exactly how the first version of this test passed locally (as root, where the throw never
   * happened) and failed on CI.
   */
  private fun writable(dir: File): Boolean = runCatching {
    val probe = File(dir, "probe")
    probe.createNewFile().also { created -> if (created) probe.delete() }
  }
    .getOrDefault(false)

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
    // Written by one process...
    store(root).open("m3-catalog", fp, inputs(fp))!!.also {
      it.put("one", byteArrayOf(1))
      it.put("two", byteArrayOf(2))
    }
    // ...and adopted by the next, which is the only case verification speaks to.
    val generation = store(root).open("m3-catalog", fp, inputs(fp))!!
    val cache = CatalogThemeCache(persistence = generation)
    cache.configureTargets(listOf("one", "two"))

    val outcome = cache.verifySample { byteArrayOf(99) }

    assertEquals(CatalogThemeCache.VerifyOutcome.MISMATCH, outcome)
    assertEquals(0, cache.snapshot().cached, "a failed verification leaves nothing behind")
    assertNull(cache.get("one"))
  }

  /**
   * A discard the write lock refuses is not a discard, and must not be reported as one.
   *
   * `verifySample` used to set `persistenceTrusted` on any mismatch, ignoring whether `discard`
   * actually succeeded — so PNGs it had just proved wrong stayed on disk and were immediately
   * servable as verified. Honouring the result fixed that, but returning plain `MISMATCH` was still
   * wrong one level up: `ServeCatalogLiveHost` latches `persistenceVerified` for MISMATCH, so the
   * retry this case depends on would never come, and the entries would stay withheld from reads
   * while `contains` kept the optimizer from re-warming them.
   */
  @Test
  fun `a discard blocked by the write lock is not reported as settled`() {
    val root = tempDir()
    val fp = "b".repeat(64)
    store(root).open("m3-catalog", fp, inputs(fp))!!.also {
      it.put("one", byteArrayOf(1))
      it.put("two", byteArrayOf(2))
    }
    val generation = store(root).open("m3-catalog", fp, inputs(fp))!!
    val cache = CatalogThemeCache(persistence = generation)
    cache.configureTargets(listOf("one", "two"))

    // Hold the generation's write lock from another channel in this process, the way a foreground
    // render publishing into the same generation does.
    val lockFile = File(File(File(root, "m3-catalog"), fp), ".write.lock")
    RandomAccessFile(lockFile, "rw").use { raf ->
      raf.channel.lock().use {
        val outcome = cache.verifySample { byteArrayOf(99) }

        assertEquals(CatalogThemeCache.VerifyOutcome.MISMATCH_UNDISCARDED, outcome)
        assertFalse(outcome.settled, "an undiscarded mismatch leaves verification unfinished")
        // The suspect entries are withheld rather than served...
        assertNull(cache.get("one"))
        // ...and the bytes are still there, which is exactly why the caller must ask again.
        val generationDir = File(File(root, "m3-catalog"), fp)
        assertTrue(
          generationDir.listFiles().orEmpty().any { it.name.endsWith(".png") },
          "the suspect PNGs are still on disk, which is why the caller has to ask again",
        )
      }
    }
  }

  @Test
  fun `verification keeps a generation whose renders still match`() {
    val root = tempDir()
    val fp = "a".repeat(64)
    store(root).open("m3-catalog", fp, inputs(fp))!!.put("one", byteArrayOf(7))
    val generation = store(root).open("m3-catalog", fp, inputs(fp))!!
    val cache = CatalogThemeCache(persistence = generation)
    cache.configureTargets(listOf("one"))

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
    store(root).open("m3-catalog", fp, inputs(fp))!!.put("one", byteArrayOf(7))
    val generation = store(root).open("m3-catalog", fp, inputs(fp))!!
    val cache = CatalogThemeCache(persistence = generation)
    cache.configureTargets(listOf("one"))

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
    store(root).open("m3-catalog", fp, inputs(fp))!!.put("one", byteArrayOf(1))
    val generation = store(root).open("m3-catalog", fp, inputs(fp))!!
    val cache = CatalogThemeCache(persistence = generation)
    cache.configureTargets(listOf("one"))

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

  @Test
  fun `read counters separate a cache that is used from one that is only filled`() {
    // A cache that fills and a cache that fills and is never read report identical occupancy, and
    // with a disk tier the second costs I/O on every render to buy nothing. These are the counters
    // that tell them apart.
    val root = tempDir()
    val fp = "a".repeat(64)
    store(root).open("m3-catalog", fp, inputs(fp))!!.put("warm", byteArrayOf(1))

    val cache = CatalogThemeCache(persistence = store(root).open("m3-catalog", fp, inputs(fp))!!)
    cache.configurePersistable(listOf("warm", "fresh"))
    // Settle the quarantine so the adopted entry is readable — see the withheld test below for the
    // window before this happens.
    cache.verifySample { byteArrayOf(1) }

    assertNull(cache.get("cold"), "never rendered, in neither tier")
    assertContentEquals(byteArrayOf(1), cache.get("warm")) // disk, then promoted to memory
    assertContentEquals(byteArrayOf(1), cache.get("warm")) // memory

    val snapshot = cache.renderCacheSnapshot()
    assertEquals(1, snapshot.diskHits)
    assertEquals(1, snapshot.memoryHits)
    assertEquals(1, snapshot.misses)
    assertEquals(2.0 / 3, snapshot.hitRate)
  }

  @Test
  fun `a read withheld by the quarantine is not counted as a miss`() {
    // Withheld reads can outnumber real misses during a cold start, and folding them into `misses`
    // would report the cache as failing at exactly the moment it is being deliberately careful.
    val root = tempDir()
    val fp = "a".repeat(64)
    store(root).open("m3-catalog", fp, inputs(fp))!!.put("one", byteArrayOf(1))

    val cache = CatalogThemeCache(persistence = store(root).open("m3-catalog", fp, inputs(fp))!!)
    cache.configurePersistable(listOf("one"))

    assertNull(cache.get("one"))

    val snapshot = cache.renderCacheSnapshot()
    assertEquals(1, snapshot.withheld)
    assertEquals(0, snapshot.misses)
    assertNull(snapshot.hitRate, "no read has been answered either way yet")
  }

  @Test
  fun `the disk tier reports what it adopted, so a key that moved is visible`() {
    // `adopted` is the only evidence that persistence carried anything across a process boundary.
    // A restart onto a fingerprint that moved adopts nothing and writes everything again, which is
    // indistinguishable from a working cache in every other counter.
    val root = tempDir()
    val stable = "a".repeat(64)
    val moved = "b".repeat(64)

    val first =
      CatalogThemeCache(persistence = store(root).open("m3-catalog", stable, inputs(stable))!!)
    first.configurePersistable(listOf("one"))
    first.put("one", byteArrayOf(1))
    assertEquals(0, first.renderCacheSnapshot().persisted?.adopted)
    assertEquals(1, first.renderCacheSnapshot().persisted?.writes)

    val restarted =
      CatalogThemeCache(persistence = store(root).open("m3-catalog", stable, inputs(stable))!!)
    assertEquals(1, restarted.renderCacheSnapshot().persisted?.adopted)
    assertEquals(stable, restarted.renderCacheSnapshot().persisted?.fingerprint)

    val churned =
      CatalogThemeCache(persistence = store(root).open("m3-catalog", moved, inputs(moved))!!)
    assertEquals(
      0,
      churned.renderCacheSnapshot().persisted?.adopted,
      "a fingerprint that moved adopts nothing — the case worth being able to see",
    )
  }

  @Test
  fun `a catalog that fell back to memory-only says why`() {
    // Every reason a catalog loses its disk tier used to look identical to running the server
    // without one, and all of them are permanent for the life of the host.
    val silent = CatalogThemeCache()
    assertNull(silent.renderCacheSnapshot().persistenceOff)

    val explained = CatalogThemeCache(persistenceOffReason = "launch descriptor unreadable")
    assertEquals("launch descriptor unreadable", explained.renderCacheSnapshot().persistenceOff)
  }

  @Test
  fun `the census counts generations per system, so fingerprint churn is visible`() {
    // Three generations for one catalog on a box that has only ever served it one way is churn, and
    // churn reports itself as success in every other counter: writes climb, the volume fills, and
    // nothing is ever adopted.
    val root = tempDir()
    var now = 1_000_000L
    val churning = ThemeCacheStore(root, graceMillis = 60 * 60_000, clock = { now })
    for (fp in listOf("a", "b", "c")) {
      churning.open("m3-catalog", fp.repeat(64), inputs(fp.repeat(64)))!!.put("k", ByteArray(8))
    }
    churning.open("meshcore", "d".repeat(64), inputs("d".repeat(64)))!!.put("k", ByteArray(8))

    churning.sweep(
      setOf(ThemeCacheStore.GenerationId("m3-catalog", "c".repeat(64))),
      onlySystems = setOf("m3-catalog", "meshcore"),
    )

    val census = churning.snapshot().generationsBySystem
    assertEquals(
      3,
      census["m3-catalog"],
      "all three spared by the grace window, and all three real",
    )
    assertEquals(1, census["meshcore"])
  }
}

private fun assertContentEquals(expected: ByteArray, actual: ByteArray?) {
  kotlin.test.assertNotNull(actual)
  kotlin.test.assertTrue(expected.contentEquals(actual), "byte contents differ")
}
