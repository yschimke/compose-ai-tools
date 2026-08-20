package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for [CatalogBlobPool] — the content-addressed store that lets a catalog's heavy bytes
 * (the executable `liveBundle`, its per-preview splits, the externalised resource pool) outlive
 * both a reload and, given a durable root, the process.
 *
 * The load-bearing properties are that a hit is only ever returned once its bytes hash back to the
 * name it is filed under, and that a pool reopened over the same directory reads what the previous
 * one wrote. Everything else here guards those two.
 */
class CatalogBlobPoolTest {

  private fun root(): File =
    Files.createTempDirectory("blob-pool").toFile().also { it.deleteOnExit() }

  private fun sha(bytes: ByteArray) = CatalogBlobPool.sha256Hex(bytes)

  @Test
  fun `a content-addressed blob is fetched once and served from disk after that`() {
    val pool = CatalogBlobPool(root())
    val bytes = "a font".toByteArray()
    val fetches = AtomicLong()
    val fetch = {
      fetches.incrementAndGet()
      bytes
    }

    val first = assertNotNull(pool.contentAddressed(sha(bytes), bytes.size.toLong(), fetch))
    val second = assertNotNull(pool.contentAddressed(sha(bytes), bytes.size.toLong(), fetch))

    assertContentEquals(bytes, first.readBytes())
    assertEquals(first, second)
    assertEquals(1, fetches.get(), "the second read must not go back to the branch")
    assertEquals(1, pool.snapshot().hits)
    assertEquals(1, pool.snapshot().misses)
  }

  @Test
  fun `bytes that do not hash to the declared digest are refused`() {
    // Fail-closed: the declared sha256 is the only thing that makes a fetched classpath entry safe
    // to hand to a classloader, so bytes that do not match it are not merely uncached — they are
    // not returned at all.
    val pool = CatalogBlobPool(root())
    val declared = sha("what the manifest declared".toByteArray())

    val blob = pool.contentAddressed(declared, 5) { "other".toByteArray() }

    assertNull(blob)
    assertFalse(pool.contentFile(declared).exists())
  }

  @Test
  fun `a corrupt cached entry is refetched rather than trusted by size`() {
    // The whole point of a content-addressed store: a same-length but wrong-content entry (a
    // partial write, a disk fault) must never be served. Verifying on read is what catches it.
    val root = root()
    val pool = CatalogBlobPool(root)
    val bytes = ByteArray(64) { 7 }
    val digest = sha(bytes)
    pool.contentFile(digest).apply {
      parentFile.mkdirs()
      writeBytes(ByteArray(64) { 0 })
    }
    val fetches = AtomicLong()

    val blob =
      assertNotNull(
        pool.contentAddressed(digest, 64) {
          fetches.incrementAndGet()
          bytes
        }
      )

    assertContentEquals(bytes, blob.readBytes())
    assertEquals(1, fetches.get())
    assertEquals(1, pool.snapshot().corrupt)
  }

  @Test
  fun `a keyed blob is produced once and read back by key`() {
    val pool = CatalogBlobPool(root())
    val url = "https://raw.githubusercontent.com/o/r/$COMMIT/bundle/app-bundle.png"
    val produced = AtomicLong()
    val produce = { dest: File ->
      produced.incrementAndGet()
      dest.writeBytes("bundle bytes".toByteArray())
      true
    }

    val first = assertNotNull(pool.keyed(url, produce))
    val second = assertNotNull(pool.keyed(url, produce))

    assertEquals(first, second)
    assertContentEquals("bundle bytes".toByteArray(), second.readBytes())
    assertEquals(1, produced.get())
  }

  @Test
  fun `a keyed blob written by one pool is read by the next over the same root`() {
    // The restart case, stated directly: a rolled container is a new process over the same volume,
    // and the point of the whole feature is that it does not pull the bundle again.
    val root = root()
    val url = "https://raw.githubusercontent.com/o/r/$COMMIT/bundle/app-bundle.png"
    val bytes = "carried over".toByteArray()
    assertNotNull(
      CatalogBlobPool(root).keyed(url) { dest ->
        dest.writeBytes(bytes)
        true
      }
    )

    val reopened = CatalogBlobPool(root)
    val produced = AtomicLong()
    val blob =
      assertNotNull(
        reopened.keyed(url) { dest ->
          produced.incrementAndGet()
          dest.writeBytes(bytes)
          true
        }
      )

    assertContentEquals(bytes, blob.readBytes())
    assertEquals(0, produced.get(), "a restarted process must read, not re-produce")
    assertEquals(1, reopened.snapshot().hits)
  }

