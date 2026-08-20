package ee.schimke.composeai.cli.serve

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.Serializable

/** What the pool holds and what its reads did — `/status.json` → `catalogCache`. */
@Serializable
data class CatalogBlobPoolSnapshot(
  val blobs: Int,
  val bytes: Long,
  val maxBytes: Long,
  /** Reads answered from disk — the requests this pool did not make. */
  val hits: Long,
  /** Reads that had to produce the blob. */
  val misses: Long,
  val writes: Long,
  val writeFailures: Long,
  /** Blobs reclaimed by [CatalogBlobPool.sweep]. */
  val evicted: Long,
  /** Blobs dropped because their bytes did not hash back to their name. */
  val corrupt: Long,
  /** Cached entries re-checked against the branch by the sampled audit. */
  val audited: Long = 0,
  /**
   * Audited entries whose cached bytes did **not** match the branch.
   *
   * Expected to be zero forever: the audit exists to check the one thing content-addressing cannot
   * — that a key is filed against the right content — and a non-zero value means something the
   * design treats as impossible has happened. Worth an alert, not a dashboard.
   */
  val mismatched: Long = 0,
  /**
   * Whether an operator configured a directory (`--catalog-cache-dir`), rather than this being the
   * temp-dir fallback discarded with the container.
   *
   * `false` on a deployed box means the bytes are certainly being paid for and thrown away. `true`
   * means only that the decision was made: a configured path inside an image with no volume mounted
   * there is just as ephemeral, and nothing here can tell those apart. [adopted] is the evidence
   * that the storage actually persisted.
   */
  val persistenceConfigured: Boolean = false,
  /**
   * Blobs already on disk when this process opened the pool.
   *
   * The only direct evidence that anything survived the last restart, and therefore the number to
   * read after a roll: `0` on a pool with [persistenceConfigured] that should have found a warm
   * volume is the failure, and it is invisible in every other field.
   */
  val adopted: Int = 0,
  val lastFailure: String? = null,
)

/**
 * Content-addressed home for the heavy bytes a catalog load fetches — the executable `liveBundle`,
 * its per-preview splits, and the externalised resource pool.
 *
 * ### What this exists to stop
 *
 * `ServeCommand.registerCatalogs` roots the catalog store at a `createTempDirectory`, so everything
 * a catalog fetched is discarded when the container is recreated — which on the prebuilt image is
 * what every rolled release performs. Worse, it is not only restarts: [ServeCatalogStore.load]
 * finishes by deleting the live per-system directory before renaming staging over it, so **every
 * reload throws the previous generation away too**, including a 100 MB-class bundle the new
 * revision may not have changed at all. Only the old `.res-cache` survived a reload, and only
 * because it sat above that directory.
 *
 * Pointing [ServeCommand] at a durable root makes the pool outlive both events. It is deliberately
 * the whole of the change: nothing here decides *what* to cache or *when* a catalog is stale — that
 * is the caller's business, and the rule the caller must keep is stated below.
 *
 * ### The rule callers must keep
 *
 * **Only bytes with an immutable address may be [keyed] here.** A catalog load resolves its
 * delivery commit first and pins every subsequent URL to it
 * (`raw.githubusercontent.com/<repo>/<commit>/…`), so those reads are immutable by construction and
 * a cached answer can only ever be *the* answer. The un-pinned fallback — a load whose revision
 * feed could not be read, which addresses the branch ref instead — is mutable, and must not reach
 * this pool at all. One rule, no TTLs, nothing to revalidate.
 *
 * [contentAddressed] carries no such caveat: its key *is* the digest a trusted manifest declared,
 * so it is safe wherever that manifest is.
 *
 * ### Layout, and why there is only one blob space
 *
 * ```
 * <root>/content/<sha256-of-bytes>     the blobs themselves
 * <root>/keys/<sha256-of-key>          a pointer: the content sha its key resolves to
 * ```
 *
 * Both addressing modes land in the same `content/` space, so a bundle fetched by URL and the same
 * bundle declared by sha are one file. More importantly it makes **every blob self-verifying**: the
 * file name is the digest, so a read hashes the bytes and compares, and a truncated or corrupted
 * entry can never be handed to a classloader. A sidecar recording the digest beside the blob would
 * have needed the pair to stay consistent across two writers and a kill; a name cannot go out of
 * step with itself.
 *
 * The pointer file is the only mutable thing here, and it is a single small atomic write whose
 * every possible value names a self-verifying blob — so two replicas racing on it cannot produce a
 * wrong read, at worst a re-produced one.
 *
 * ### Concurrency
 *
 * The prebuilt image's rolling update boots a new replica alongside the running one and both mount
 * this volume. Writes are therefore staged per-writer and moved into place atomically, reads verify
 * before trusting, and [sweep] spares anything younger than [graceMillis] so a booting replica
 * cannot reclaim the bytes the outgoing one is still serving from.
 */
