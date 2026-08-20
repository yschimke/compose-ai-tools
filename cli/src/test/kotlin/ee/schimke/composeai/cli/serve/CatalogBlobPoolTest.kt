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