  @Test
  fun `a producer that fails leaves nothing behind and does not poison the key`() {
    val pool = CatalogBlobPool(root())
    val url = "https://raw.githubusercontent.com/o/r/$COMMIT/bundle/missing.png"

    assertNull(pool.keyed(url) { false })
    assertFalse(pool.holds(url))

    // The next attempt is free to succeed — a miss is not remembered, which is what makes a
    // transient branch blip self-heal.
    val blob =
      assertNotNull(
        pool.keyed(url) { dest ->
          dest.writeBytes("landed".toByteArray())
          true
        }
      )
    assertContentEquals("landed".toByteArray(), blob.readBytes())
    assertTrue(pool.holds(url))
  }

  @Test
  fun `a pointer whose blob was reclaimed re-produces instead of returning a missing file`() {
    val pool = CatalogBlobPool(root())
    val url = "https://raw.githubusercontent.com/o/r/$COMMIT/bundle/app-bundle.png"
    val bytes = "bundle".toByteArray()
    assertNotNull(
      pool.keyed(url) { dest ->
        dest.writeBytes(bytes)
        true
      }
    )
    assertTrue(pool.contentFile(sha(bytes)).delete())

    assertFalse(pool.holds(url))
    val produced = AtomicLong()
    val blob =
      assertNotNull(
        pool.keyed(url) { dest ->
          produced.incrementAndGet()
          dest.writeBytes(bytes)
          true
        }
      )

    assertEquals(1, produced.get())
    assertContentEquals(bytes, blob.readBytes())
  }

  @Test
  fun `both addressing modes share one blob space`() {
    // A bundle fetched by URL and the same bytes declared by sha are one file, because the name is
    // the digest either way. Two spaces would double the disk for no gain.
    val pool = CatalogBlobPool(root())
    val bytes = "shared".toByteArray()
    val url = "https://raw.githubusercontent.com/o/r/$COMMIT/bundle/app-bundle.png"
    val keyed =
      assertNotNull(
        pool.keyed(url) { dest ->
          dest.writeBytes(bytes)
          true
        }
      )

    val addressed =
      assertNotNull(pool.contentAddressed(sha(bytes), bytes.size.toLong()) { error("no fetch") })

    assertEquals(keyed, addressed)
    assertEquals(1, pool.snapshot().blobs)
  }

  @Test
  fun `written bytes are read back by key and a stranger key is a miss`() {
    val pool = CatalogBlobPool(root())
    val url = "https://raw.githubusercontent.com/o/r/$COMMIT/images/button/ideal.png"
    val bytes = "a baked png".toByteArray()

    assertNull(pool.read(url), "nothing was written yet")
    pool.write(url, bytes)

    assertContentEquals(bytes, assertNotNull(pool.read(url)))
    assertNull(pool.read("https://raw.githubusercontent.com/o/r/$COMMIT/images/other.png"))
  }

  @Test
  fun `a read whose blob was corrupted on disk is a miss, not wrong bytes`() {
    // Same guarantee the produce-once lane gets, on the lane that answers request-path reads: the
    // blob's name is its digest, so bytes that no longer hash to it are dropped rather than served.
    val pool = CatalogBlobPool(root())
    val url = "https://raw.githubusercontent.com/o/r/$COMMIT/images/button/ideal.png"
    val bytes = "a baked png".toByteArray()
    pool.write(url, bytes)
    pool.contentFile(sha(bytes)).writeBytes("tampered".toByteArray())

    assertNull(pool.read(url))
    assertEquals(1, pool.snapshot().corrupt)
  }

  @Test
  fun `a written blob survives into a pool reopened over the same root`() {
    val root = root()
    val url = "https://raw.githubusercontent.com/o/r/$COMMIT/images/button/ideal.png"
    val bytes = "carried over".toByteArray()
    CatalogBlobPool(root).write(url, bytes)

    assertContentEquals(bytes, assertNotNull(CatalogBlobPool(root).read(url)))
  }

