package ee.schimke.composeai.cli.serve

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
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
  private val lastFailure = ConcurrentHashMap<String, String>()

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
    val liveDirs = live.mapNotNull { it.dir() }.toSet()
    var deleted = 0
    var reclaimed = 0L
    var survivingBytes = 0L
    var survivingGenerations = 0

    for (systemDir in root.listFiles()?.filter { it.isDirectory }.orEmpty()) {
      val generationDirs = systemDir.listFiles()?.filter { it.isDirectory }.orEmpty()
      // A system the caller has no current generation for is left entirely alone. Absence from the
      // live set means "we did not load this catalog", which is not the same as "this catalog's
      // warmed renders are garbage" — a load can fail transiently, and its cache must outlive that.
      if (onlySystems != null && systemDir.name !in onlySystems) {
        survivingBytes += systemDir.sizeOnDisk()
        survivingGenerations += generationDirs.size
        continue
      }
      for (generationDir in generationDirs) {
        val size = generationDir.sizeOnDisk()
        if (generationDir in liveDirs) {
          survivingBytes += size
          survivingGenerations++
          continue
        }
        if (generationDir.deleteRecursively()) {
          deleted++
          reclaimed += size
        }
      }
      // A system directory left empty by the sweep is itself garbage.
      if (systemDir.listFiles()?.isEmpty() == true) systemDir.delete()
    }

    val total = survivingBytes
    knownBytes.set(total)
    knownGenerations.set(survivingGenerations)
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
      bytes = knownBytes.get(),
      maxBytes = maxBytes,
      writes = writes.get(),
      writeFailures = writeFailures.get(),
      hits = hits.get(),
      misses = misses.get(),
      lastFailureReason = lastFailure["reason"],
    )

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

    fun contains(cacheKey: String): Boolean = fileName(cacheKey) in present

    fun get(cacheKey: String): ByteArray? {
      val name = fileName(cacheKey)
      if (name !in present) {
        misses.incrementAndGet()
        return null
      }
      val bytes = runCatching { File(dir, "$name$PNG_SUFFIX").readBytes() }.getOrNull()
      if (bytes == null) {
        // On disk a moment ago and unreadable now — a sweep, an external delete, a truncated write.
        // Forget it so the optimizer treats it as work still to do rather than as permanently
        // cached-but-broken.
        present.remove(name)
        misses.incrementAndGet()
        return null
      }
      hits.incrementAndGet()
      return bytes
    }

    /**
     * Persist one render. Best-effort and never throws: a failed write costs a re-render later,
     * which is strictly better than failing the render that just succeeded.
     *
     * Written to a temp file and renamed, so a crash or a full disk leaves no half-PNG that a later
     * process would read as a valid cached render.
     */
    fun put(cacheKey: String, png: ByteArray) {
      val name = fileName(cacheKey)
      if (name in present) return
      val target = File(dir, "$name$PNG_SUFFIX")
      val temp = File(dir, "$name$TEMP_SUFFIX")
      try {
        temp.writeBytes(png)
        if (!temp.renameTo(target)) {
          temp.delete()
          recordFailure(system, "rename failed for $name")
          return
        }
        present += name
        writes.incrementAndGet()
        knownBytes.addAndGet(png.size.toLong())
      } catch (e: IOException) {
        runCatching { temp.delete() }
        recordFailure(system, e.message ?: e::class.simpleName ?: "write failed")
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
      present.clear()
      // Measured before the delete and subtracted, or the census would carry the discarded
      // generation's bytes plus its rebuilt replacement until the next sweep — making the one
      // number an operator uses to judge occupancy roughly twice the truth.
      knownBytes.addAndGet(-dir.sizeOnDisk())
      return runCatching {
          // The PNGs go; the DIRECTORY stays. This generation object remains attached to a live
          // CatalogThemeCache, and deleting the directory under it would make every later `put`
          // fail its temp write, catch the IOException and persist nothing — so the optimizer would
          // re-render the whole catalog into memory alone and lose it all again at restart. The
          // point of discarding is to stop trusting these bytes, not to stop writing new ones.
          dir.listFiles()?.forEach { it.deleteRecursively() }
          dir.isDirectory || dir.mkdirs()
        }
        .getOrDefault(false)
    }

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
    const val MANIFEST_NAME: String = "manifest.json"
    const val MAX_REASON_CHARS: Int = 200
    private const val PNG_SUFFIX = ".png"
    private const val TEMP_SUFFIX = ".png.tmp"

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

/** Disk-tier counters for `/status.json` (`themeCache`). */
@Serializable
data class ThemeCacheStoreSnapshot(
  val root: String,
  val generations: Int,
  val bytes: Long,
  val maxBytes: Long,
  val writes: Long,
  val writeFailures: Long,
  val hits: Long,
  val misses: Long,
  val lastFailureReason: String? = null,
)
