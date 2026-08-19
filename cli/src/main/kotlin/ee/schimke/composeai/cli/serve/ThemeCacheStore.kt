package ee.schimke.composeai.cli.serve

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * On-disk home for rendered theme PNGs, so warming survives the thing that produced it.
 *
 * ### What this exists to stop
 *
 * [CatalogThemeCache] is process memory. It is dropped by a server restart and, separately, by a
 * catalog reload — every load builds a fresh `ServeSessionState` and therefore a fresh cache. For
 * `m3-catalog` on the public box those two together fired 7-10 times a day (3 delivery-branch
 * regenerations and 4 releases on 2026-08-17 alone) against a catalog needing roughly 28 hours of
 * lane time to warm its 10,120 targets. It had never once had a window long enough to finish, on
 * any day. Rotation ([ServeBackgroundWork]) got it warming; only persistence lets the work
 * accumulate.
 *
 * ### Layout
 *
 * ```
 * <root>/<system>/<fingerprint>/manifest.json
 * <root>/<system>/<fingerprint>/<sha256(cacheKey)>.png
 * ```
 *
 * A **generation** is one `(system, fingerprint)` pair — see [ThemeCacheFingerprint] for what the
 * fingerprint covers. Generations are never mutated in place: a new catalog revision or a new
 * server version simply writes under a new directory and the old one is swept. That is what makes
 * invalidation structural rather than something a reader has to remember to check.
 *
 * The manifest is not consulted to decide validity — the directory name already is the decision. It
 * records the inputs so a drop is *explainable* ("renderer 1.13.0 to 1.14.0 invalidated 8,412
 * entries") instead of being another unexplained return to zero.
 */
class ThemeCacheStore(
  private val root: File,
  /**
   * Ceiling for the whole store across every catalog and generation.
   *
   * Enforced by [sweep] rather than at write time. Writes must not block on a byte census — the
   * optimizer is calling [Generation.put] once per render — and a cache that refused to grow
   * between sweeps would silently stop persisting exactly when it was working hardest.
   */
  private val maxBytes: Long = DEFAULT_MAX_BYTES,
  /**
   * How recently a generation must have been created to be spared by [sweep] even when this process
   * has no use for it.
   *
   * This exists for the zero-downtime rollout the image deployment performs: a new replica boots
   * alongside the running one, sharing the `/config` volume this store defaults into, and knows
   * nothing of the old replica's fingerprints. Without a grace window it would reclaim the cache of
   * the replica still serving production — and if the new replica then failed readiness, the old
   * one would carry on with its warming deleted out from under it.
   */
  private val graceMillis: Long = DEFAULT_SWEEP_GRACE_MILLIS,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
  }
  private val writes = AtomicLong()
  private val writeFailures = AtomicLong()
  private val hits = AtomicLong()
  private val misses = AtomicLong()
  // Census published by [sweep] and advanced by each write — see [snapshot] for why it is not read
  // from the filesystem on the request path.
  private val knownBytes = AtomicLong()
  private val knownGenerations = java.util.concurrent.atomic.AtomicInteger()
  /**
   * Generation directories per system, as of the last [sweep].
   *
   * The single number that says whether the *key* is working. A system whose fingerprint is stable
   * has one generation on disk; one whose fingerprint churns — because some input the digest reads
   * changes on every load, a staging path that slipped into the render config, a jar rebuilt each
   * boot — accumulates a directory per restart, each adopted by nobody. Both look identical in
   * [writes] and in [hits], which is exactly the confusion this exists to remove: writes climbing
   * while a system's generation count climbs beside them means the cache is buying disk I/O and
   * nothing else.
   */
  private val knownGenerationsBySystem = AtomicReference<Map<String, Int>>(emptyMap())
  private val lastFailure = ConcurrentHashMap<String, String>()
  private val tempSequence = AtomicLong()
  /** Distinguishes this process's in-flight writes from a concurrently deployed replica's. */
  private val writerId: String =
    ProcessHandle.current().pid().toString(36) + "-" + System.identityHashCode(this).toString(36)

  /**
   * Open (creating if needed) the generation for [system] at [fingerprint], or null when the store
   * is unusable.
   *
   * Null rather than an exception: persistence is an optimisation, and a box with a read-only or
   * full disk must serve catalogs exactly as it did before this existed.
   */
  fun open(system: String, fingerprint: String, inputs: GenerationInputs): Generation? {
    val safeSystem = system.safeName() ?: return null
    val safeFingerprint = fingerprint.safeName() ?: return null
    val dir = File(File(root, safeSystem), safeFingerprint)
    if (!runCatching { dir.mkdirs() }.getOrDefault(false) && !dir.isDirectory) {
      recordFailure(system, "could not create $dir")
      return null
    }
    writeManifest(dir, inputs)
    knownGenerations.incrementAndGet()
    knownGenerationsBySystem.getAndUpdate { it + (system to (it[system] ?: 0) + 1) }
    knownBytes.addAndGet(dir.sizeOnDisk())
    return Generation(dir, system)
  }

  private fun writeManifest(dir: File, inputs: GenerationInputs) {
    val file = File(dir, MANIFEST_NAME)
    if (file.isFile) return
    runCatching { file.writeText(json.encodeToString(inputs.copy(createdAtEpochMillis = clock()))) }
      .onFailure { recordFailure(dir.name, "manifest: ${it.message}") }
  }

  /**
   * Delete every generation not in [live], and report whether what remains fits [maxBytes].
   *
   * Reclaiming the dead set is the whole of the sweep, and on a box regenerating several times a
   * day it is also nearly the whole of the garbage: every superseded catalog revision and every
   * previous server version leaves one behind.
   *
   * **A live generation is never deleted, not even to fit the cap.** Evicting what is currently
   * being warmed to make room for what is not would turn the cap into a treadmill — the optimizer
   * would re-render exactly what the sweep just discarded, forever, and the box would look busy
   * while making no progress. So an over-cap *live* set is reported ([SweepResult.overCap]) rather
   * than acted on: it means the cap is too small for the catalog set, which is a configuration
   * answer and not something this can quietly fix.
   */
  fun sweep(live: Set<GenerationId>, onlySystems: Set<String>? = null): SweepResult {
    val youngerThan = clock() - graceMillis
    val beforeScan = knownBytes.get()
    val liveDirs = live.mapNotNull { it.dir() }.toSet()
    var deleted = 0
    var reclaimed = 0L
    var survivingBytes = 0L
    var survivingGenerations = 0
    val survivingBySystem = mutableMapOf<String, Int>()

    for (systemDir in root.listFiles()?.filter { it.isDirectory }.orEmpty()) {
      val generationDirs = systemDir.listFiles()?.filter { it.isDirectory }.orEmpty()
      // A system the caller has no current generation for is left entirely alone. Absence from the
      // live set means "we did not load this catalog", which is not the same as "this catalog's
      // warmed renders are garbage" — a load can fail transiently, and its cache must outlive that.
      if (onlySystems != null && systemDir.name !in onlySystems) {
        survivingBytes += systemDir.sizeOnDisk()
        survivingGenerations += generationDirs.size
        if (generationDirs.isNotEmpty()) survivingBySystem[systemDir.name] = generationDirs.size
        continue
      }
      for (generationDir in generationDirs) {
        val size = generationDir.sizeOnDisk()
        // Three kinds of survivor, each for its own reason:
        //  - ours: obviously;
        //  - young: the image deployment rolls out zero-downtime, so a new replica boots beside the
        //    running one on the same volume and sees its generations as unreferenced. Reclaiming
        //    them deletes a possibly 28-hour cache from the replica still serving production — and
        //    still serving it if the new replica fails readiness;
        //  - undeletable: the bytes remain on the volume whatever the filesystem reported, and a
        //    census that omits them can report the store under a cap it is actually over.
        if (generationDir in liveDirs || createdAt(generationDir) > youngerThan) {
          survivingBytes += size
          survivingGenerations++
          survivingBySystem.merge(systemDir.name, 1, Int::plus)
          continue
        }
        if (generationDir.deleteRecursively()) {
          deleted++
          reclaimed += size
        } else {
          survivingBytes += size
          survivingGenerations++
          survivingBySystem.merge(systemDir.name, 1, Int::plus)
          recordFailure(systemDir.name, "could not reclaim ${generationDir.name}")
        }
      }
      // A system directory left empty by the sweep is itself garbage.
      if (systemDir.listFiles()?.isEmpty() == true) systemDir.delete()
    }

    val total = survivingBytes
    // Merged, not assigned. A sweep runs concurrently with other catalogs' optimizers writing into
    // this store — startup releases the background-work gate immediately before sweeping — so a
    // bare
    // `set` can discard a write that landed during the scan, or publish a total that never saw a
    // file created after its directory was walked. Either way `/status` under-reports occupancy
    // until the next sweep, which is exactly when an over-cap volume most needs to be visible.
    knownBytes.getAndUpdate { current -> total + (current - beforeScan).coerceAtLeast(0) }
    knownGenerations.set(survivingGenerations)
    knownGenerationsBySystem.set(survivingBySystem.toMap())
    return SweepResult(
      deletedGenerations = deleted,
      reclaimedBytes = reclaimed,
      bytes = total,
      overCap = total > maxBytes,
    )
  }

  /** Every system with a directory in the store, whether or not this server still serves it. */
  fun systems(): Set<String> =
    root.listFiles()?.filter { it.isDirectory }?.map { it.name }?.toSet().orEmpty()

  /**
   * Disk occupancy as of the last sweep, plus everything written since.
   *
   * **Deliberately not a live census.** `/status.json` is a monitoring endpoint that gets polled,
   * and one warmed catalog is 10,120 files — recursively walking the tree per request would put
   * tens of thousands of filesystem metadata operations on the request path, growing with every
   * catalog served. The sweep already walks the tree for its own reasons, so it publishes the total
   * on the way past and writes add to it from there. Slightly stale between sweeps, which is the
   * right trade for a number nobody acts on within a second.
   */
  fun snapshot(): ThemeCacheStoreSnapshot =
    ThemeCacheStoreSnapshot(
      root = root.path,
      generations = knownGenerations.get(),
      generationsBySystem = knownGenerationsBySystem.get(),
      bytes = knownBytes.get(),
      maxBytes = maxBytes,
      writes = writes.get(),
      writeFailures = writeFailures.get(),
      hits = hits.get(),
      misses = misses.get(),
      lastFailureReason = lastFailure["reason"],
    )

  /**
   * When this generation was first created, from its manifest, falling back to the directory's own
   * timestamp and finally to "now" — an unreadable age must read as *young*, so an unparseable
   * manifest errs toward keeping bytes rather than deleting another replica's cache.
   */
  private fun createdAt(dir: File): Long =
    runCatching {
      json
        .decodeFromString(GenerationInputs.serializer(), File(dir, MANIFEST_NAME).readText())
        .createdAtEpochMillis
        .takeIf { it > 0 }
    }
      .getOrNull() ?: dir.lastModified().takeIf { it > 0 } ?: clock()

  private fun recordFailure(system: String, reason: String) {
    writeFailures.incrementAndGet()
    lastFailure["reason"] = "$system: ${reason.take(MAX_REASON_CHARS)}"
  }

  /** One `(system, fingerprint)` generation's directory of PNGs. */
  inner class Generation internal constructor(private val dir: File, private val system: String) {

    /**
     * Cache keys already on disk, read once at open.
     *
     * Held as a set rather than re-listed per lookup because the question "is this target already
     * warm" is asked for every target on every optimizer pass — 10,120 times for one catalog — and
     * a directory listing per question would make the cache slower than the renders it replaces.
     */
    private val present: MutableSet<String> =
      java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>()).apply {
        dir
          .listFiles()
          ?.filter { it.isFile && it.name.endsWith(PNG_SUFFIX) }
          ?.forEach { add(it.name.removeSuffix(PNG_SUFFIX)) }
      }

    /** How many renders were already on disk when this generation was opened. */
    val loadedEntries: Int = present.size

    /** This generation's directory name — the fingerprint it was opened under. */
    val fingerprint: String = dir.name

    // Per-generation counters. The store-wide ones next to them answer "is the volume being used";
    // these answer "is THIS catalog's cache working", which is the question an operator actually
    // has — a box serving fifteen catalogs where one has an unstable fingerprint reports healthy
    // store-wide totals while that one catalog re-renders from scratch every restart.
    private val generationHits = AtomicLong()
    private val generationMisses = AtomicLong()
    private val generationWrites = AtomicLong()

    /**
     * Exactly the renders that were on disk when this generation was opened — the ones written by
     * some *other* process, and therefore the only ones whose trustworthiness is in question.
     *
     * Snapshotted because [present] grows as this process writes, and a render this process just
     * produced needs no verification: it came from this renderer.
     */
    private val adopted: MutableSet<String> =
      java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>()).apply {
        addAll(present)
      }

    /** Whether [cacheKey] came from a previous process rather than from this one. */
    fun wasAdopted(cacheKey: String): Boolean = fileName(cacheKey) in adopted

    fun contains(cacheKey: String): Boolean = fileName(cacheKey) in present

    fun get(cacheKey: String): ByteArray? {
      val name = fileName(cacheKey)
      if (name !in present) {
        misses.incrementAndGet()
        generationMisses.incrementAndGet()
        return null
      }
      val bytes = runCatching { File(dir, "$name$PNG_SUFFIX").readBytes() }.getOrNull()
      if (bytes == null) {
        // On disk a moment ago and unreadable now — a sweep, an external delete, a truncated write.
        // Forget it so the optimizer treats it as work still to do rather than as permanently
        // cached-but-broken.
        present.remove(name)
        misses.incrementAndGet()
        generationMisses.incrementAndGet()
        return null
      }
      hits.incrementAndGet()
      generationHits.incrementAndGet()
      return bytes
    }

    /**
     * Persist one render. Best-effort and never throws: a failed write costs a re-render later,
     * which is strictly better than failing the render that just succeeded.
     *
     * Written to a temp file and renamed, so a crash or a full disk leaves no half-PNG that a later
     * process would read as a valid cached render.
     */
    fun put(cacheKey: String, png: ByteArray, replaceExisting: Boolean = false) {
      val name = fileName(cacheKey)
      // Presence alone is not proof that no write is needed. While a generation is quarantined a
      // foreground request deliberately misses the adopted copy and renders fresh bytes; returning
      // here would leave the suspect PNG on disk, and once verification passed on OTHER sampled
      // keys
      // the read path would serve it again the moment the fresh copy fell out of memory.
      if (name in present && !(replaceExisting && name in adopted)) return
      // Optimizer admission prevents duplicate warming, but foreground renders can still complete
      // on two zero-downtime replicas at once. Serialize writes for the whole generation across
      // processes. This is try-lock rather than lock: persistence is best-effort and a visitor must
      // never wait for another replica's disk write.
      val generationWriteLock = tryGenerationWriteLock() ?: return
      val target = File(dir, "$name$PNG_SUFFIX")
      // Writer-unique, because the zero-downtime rollout puts two processes on this volume at once.
      // A temp path shared by cache key lets one replica rename the inode while the other is still
      // writing it, publishing a half-PNG under a name that claims to be complete — and a reader
      // then promotes those bytes as a valid render.
      val temp = File(dir, "$name.${writerId}-${tempSequence.incrementAndGet()}$TEMP_SUFFIX")
      val existingSize = target.length()
      try {
        temp.writeBytes(png)
        if (!temp.renameTo(target)) {
          temp.delete()
          recordFailure(system, "rename failed for $name")
          return
        }
        // The size DELTA, not the payload size: two hosts for the same fingerprint can race to
        // publish the same key during a catalog replacement and both rename over the target, but
        // only one file ends up occupying the volume.
        val previousSize = if (name in present) existingSize else 0L
        present += name
        // Replaced by this process, so it is no longer a candidate for verifying the previous one.
        adopted -= name
        writes.incrementAndGet()
        generationWrites.incrementAndGet()
        knownBytes.addAndGet(png.size.toLong() - previousSize)
      } catch (e: IOException) {
        runCatching { temp.delete() }
        recordFailure(system, e.message ?: e::class.simpleName ?: "write failed")
      } finally {
        generationWriteLock.close()
      }
    }

    /**
     * Drop this whole generation — used when load-time verification finds a cached render that no
     * longer matches what the renderer produces.
     *
     * The whole generation, not the offending entry: a mismatch means the fingerprint failed to
     * capture some input, so every entry sharing it is suspect. Keeping the rest would be trusting
     * the same broken identity that just proved untrustworthy.
     */
    fun discard(): Boolean {
      // Retried, because the contended case is transient and the consequence of giving up is not.
      // The lock is held only for the length of one PNG write, so a foreground render that happens
      // to be publishing right now clears in milliseconds; the caller, on the other hand, has just
      // proved this generation's bytes wrong, and a `false` it does not act on leaves those bytes
      // on disk to be served. See `CatalogThemeCache.verifySample`, which now keeps the generation
      // quarantined when this still fails.
      var generationWriteLock = tryGenerationWriteLock()
      var attempt = 0
      while (generationWriteLock == null && attempt < DISCARD_LOCK_ATTEMPTS) {
        attempt++
        runCatching { Thread.sleep(DISCARD_LOCK_BACKOFF_MILLIS) }
        generationWriteLock = tryGenerationWriteLock()
      }
      if (generationWriteLock == null) return false
      try {
        present.clear()
        adopted.clear()
        // Measured before the delete and subtracted, or the census would carry the discarded
        // generation's bytes plus its rebuilt replacement until the next sweep — making the one
        // number an operator uses to judge occupancy roughly twice the truth.
        knownBytes.addAndGet(-dir.sizeOnDisk())
        return runCatching {
            // The PNGs go; the DIRECTORY stays. This generation object remains attached to a live
            // CatalogThemeCache, and deleting the directory under it would make every later `put`
            // fail its temp write, catch the IOException and persist nothing — so the optimizer
            // would
            // re-render the whole catalog into memory alone and lose it all again at restart. The
            // point of discarding is to stop trusting these bytes, not to stop writing new ones.
            // EVERY child must go. A partial discard leaves stale PNGs under a fingerprint this
            // process has already decided it cannot trust, and the next restart adopts them again —
            // reproducing the exact mismatch that triggered the discard, indefinitely.
            val cleared =
              dir
                .listFiles()
                ?.filterNot { it.name == GENERATION_WRITE_LOCK }
                ?.all { it.deleteRecursively() } ?: true
            if (!cleared) recordFailure(system, "could not fully discard ${dir.name}")
            cleared && (dir.isDirectory || dir.mkdirs())
          }
          .getOrDefault(false)
      } finally {
        generationWriteLock.close()
      }
    }

    private fun tryGenerationWriteLock(): AutoCloseable? {
      val randomAccess =
        runCatching { RandomAccessFile(File(dir, GENERATION_WRITE_LOCK), "rw") }.getOrNull()
          ?: return null
      val channel = randomAccess.channel
      val lock =
        try {
          channel.tryLock()
        } catch (_: OverlappingFileLockException) {
          null
        } catch (_: IOException) {
          null
        }
      if (lock == null) {
        runCatching { channel.close() }
        runCatching { randomAccess.close() }
        return null
      }
      return AutoCloseable {
        runCatching { lock.release() }
        runCatching { channel.close() }
        runCatching { randomAccess.close() }
      }
    }

    /**
     * What this generation has actually done, for `/status`.
     *
     * [ThemeCacheGenerationSnapshot.adopted] is the load-bearing number: it is the only evidence
     * that persistence carried anything across a process boundary at all.
     */
    fun stats(): ThemeCacheGenerationSnapshot =
      ThemeCacheGenerationSnapshot(
        fingerprint = fingerprint,
        adopted = loadedEntries,
        entries = present.size,
        hits = generationHits.get(),
        misses = generationMisses.get(),
        writes = generationWrites.get(),
      )

    private fun fileName(cacheKey: String): String =
      MessageDigest.getInstance("SHA-256").digest(cacheKey.toByteArray()).joinToString("") {
        "%02x".format(it)
      }
  }

  /** A generation's coordinates, for [sweep]'s live set. */
  data class GenerationId(val system: String, val fingerprint: String)

  private fun GenerationId.dir(): File? {
    val safeSystem = system.safeName() ?: return null
    val safeFingerprint = fingerprint.safeName() ?: return null
    return File(File(root, safeSystem), safeFingerprint)
  }

  companion object {
    const val DEFAULT_MAX_BYTES: Long = 8L * 1024 * 1024 * 1024

    /** Long enough to cover a rollout's readiness window, short enough to reclaim the same day. */
    const val DEFAULT_SWEEP_GRACE_MILLIS: Long = 60L * 60 * 1000
    const val MANIFEST_NAME: String = "manifest.json"
    const val MAX_REASON_CHARS: Int = 200
    private const val PNG_SUFFIX = ".png"
    private const val TEMP_SUFFIX = ".png.tmp"
    private const val GENERATION_WRITE_LOCK = ".write.lock"
    /**
     * Bounded retry for [Generation.discard]'s write lock.
     *
     * The lock is held for one PNG write, so ~1s of retries covers a foreground render that happens
     * to be mid-publish many times over. Bounded rather than blocking because the caller is the
     * idle verification task, not a request: it must not wedge behind a pathologically stuck
     * writer, and a genuine failure has a correct handling (keep the generation quarantined).
     */
    private const val DISCARD_LOCK_ATTEMPTS = 20
    private const val DISCARD_LOCK_BACKOFF_MILLIS = 50L

    /**
     * Names that may become a directory under the store root.
     *
     * A system id reaches here from deployment config and a fingerprint from a digest, so neither
     * is attacker-controlled today — but both name a path, and a component that can contain `..` or
     * a separator is a directory traversal waiting for the day one of them is. Rejected rather than
     * sanitised: a silently rewritten name would let two catalogs share a generation.
     */
    private val SAFE_NAME = Regex("[A-Za-z0-9._-]{1,128}")

    private fun String.safeName(): String? = takeIf {
      it.isNotEmpty() && it != "." && it != ".." && SAFE_NAME.matches(it)
    }

    private fun File.sizeOnDisk(): Long = runCatching {
      walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
      .getOrDefault(0L)
  }
}