  @Test
  fun `a hit does not re-stamp a blob that was already touched recently`() {
    // Once the small-asset lane reads through this pool, a touch per hit is a metadata write on the
    // request path. Re-stamping is throttled to a resolution the sweeper's hour-wide grace window
    // cannot tell the difference at.
    val now = AtomicLong(1_000_000L)
    val pool = CatalogBlobPool(root(), clock = { now.get() })
    val url = "https://raw.githubusercontent.com/o/r/$COMMIT/images/button/ideal.png"
    val bytes = "a baked png".toByteArray()
    pool.write(url, bytes)
    val blob = pool.contentFile(sha(bytes))
    val stamped = blob.lastModified()

    now.addAndGet(CatalogBlobPool.TOUCH_INTERVAL_MILLIS / 2)
    assertNotNull(pool.read(url))
    assertEquals(stamped, blob.lastModified(), "a recent blob is not re-stamped")

    now.addAndGet(CatalogBlobPool.TOUCH_INTERVAL_MILLIS)
    assertNotNull(pool.read(url))
    assertEquals(now.get(), blob.lastModified(), "once it is stale enough, the hit refreshes it")
  }

  @Test
  fun `re-keying bytes that are already held refreshes them against the sweeper`() {
    // The republish case, and the common one: a regenerated catalog carries mostly byte-identical
    // assets at a NEW commit, so every unchanged asset's fresh URL dedupes onto the blob already
    // here. Without a stamp that blob keeps the time it was FIRST written, and the next sweep
    // evicts precisely the assets that are current.
    val now = AtomicLong(1_000_000L)
    val pool = CatalogBlobPool(root(), maxBytes = 0, graceMillis = 60_000, clock = { now.get() })
    val bytes = "an unchanged asset".toByteArray()
    val old = "https://raw.githubusercontent.com/o/r/${"a".repeat(40)}/images/button.png"
    val fresh = "https://raw.githubusercontent.com/o/r/${"b".repeat(40)}/images/button.png"
    pool.write(old, bytes)

    // Long enough that the blob's original stamp is outside the grace window…
    now.addAndGet(120_000)
    // …then the same bytes are admitted under the new revision's URL.
    pool.write(fresh, bytes)
    val snapshot = pool.sweep()

    assertEquals(0, snapshot.evicted, "a blob just re-admitted under a new key is not stale")
    assertContentEquals(bytes, assertNotNull(pool.read(fresh)))
  }

  @Test
  fun `occupancy is published by the last census, not measured per read`() {
    // /status.json is polled, so a snapshot must not walk the pool. Proven by changing the
    // filesystem behind the pool's back: a reported count that did not move is a reported count
    // that was not measured, and the next sweep is what corrects it.
    val pool = CatalogBlobPool(root(), graceMillis = 0)
    val bytes = "a baked png".toByteArray()
    pool.write("https://raw.githubusercontent.com/o/r/$COMMIT/images/a.png", bytes)
    assertEquals(1, pool.snapshot().blobs, "a write advances the published census")

    assertTrue(pool.contentFile(sha(bytes)).delete())

    assertEquals(1, pool.snapshot().blobs, "still reporting what it last measured")
    assertEquals(0, pool.sweep().blobs, "the sweep is what re-measures")
    assertEquals(0, pool.snapshot().blobs)
  }

  @Test
  fun `clearing counts blobs as evicted and not the pointers naming them`() {
    // Deduplication means many keys can name one blob. Counting pointers too would let a clear
    // report more reclaimed than ever existed, in the metric an operator reads to check it worked.
    val pool = CatalogBlobPool(root())
    val bytes = "one shared asset".toByteArray()
    pool.write("https://raw.githubusercontent.com/o/r/${"a".repeat(40)}/images/a.png", bytes)
    pool.write("https://raw.githubusercontent.com/o/r/${"b".repeat(40)}/images/a.png", bytes)
    pool.write("https://raw.githubusercontent.com/o/r/${"c".repeat(40)}/images/a.png", bytes)
    assertEquals(1, pool.snapshot().blobs, "three keys, one deduplicated blob")

    val after = pool.clear()

    assertEquals(0, after.blobs)
    assertEquals(0, after.bytes)
    assertEquals(1, after.evicted, "one blob went, not three")
  }