class CatalogBlobPool(
  private val root: File,
  /**
   * Ceiling for the whole pool, enforced by [sweep] rather than at write time — a load must not
   * block on a byte census, and a pool that refused to grow between sweeps would stop caching
   * exactly when it was busiest.
   */
  private val maxBytes: Long = DEFAULT_MAX_BYTES,
  /** How recently a blob must have been touched to be spared by [sweep]. See **Concurrency**. */
  private val graceMillis: Long = DEFAULT_SWEEP_GRACE_MILLIS,
  /**
   * Whether an operator named a directory for [root], rather than this being the temp-dir fallback.
   *
   * Deliberately **not** called durable. Nothing here can establish that the storage outlives the
   * process — `--catalog-cache-dir /var/cache/x` in a container with no volume mounted there is
   * configured and just as ephemeral, while the same path under a plain host `serve` persists fine,
   * and no portable test tells those apart from in here. So this reports the decision that was
   * made, and [adopted] reports what actually survived. Claiming the stronger thing would be the
   * false reassurance it exists to remove.
   */
  private val persistenceConfigured: Boolean = false,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val hits = AtomicLong()
  private val misses = AtomicLong()
  private val writes = AtomicLong()
  private val writeFailures = AtomicLong()
  private val evicted = AtomicLong()
  private val corrupt = AtomicLong()
  private val audited = AtomicLong()
  private val mismatched = AtomicLong()
  /**
   * Occupancy as of the last census, advanced by each write and reclaim in between.
   *
   * Published rather than measured, for the reason [maxBytes] is enforced by [sweep] rather than at
   * write time: a census is a directory listing plus a `length()` per file, and `/status.json` is
   * polled. Reading it there would put an O(blobs) filesystem walk on a monitoring request and make
   * the cost grow with exactly the thing the cache is trying to grow. [ThemeCacheStore] publishes
   * its own the same way. The cost is that these lag a write by at most one sweep interval, which
   * is the right trade for a number nobody reads to the byte.
   */
  private val knownBlobs = java.util.concurrent.atomic.AtomicInteger()
  private val knownBytes = AtomicLong()
  @Volatile private var lastFailure: String? = null
  private val tempSequence = AtomicLong()

  /** Distinguishes this process's in-flight writes from a concurrently deployed replica's. */
  private val writerId: String =
    ProcessHandle.current().pid().toString(36) + "-" + System.identityHashCode(this).toString(36)

  /**
   * Serialises production per key within this process, so a grid opening twenty per-preview daemons
   * at once produces each blob once instead of once per caller. Cross-process races are left to the
   * atomic move — they are rare, and the loser's work is simply discarded.
   */
  private val keyLocks = ConcurrentHashMap<String, Any>()

  private val contentDir = File(root, CONTENT_DIR)
  private val keysDir = File(root, KEYS_DIR)

  /**
   * Blobs already present when this process opened the pool — the only direct evidence that
   * anything survived the last restart.
   *
   * One census at construction, which is also what seeds the published occupancy so `/status.json`
   * is right from the first poll rather than from the first sweep. `0` on a configured pool after a
   * roll that should have found a warm volume is the failure this number exists to make visible;
   * without it a cache that is quietly starting over every time looks exactly like one that is
   * working, since both report climbing writes.
   */
  private val adopted: Int = census().blobs

  /**
   * The blob whose sha256 is [sha256], fetching it once when absent, or null when it cannot be had.
   *
   * The key is the digest, so a hit is trusted only after its bytes hash back to it: a same-length
   * but corrupt entry (a partial write, a disk fault) is re-fetched rather than silently put on a
   * classpath. [size] is checked first purely because it is free.
   *
   * Null rather than an exception throughout — the pool is an optimisation, and a box with a
   * read-only or full disk must load catalogs exactly as it did before this existed.
   */
  fun contentAddressed(sha256: String, size: Long, fetch: () -> ByteArray?): File? {
    val sha = sha256.takeIf(::isSha) ?: return null
    val blob = File(contentDir, sha)
    readVerified(blob, sha, size)?.let {
      hits.increment()
      return it
    }
    misses.increment()
    return synchronized(keyLocks.computeIfAbsent(sha) { Any() }) {
      // Double-checked: a caller that queued behind another's fetch reads what it just landed —
      // and is counted as the hit it is, so contention does not quietly depress the hit rate these
      // counters exist to report.
      readVerified(blob, sha, size)?.also { hits.increment() }
        ?: run {
          val bytes = runCatching(fetch).getOrNull() ?: return@run null
          if (sha256Hex(bytes) != sha) {
            recordFailure("declared sha256 $sha does not match the bytes fetched for it")
            return@run null
          }
          store(bytes, sha)
        }
    }
  }

  /**
   * The blob [key] resolves to, producing it once with [produce] when absent, or null when it
   * cannot be had.
   *
   * [key] must be an **immutable** address — see **The rule callers must keep**. [produce] is
   * handed a scratch file to write and returns whether it wrote one; whatever it left there is
   * hashed, stored under its digest, and the key repointed at it. So a producer that is not
   * byte-reproducible (a repack that stamps a timestamp) costs at most a duplicate blob, never a
   * wrong read.
   */
  fun keyed(key: String, produce: (dest: File) -> Boolean): File? {
    val pointer = File(keysDir, sha256Hex(key.toByteArray()))
    resolve(pointer)?.let {
      hits.increment()
      return it
    }
    misses.increment()
    return synchronized(keyLocks.computeIfAbsent(key) { Any() }) {
      resolve(pointer)?.also { hits.increment() }
        ?: run {
          val scratch = temp("produce") ?: return@run null
          val produced = runCatching { produce(scratch) }.getOrDefault(false) && scratch.isFile
          if (!produced) {
            scratch.delete()
            return@run null
          }
          val sha = sha256Hex(scratch)
          if (sha == null) {
            scratch.delete()
            recordFailure("could not digest the blob produced for a key")
            return@run null
          }
          val blob = adopt(scratch, sha) ?: return@run null
          // Written last, and only once the blob it names is in place — a pointer is only ever
          // read back through [resolve], which re-verifies, so a stale one degrades to a miss.
          writeAtomically(pointer, sha.toByteArray())
          blob
        }
    }
  }

  /**
   * Whether [key] already resolves to a blob that is present, **without** verifying its bytes.
   *
   * For an availability probe that must not download: the caller asking is deciding whether a lane
   * exists at all, and hashing a multi-megabyte bundle to answer would defeat the point of asking
   * cheaply. A `true` that a later [keyed] read then rejects as corrupt costs one re-produce, which
   * is the same thing a `true` from a network probe costs.
   */
  fun holds(key: String): Boolean =
    resolve(File(keysDir, sha256Hex(key.toByteArray())), verify = false) != null

  /**
   * The bytes cached under [key], or null when there is no verified entry.
   *
   * The read half of the small-asset lane, split from [keyed] because that one is built around
   * *producing* the blob under a lock — right for a 100 MB bundle a grid of daemons would otherwise
   * fetch twenty times over, wrong for a baked PNG on the request path, where a miss should return
   * immediately so the caller can go to the branch and report **why** it failed rather than have
   * that answer collapsed into a null.
   */
  fun read(key: String): ByteArray? {
    val blob = resolve(File(keysDir, sha256Hex(key.toByteArray()))) ?: return null
    hits.increment()
    return runCatching { blob.readBytes() }.getOrNull()
  }

  /**
   * Cache [bytes] under [key]. Best-effort — a full or read-only disk simply leaves the next read a
   * miss.
   *
   * [key] must be an immutable address; see **The rule callers must keep**.
   */
  fun write(key: String, bytes: ByteArray) {
    misses.increment()
    val sha = sha256Hex(bytes)
    val blob = File(contentDir, sha)
    if (blob.isFile) {
      // Already held under some other key — the overwhelmingly common case on a republish, where a
      // regenerated catalog carries mostly byte-identical assets at a NEW commit, so every
      // unchanged asset's fresh URL dedupes onto the blob that is already here. Stamping it is
      // what makes that a refresh rather than a silent ageing: without it the blob keeps the time
      // it was first written, and the next sweep can evict precisely the assets that are current.
      stamp(blob)
    } else if (store(bytes, sha) == null) {
      return
    }
    writeAtomically(File(keysDir, sha256Hex(key.toByteArray())), sha.toByteArray())
  }

  /**
   * Check what this pool would serve for [key] against [fresh] — bytes just read from the branch —
   * and drop the entry when they differ.
   *
   * ### The one thing nothing else here checks
   *
   * Every blob is verified against its **own** name on read, so a truncated or bit-rotted file can
   * never be served. What that cannot catch is a wrong *mapping*: the pointer says "key K holds
   * content sha S", and if K were ever filed against the wrong S — a mistaken `write` call site, a
   * refactor that reuses a key, a race nobody predicted — the blob under S still hashes to S, every
   * check passes, and the pool serves the wrong bytes for K indefinitely. Content-addressing makes
   * corruption impossible and mis-filing invisible; this is the only thing that would notice.
   *
   * Deliberately a **report, not a gate**. It runs on a sample, behind the request path, and its
   * effect on a mismatch is to drop the entry so the next read re-fetches. A mismatch means
   * something is wrong that the design says cannot happen, so the point is that [mismatched] stops
   * being zero and someone looks — not that a visitor waits for an audit.
   */
  fun audit(key: String, fresh: ByteArray): AuditResult {
    val held = read(key) ?: return AuditResult.NOT_CACHED
    audited.increment()
    if (held.contentEquals(fresh)) return AuditResult.MATCHED
    mismatched.increment()
    recordFailure("audit: cached bytes for a key did not match the branch — entry dropped")
    runCatching { File(keysDir, sha256Hex(key.toByteArray())).delete() }
    return AuditResult.MISMATCHED
  }

  /** What [audit] established about one key. */
  enum class AuditResult {
    /** Nothing cached under that key — the audit had nothing to check. */
    NOT_CACHED,
    /** What the pool holds is what the branch serves. */
    MATCHED,
    /** They differ. The entry was dropped; something is wrong upstream of the blob. */
    MISMATCHED,
  }

  /**
   * Drop **everything** this pool holds, returning what is left (normally nothing).
   *
   * The operator's "I do not trust this; fetch it again" button. Whole-pool rather than per
   * catalog, and that is not a shortcut: blobs are named by their own digest and shared across
   * systems on purpose — a font fetched for one catalog is the same file the next one reads — so no
   * blob has an owning system to delete it by. Partitioning by system to make a narrower button
   * possible would give up the deduplication, which is worth more than the button.
   *
   * Safe at any moment for the same reason [sweep] is: everything here is re-fetchable, and a
   * reader already holding an open file keeps reading it. The cost of being wrong about needing
   * this is bandwidth, not correctness.
   */
  fun clear(): CatalogBlobPoolSnapshot {
    // Only the blobs count as evictions. A pointer is not a blob, and deduplication means many of
    // them can name one — counting both would let a clear report far more reclaimed than existed,
    // in the very metric an operator reads to check the clear did what they asked.
    for (blob in contentDir.listFiles()?.filter { it.isFile }.orEmpty()) {
      if (runCatching { blob.delete() }.getOrDefault(false)) evicted.increment()
    }
    for (pointer in keysDir.listFiles()?.filter { it.isFile }.orEmpty()) {
      runCatching { pointer.delete() }
    }
    // Scratch too. A process killed mid-produce leaves a bundle-sized file under `tmp/`, which no
    // census counts and no read will ever want — so without this an operator could clear the cache,
    // be told it holds nothing, and still find the volume full. A live writer losing its scratch
    // file fails that one produce and returns null, which every caller already treats as a miss.
    for (scratch in File(root, TEMP_DIR).listFiles()?.filter { it.isFile }.orEmpty()) {
      runCatching { scratch.delete() }
    }
    return census()
  }

  /**
   * Reclaim blobs until the pool is under [maxBytes], oldest-touched first, sparing anything
   * younger than [graceMillis]; then drop pointers whose blob is gone.
   *
   * Eviction is always safe — the worst a reclaimed blob costs is the fetch that produces it again
   * — which is what lets this run without knowing anything about which catalogs are live. Run it
   * once the catalog pass has finished, where the byte census is worth paying for.
   */
  fun sweep(): CatalogBlobPoolSnapshot {
    val now = clock()
    val blobs =
      contentDir.listFiles()?.filter { it.isFile }.orEmpty().sortedBy { it.lastModified() }
    var total = blobs.sumOf { it.length() }
    for (blob in blobs) {
      if (total <= maxBytes) break
      if (now - blob.lastModified() < graceMillis) continue
      val size = blob.length()
      if (runCatching { blob.delete() }.getOrDefault(false)) {
        total -= size
        evicted.increment()
      }
    }
    for (pointer in keysDir.listFiles()?.filter { it.isFile }.orEmpty()) {
      if (resolve(pointer, verify = false) == null) runCatching { pointer.delete() }
    }
    // Abandoned scratch, on the same reasoning as [clear] — but bounded by the grace window rather
    // than unconditional, because this runs on a timer while writers are live and a scratch file
    // younger than that may be one of theirs. Anything older belonged to a process that is gone.
    for (scratch in File(root, TEMP_DIR).listFiles()?.filter { it.isFile }.orEmpty()) {
      if (now - scratch.lastModified() >= graceMillis) runCatching { scratch.delete() }
    }
    return census()
  }

  /**
   * Re-measure occupancy from the filesystem and publish it. Only ever called from the paths that
   * are already walking the directory — see [knownBlobs].
   */
  private fun census(): CatalogBlobPoolSnapshot {
    val blobs = contentDir.listFiles()?.filter { it.isFile }.orEmpty()
    knownBlobs.set(blobs.size)
    knownBytes.set(blobs.sumOf { it.length() })
    return snapshot()
  }

  /** Counters and the last published occupancy. Cheap enough for a polled status endpoint. */
  fun snapshot(): CatalogBlobPoolSnapshot {
    return CatalogBlobPoolSnapshot(
      blobs = knownBlobs.get(),
      bytes = knownBytes.get(),
      maxBytes = maxBytes,
      persistenceConfigured = persistenceConfigured,
      adopted = adopted,
      hits = hits.get(),
      misses = misses.get(),
      writes = writes.get(),
      writeFailures = writeFailures.get(),
      evicted = evicted.get(),
      corrupt = corrupt.get(),
      audited = audited.get(),
      mismatched = mismatched.get(),
      lastFailure = lastFailure,
    )
  }

  /** The file a blob with this digest occupies, whether or not it exists. Visible for tests. */
  fun contentFile(sha256: String): File = File(contentDir, sha256)

  // ---------------------------------------------------------------------------------------------

  /** [blob] if it exists and hashes back to [sha], else null — dropping it when it does not. */
  private fun readVerified(blob: File, sha: String, size: Long = -1): File? {
    if (!blob.isFile) return null
    if (size >= 0 && blob.length() != size) {
      dropCorrupt(blob, "size")
      return null
    }
    if (sha256Hex(blob) != sha) {
      dropCorrupt(blob, "digest")
      return null
    }
    touch(blob)
    return blob
  }

  /** The blob [pointer] names, verified unless the caller only wants to know it is still there. */
  private fun resolve(pointer: File, verify: Boolean = true): File? {
    val sha = runCatching { pointer.readText().trim() }.getOrNull()?.takeIf(::isSha) ?: return null
    val blob = File(contentDir, sha)
    if (!verify) return blob.takeIf { it.isFile }
    return readVerified(blob, sha)
  }

  private fun dropCorrupt(blob: File, why: String) {
    corrupt.increment()
    recordFailure("discarded ${blob.name.take(12)}… on $why mismatch")
    val size = runCatching { blob.length() }.getOrDefault(0L)
    if (runCatching { blob.delete() }.getOrDefault(false)) {
      knownBlobs.updateAndGet { (it - 1).coerceAtLeast(0) }
      knownBytes.updateAndGet { (it - size).coerceAtLeast(0L) }
    }
  }

  /**
   * Put a newly published blob on this pool's clock. Best-effort.
   *
   * Unconditional, unlike [touch]: a blob arrives carrying whatever mtime the filesystem gave the
   * scratch file it was moved from, which is wall-clock time and therefore says nothing about when
   * *this* pool saw it. Stamping on write is what makes every subsequent comparison — the sweeper's
   * ordering, [touch]'s staleness check — read one clock instead of two.
   */
  private fun stamp(blob: File) {
    runCatching { blob.setLastModified(clock()) }
  }

  /**
   * Keeps a blob that is still being read out of the sweeper's reach. Best-effort.
   *
   * Skipped when the recorded time is already recent. Once the small-asset lane reads through this
   * pool, a touch on every hit is a filesystem metadata write on the **request path** — one per
   * baked PNG a visitor's grid paints — bought to refine an ordering the sweeper only consults
   * against an hour-wide grace window. Re-stamping at most once per [TOUCH_INTERVAL_MILLIS] keeps
   * "least recently used" meaningful at the resolution anything actually uses it.
   */
  private fun touch(blob: File) {
    val now = clock()
    val recorded = runCatching { blob.lastModified() }.getOrDefault(0L)
    if (now - recorded in 0 until TOUCH_INTERVAL_MILLIS) return
    runCatching { blob.setLastModified(now) }
  }

  private fun store(bytes: ByteArray, sha: String): File? {
    val scratch = temp("store") ?: return null
    return runCatching { scratch.writeBytes(bytes) }
      .fold(
        onSuccess = { adopt(scratch, sha) },
        onFailure = {
          scratch.delete()
          recordFailure("could not stage a blob: ${it.message}")
          null
        },
      )
  }

  /** Move [scratch] into `content/<sha>`, or drop it when another writer got there first. */
  private fun adopt(scratch: File, sha: String): File? {
    val blob = File(contentDir, sha)
    if (!ensureDir(contentDir)) {
      scratch.delete()
      return null
    }
    // A blob already there is by definition these bytes — the name is their digest — so the race
    // has no loser worth reporting: drop the duplicate and read what is already published.
    if (blob.isFile) {
      scratch.delete()
      stamp(blob)
      return blob
    }
    val moved = runCatching {
      java.nio.file.Files.move(
        scratch.toPath(),
        blob.toPath(),
        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
      )
      true
    }
      .getOrElse { runCatching { scratch.renameTo(blob) }.getOrDefault(false) }
    if (!moved) {
      scratch.delete()
      // Another writer landing the identical bytes first is a success, not a failure.
      if (blob.isFile) return blob
      writeFailures.increment()
      recordFailure("could not publish a blob into $contentDir")
      return null
    }
    writes.increment()
    // Advance the published occupancy so a write is visible before the next sweep re-measures.
    knownBlobs.incrementAndGet()
    knownBytes.addAndGet(runCatching { blob.length() }.getOrDefault(0L))
    // Stamped from the same clock every hit reads, so "least recently used" is one notion rather
    // than a mix of the pool's clock and whatever mtime the move happened to preserve.
    stamp(blob)
    return blob
  }

  private fun writeAtomically(target: File, bytes: ByteArray) {
    if (!ensureDir(target.parentFile)) return
    val scratch = temp("pointer") ?: return
    runCatching {
      scratch.writeBytes(bytes)
      java.nio.file.Files.move(
        scratch.toPath(),
        target.toPath(),
        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
      )
    }
      .onFailure {
        scratch.delete()
        recordFailure("could not write a key pointer: ${it.message}")
      }
  }

  /** A scratch file in the pool's own temp dir, so every publish is a same-filesystem move. */
  private fun temp(what: String): File? {
    val dir = File(root, TEMP_DIR)
    if (!ensureDir(dir)) return null
    return File(dir, "$what-$writerId-${tempSequence.incrementAndGet()}")
  }

  private fun ensureDir(dir: File?): Boolean {
    if (dir == null) return false
    if (dir.isDirectory) return true
    if (runCatching { dir.mkdirs() }.getOrDefault(false) || dir.isDirectory) return true
    writeFailures.increment()
    recordFailure("could not create $dir")
    return false
  }

  private fun recordFailure(reason: String) {
    lastFailure = reason.take(MAX_REASON_CHARS)
  }

  private fun AtomicLong.increment() {
    incrementAndGet()
  }

  companion object {
    const val CONTENT_DIR: String = "content"
    const val KEYS_DIR: String = "keys"
    const val TEMP_DIR: String = "tmp"

    /**
     * Ceiling for the whole pool. Sized for a box publishing a couple of dozen catalogs: the
     * executable bundles are the bulk and run to ~100 MB each, and a catalog keeps its previous
     * revision's bundle until the sweeper reclaims it.
     */
    const val DEFAULT_MAX_BYTES: Long = 8L * 1024 * 1024 * 1024

    /** Long enough to cover a rollout's readiness window, short enough to reclaim the same day. */
    const val DEFAULT_SWEEP_GRACE_MILLIS: Long = 60L * 60 * 1000

    /**
     * How stale a blob's recorded time must be before a hit re-stamps it — see [touch]. Well under
     * the sweep grace window, so a blob being read regularly can never age into eviction.
     */
    const val TOUCH_INTERVAL_MILLIS: Long = 5L * 60 * 1000

    const val MAX_REASON_CHARS: Int = 200

    private const val BUFFER_BYTES = 1 shl 16

    private fun isSha(value: String): Boolean =
      value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    fun sha256Hex(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).hex()

    /**
     * Streamed rather than `readBytes()` — these blobs run to 100 MB and are hashed on every read.
     */
    fun sha256Hex(file: File): String? = runCatching {
      val digest = MessageDigest.getInstance("SHA-256")
      file.inputStream().use { input ->
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
          val read = input.read(buffer)
          if (read < 0) break
          digest.update(buffer, 0, read)
        }
      }
      digest.digest().hex()
    }
      .getOrNull()

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
  }
}