/**
 * What a generation was fingerprinted from, recorded beside its PNGs.
 *
 * Never read to decide validity — the directory name is that decision. This exists so an operator
 * can answer "why did the cache reset" from the box itself.
 */
@Serializable
data class GenerationInputs(
  val system: String,
  val fingerprint: String,
  val toolVersion: String,
  val variant: String,
  val renderConfig: String,
  val createdAtEpochMillis: Long = 0,
)

/** What one [ThemeCacheStore.sweep] reclaimed. */
data class SweepResult(
  val deletedGenerations: Int,
  val reclaimedBytes: Long,
  val bytes: Long,
  val overCap: Boolean,
)

/**
 * What one catalog generation's disk tier has done this process, for `/status.json`.
 *
 * The point of publishing this per catalog rather than only store-wide: a disk cache that is
 * working and one that is pure write amplification produce the same store-wide `writes`, and the
 * difference between them is visible only here. Read it in this order:
 * - [adopted] `0` after a restart that should have found a warm generation ⇒ the **key** moved.
 *   Compare [fingerprint] with the previous process's; if it changed while nothing about the
 *   catalog or the server did, some input the digest reads is unstable, and every write this
 *   process makes is being left for a sweep to reclaim.
 * - [adopted] high but [hits] `0` ⇒ the entries are there and nothing is reading them: either
 *   nothing asked for those keys, or the generation is still quarantined pending verification.
 * - [writes] climbing with [adopted] `0` on every restart is the "disk I/O for nothing" case,
 *   stated in two numbers.
 */
@Serializable
data class ThemeCacheGenerationSnapshot(
  /** The generation directory this catalog is reading and writing — its cache key. */
  val fingerprint: String,
  /** Renders already on disk when this process opened the generation. */
  val adopted: Int,
  /** Renders on disk now, adopted plus written since. */
  val entries: Int,
  /** Reads this process served from disk. */
  val hits: Long,
  /** Reads that went to disk and found nothing. */
  val misses: Long,
  /** Renders this process wrote to disk. */
  val writes: Long,
)

/** Disk-tier counters for `/status.json` (`themeCache`). */
@Serializable
data class ThemeCacheStoreSnapshot(
  val root: String,
  val generations: Int,
  /**
   * Generation directories per system, as of the last sweep.
   *
   * More than one for a system that has only ever been served one way is fingerprint churn, and
   * churn is the failure mode that reports itself as success everywhere else.
   */
  val generationsBySystem: Map<String, Int> = emptyMap(),
  val bytes: Long,
  val maxBytes: Long,
  val writes: Long,
  val writeFailures: Long,
  val hits: Long,
  val misses: Long,
  val lastFailureReason: String? = null,
)