  @Test
  fun `clearing reclaims abandoned scratch files too`() {
    // A process killed mid-produce leaves a bundle-sized file under tmp/ that no census counts and
    // no read will ever want. Without this an operator could clear the cache, be told it holds
    // nothing, and still find the volume full.
    val root = root()
    val pool = CatalogBlobPool(root)
    pool.write("https://raw.githubusercontent.com/o/r/$COMMIT/images/a.png", "real".toByteArray())
    val scratch =
      File(root, CatalogBlobPool.TEMP_DIR).let {
        it.mkdirs()
        File(it, "produce-deadproc-1").apply { writeBytes(ByteArray(4096)) }
      }

    pool.clear()

    assertFalse(scratch.exists(), "an abandoned scratch file is not left behind by a clear")
  }

  @Test
  fun `sweeping reclaims scratch older than the grace window and spares the rest`() {
    // Same reasoning as the clear, but bounded: this runs on a timer while writers are live, so a
    // scratch file younger than the grace window may be one of theirs.
    val now = AtomicLong(1_000_000L)
    val root = root()
    val pool = CatalogBlobPool(root, graceMillis = 60_000, clock = { now.get() })
    val tmp = File(root, CatalogBlobPool.TEMP_DIR).apply { mkdirs() }
    val stale = File(tmp, "produce-old-1").apply { writeBytes(ByteArray(16)) }
    val live = File(tmp, "produce-new-1").apply { writeBytes(ByteArray(16)) }
    assertTrue(stale.setLastModified(now.get() - 120_000))
    assertTrue(live.setLastModified(now.get()))

    pool.sweep()

    assertFalse(stale.exists(), "scratch from a process that is gone")
    assertTrue(live.exists(), "scratch a live writer may still be filling")
  }

  @Test
  fun `a pool reports whether persistence was configured and what it adopted`() {
    // `persistenceConfigured` reports a decision; `adopted` is the evidence. Everything else looks
    // the same either way: a temp pool fills, serves within-process hits and reports climbing
    // writes, right up until the container is recreated and none of it is there.
    val root = root()
    val first = CatalogBlobPool(root, persistenceConfigured = true)
    assertFalse(
      CatalogBlobPool(root()).snapshot().persistenceConfigured,
      "a temp-dir pool says so",
    )
    assertTrue(first.snapshot().persistenceConfigured)
    assertEquals(0, first.snapshot().adopted, "nothing was here before this process")

    first.write("https://raw.githubusercontent.com/o/r/$COMMIT/images/a.png", "png".toByteArray())

    // A restart over the same volume: what it finds is what survived.
    val reopened = CatalogBlobPool(root, persistenceConfigured = true)
    assertEquals(1, reopened.snapshot().adopted, "the blob the previous process left")
    assertEquals(0, first.snapshot().adopted, "adopted is fixed at open, not a running total")
  }

  @Test
  fun `occupancy is right from the first poll, before any sweep`() {
    // The census at construction seeds the published occupancy too, so a freshly opened pool over a
    // warm volume does not report an empty cache until its first sweep.
    val root = root()
    CatalogBlobPool(root)
      .write(
        "https://raw.githubusercontent.com/o/r/$COMMIT/images/a.png",
        "png".toByteArray(),
      )

    val snapshot = CatalogBlobPool(root).snapshot()

    assertEquals(1, snapshot.blobs)
    assertTrue(snapshot.bytes > 0)
  }

  @Test
  fun `the audit catches a key filed against the wrong content`() {
    // The one failure content-addressing cannot see. Every blob is verified against its OWN name,
    // so a mis-filed pointer — key K naming content sha S when S is not what K holds — passes every
    // existing check: the blob under S hashes to S perfectly well. Simulated here by writing one
    // key's bytes and then auditing it against what the branch actually serves.
    val pool = CatalogBlobPool(root())
    val url = "https://raw.githubusercontent.com/o/r/$COMMIT/images/button.png"
    pool.write(url, "the wrong asset".toByteArray())

    val result = pool.audit(url, "what the branch serves".toByteArray())

    assertEquals(CatalogBlobPool.AuditResult.MISMATCHED, result)
    assertEquals(1, pool.snapshot().mismatched)
    assertNull(pool.read(url), "a mismatched entry is dropped so the next read re-fetches")
  }

  @Test
  fun `an audit that agrees leaves the entry alone`() {
    val pool = CatalogBlobPool(root())
    val url = "https://raw.githubusercontent.com/o/r/$COMMIT/images/button.png"
    val bytes = "the right asset".toByteArray()
    pool.write(url, bytes)

    assertEquals(CatalogBlobPool.AuditResult.MATCHED, pool.audit(url, bytes))
    assertEquals(0, pool.snapshot().mismatched)
    assertEquals(1, pool.snapshot().audited)
    assertContentEquals(bytes, assertNotNull(pool.read(url)))
  }

  @Test
  fun `auditing a key the pool does not hold checks nothing`() {
    // Not a pass and not a failure — there was nothing to compare. Counting it as audited would
    // make the audit look like it is doing more work than it is.
    val pool = CatalogBlobPool(root())

    val result =
      pool.audit(
        "https://raw.githubusercontent.com/o/r/$COMMIT/images/absent.png",
        "x".toByteArray(),
      )

    assertEquals(CatalogBlobPool.AuditResult.NOT_CACHED, result)
    assertEquals(0, pool.snapshot().audited)
  }

  @Test
  fun `sweep reclaims oldest-first down to the cap`() {
    val now = AtomicLong(1_000_000L)
    val pool = CatalogBlobPool(root(), maxBytes = 200, graceMillis = 0, clock = { now.get() })
    val blobs =
      (1..4).map { i ->
        now.addAndGet(1_000)
        assertNotNull(
          pool.keyed("https://raw.githubusercontent.com/o/r/$COMMIT/b$i.png") { dest ->
            dest.writeBytes(ByteArray(100) { i.toByte() })
            true
          }
        )
      }

    now.addAndGet(1_000)
    val snapshot = pool.sweep()

    assertEquals(200, snapshot.bytes)
    assertEquals(2, snapshot.blobs)
    assertEquals(2, snapshot.evicted)
    assertFalse(blobs[0].exists(), "the oldest blob goes first")
    assertFalse(blobs[1].exists())
    assertTrue(blobs[2].exists())
    assertTrue(blobs[3].exists())
  }

  @Test
  fun `sweep spares blobs younger than the grace window`() {
    // The overlapping-replica case: a booting replica shares this volume with the one still
    // serving, and knows nothing about what it just wrote. Without the window it would reclaim it.
    val now = AtomicLong(1_000_000L)
    val pool = CatalogBlobPool(root(), maxBytes = 10, graceMillis = 60_000, clock = { now.get() })
    val blob =
      assertNotNull(
        pool.keyed("https://raw.githubusercontent.com/o/r/$COMMIT/fresh.png") { dest ->
          dest.writeBytes(ByteArray(100))
          true
        }
      )

    val snapshot = pool.sweep()

    assertTrue(blob.exists(), "a blob the outgoing replica may still be reading must survive")
    assertEquals(0, snapshot.evicted)
    assertEquals(100, snapshot.bytes, "over the cap, and reported rather than acted on")
  }

  @Test
  fun `sweep drops pointers whose blob is gone`() {
    val root = root()
    val pool = CatalogBlobPool(root, graceMillis = 0)
    val url = "https://raw.githubusercontent.com/o/r/$COMMIT/b.png"
    val bytes = "gone soon".toByteArray()
    assertNotNull(
      pool.keyed(url) { dest ->
        dest.writeBytes(bytes)
        true
      }
    )
    assertTrue(pool.contentFile(sha(bytes)).delete())

    pool.sweep()

    val pointers = File(root, CatalogBlobPool.KEYS_DIR).listFiles()?.filter { it.isFile }.orEmpty()
    assertTrue(pointers.isEmpty(), "a pointer to nothing is not worth keeping")
  }

  @Test
  fun `an unwritable root degrades to no caching rather than failing the load`() {
    // Persistence is an optimisation. A box with a read-only or full disk must load catalogs
    // exactly as it did before this existed, which means every call here answers null or refetches
    // — never throws.
    val root = File(root(), "nested").apply { writeText("not a directory") }
    val pool = CatalogBlobPool(root)
    val bytes = "x".toByteArray()

    assertNull(pool.contentAddressed(sha(bytes), bytes.size.toLong()) { bytes })
    assertNull(pool.keyed("https://raw.githubusercontent.com/o/r/$COMMIT/b.png") { true })
    assertFalse(pool.holds("https://raw.githubusercontent.com/o/r/$COMMIT/b.png"))
    assertNotNull(pool.snapshot().lastFailure)
  }

  private companion object {
    const val COMMIT = "0123456789abcdef0123456789abcdef01234567"
  }
}
