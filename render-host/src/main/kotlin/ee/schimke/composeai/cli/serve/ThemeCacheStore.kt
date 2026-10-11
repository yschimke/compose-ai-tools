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
 * On-disk home for rendered theme PNGs, so warming survives server restarts and catalog reloads
 * (which drop the in-memory [CatalogThemeCache]).
 *
 * ```
 * <root>/<system>/<fingerprint>/manifest.json
 * <root>/<system>/<fingerprint>/<sha256(cacheKey)>.png
 * ```
 *
 * A **generation** is one `(system, fingerprint)` pair ([ThemeCacheFingerprint]). Generations are
 * never mutated in place: a new revision writes a new directory and the old one is swept, so
 * invalidation is structural. The manifest only records inputs so a reset is explainable.
 */
public class ThemeCacheStore(
  private val root: File,
  /**
   * Ceiling for the whole store, enforced by [sweep] rather than at write time so writes never
   * block on a byte census.
   */
  private val maxBytes: Long = DEFAULT_MAX_BYTES,
  /**
   * How recently a generation must have been created to survive [sweep] even when unused here: on a
   * zero-downtime rollout the new replica shares the volume and must not reclaim the cache of the
   * replica still serving.
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
  // Census published by [sweep] and advanced by each write; see [snapshot].
  private val knownBytes = AtomicLong()
  private val knownGenerations = java.util.concurrent.atomic.AtomicInteger()
  /**
   * Generation directories per system as of the last [sweep]. A count that climbs with restarts
   * means the fingerprint is churning and the cache is buying disk I/O and nothing else.
   */
  private val knownGenerationsBySystem = AtomicReference<Map<String, Int>>(emptyMap())
  private val lastFailure = ConcurrentHashMap<String, String>()
  private val tempSequence = AtomicLong()
  /** Cache for [evictedAtEpochMillis]; -1 until the marker has been looked for. */
  private val evictedAt = AtomicLong(-1)
  /** Distinguishes this process's in-flight writes from a concurrently deployed replica's. */
  private val writerId: String =
    ProcessHandle.current().pid().toString(36) + "-" + System.identityHashCode(this).toString(36)

  /**
   * Open (creating if needed) the generation for [system] at [fingerprint], or null when the store
   * is unusable: persistence is an optimisation, so a read-only or full disk must not fail serving.
   */
  public fun open(system: String, fingerprint: String, inputs: GenerationInputs): Generation? {
    val safeSystem = system.safeName() ?: return null
    val safeFingerprint = fingerprint.safeName() ?: return null
    val dir = File(File(root, safeSystem), safeFingerprint)
    if (!runCatching { dir.mkdirs() }.getOrDefault(false) && !dir.isDirectory) {
      recordFailure(system, "could not create $dir")
      return null
    }
    val marked = writeManifest(dir, inputs)
    knownGenerations.incrementAndGet()
    knownGenerationsBySystem.getAndUpdate { it + (system to (it[system] ?: 0) + 1) }
    knownBytes.addAndGet(dir.sizeOnDisk())
    return Generation(dir, system, markAllDirtyOnOpen = marked == MarkOutcome.UNRECORDED)
  }

  /**
   * Write the generation's manifest, or refresh it when a different build opens the same generation
   * (releases adopt their predecessor's renders, see [ThemeCacheFingerprint]).
   *
   * The rewrite happens at open, not first write, because [dirtyBeforeEpochMillis] must be on disk
   * before any render is served from this directory. So [GenerationInputs.toolVersion] is the build
   * that last opened the generation, not the one that made the pixels.
   * [GenerationInputs.createdAtEpochMillis] is preserved so the sweep's grace window still works.
   */
  private fun writeManifest(dir: File, inputs: GenerationInputs): MarkOutcome {
    val file = File(dir, MANIFEST_NAME)
    val existing =
      if (file.isFile) {
        runCatching { json.decodeFromString(GenerationInputs.serializer(), file.readText()) }
          .getOrNull()
      } else {
        null
      }
    // Anything older than the last eviction plus the grace window is untrusted ([evictAll]); the
    // grace covers the outgoing replica's writes, which land after the eviction instant.
    val evictionBoundary = evictedAtEpochMillis().takeIf { it > 0 }?.plus(graceMillis) ?: 0L
    // Rewrite an unreadable manifest. The eviction clause catches an evict-and-restart without a
    // release, where the same-build early return would otherwise keep a stale (usually zero)
    // boundary.
    if (
      existing != null &&
        existing.toolVersion == inputs.toolVersion &&
        existing.dirtyBeforeEpochMillis >= evictionBoundary
    )
      return MarkOutcome.NOT_NEEDED
    val createdAt = existing?.createdAtEpochMillis?.takeIf { it > 0 } ?: clock()
    // Renders exist that this build may not have written; a missing or corrupt manifest means
    // unknown ownership, which must not be read as "ours".
    val inherited =
      existing != null || dir.listFiles()?.any { it.name.endsWith(PNG_SUFFIX) } == true
    // A different build wrote what is here, so it is all dirty. `+ graceMillis` rather than
    // `clock()` because the outgoing replica keeps rendering into this directory during a
    // zero-downtime rollout; some of our own early renders get re-rendered once, which is the safe
    // direction to be wrong. `maxOf` with the eviction boundary because a generation can be both
    // cross-build and evicted.
    val boundary = maxOf(if (inherited) clock() + graceMillis else 0L, evictionBoundary)
    val wrote = runCatching {
      file.writeText(
        json.encodeToString(
          inputs.copy(createdAtEpochMillis = createdAt, dirtyBeforeEpochMillis = boundary)
        )
      )
    }
      .onFailure { recordFailure(dir.name, "manifest: ${it.message}") }
      .isSuccess
    // A cross-build open whose boundary did not reach disk must not trust anything present: open
    // with everything marked dirty in memory rather than re-reading the previous (often zero)
    // boundary.
    return when {
      // Nothing on disk to be on the wrong side of either boundary.
      !inherited -> MarkOutcome.NOT_NEEDED
      wrote -> MarkOutcome.RECORDED
      else -> MarkOutcome.UNRECORDED
    }
  }

  /** Whether a cross-build open managed to record its dirty boundary. */
  private enum class MarkOutcome {
    /** This build created the generation, or already owns the manifest: nothing to mark. */
    NOT_NEEDED,
    /** A different build's renders were here and the boundary is on disk. */
    RECORDED,
    /** A different build's renders were here and the boundary could NOT be written. */
    UNRECORDED,
  }

  /**
   * The dirty boundary recorded in [dir]'s manifest, or 0. Read from disk because the boundary
   * belongs to the generation (the first replica to open it set it), not to this process.
   */
  private fun dirtyBoundary(dir: File): Long = runCatching {
    json
      .decodeFromString(GenerationInputs.serializer(), File(dir, MANIFEST_NAME).readText())
      .dirtyBeforeEpochMillis
  }
    .getOrDefault(0L)

  /**
   * Delete every generation unconditionally and report how many went — the escape hatch for pixels
   * known to be wrong in ways no fingerprint sees. [sweep] can't do this: it spares fresh
   * generations.
   *
   * During a zero-downtime rollout the outgoing replica keeps writing to the shared volume after
   * the delete, so the eviction also leaves a mark ([EVICTED_NAME]): later opens treat anything
   * written before it plus the grace window as dirty. The mark is not a lock; it makes those writes
   * untrusted.
   */
  public fun evictAll(): Int {
    var deleted = 0
    for (systemDir in root.listFiles()?.filter { it.isDirectory }.orEmpty()) {
      for (generationDir in systemDir.listFiles()?.filter { it.isDirectory }.orEmpty()) {
        if (generationDir.deleteRecursively()) deleted++
        else recordFailure(systemDir.name, "could not evict ${generationDir.name}")
      }
      if (systemDir.listFiles()?.isEmpty() == true) systemDir.delete()
    }
    // Stamped after the deletion, so a crash midway reads as un-evicted rather than half-evicted.
    val at = clock()
    val stamped = runCatching { File(root, EVICTED_NAME).writeText(at.toString()) }.isSuccess
    if (stamped) evictedAt.set(at)
    // Without the mark the old replica's repopulated renders can't be made dirty; say so.
    else recordFailure("store", "evicted but could not record the boundary in $EVICTED_NAME")
    knownGenerations.set(0)
    knownGenerationsBySystem.set(emptyMap())
    knownBytes.set(0)
    return deleted
  }

  /**
   * When this store was last evicted, or 0. Read from disk once and cached; only [evictAll] in this
   * process changes it.
   */
  private fun evictedAtEpochMillis(): Long = evictedAt.updateAndGet { cached ->
    if (cached >= 0) cached
    else
      runCatching { File(root, EVICTED_NAME).readText().trim().toLong() }
        .getOrDefault(0L)
        .coerceAtLeast(0L)
  }

  /**
   * Delete every generation not in [live], and report whether what remains fits [maxBytes].
   *
   * A live generation is never deleted, not even to fit the cap — that would make the optimizer
   * re-render what was just discarded forever. An over-cap live set is reported
   * ([SweepResult.overCap]) as a configuration problem instead.
   */
  public fun sweep(live: Set<GenerationId>, onlySystems: Set<String>? = null): SweepResult {
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
      // A system with no current generation is left alone: its load may have failed transiently.
      if (onlySystems != null && systemDir.name !in onlySystems) {
        survivingBytes += systemDir.sizeOnDisk()
        survivingGenerations += generationDirs.size
        if (generationDirs.isNotEmpty()) survivingBySystem[systemDir.name] = generationDirs.size
        continue
      }
      for (generationDir in generationDirs) {
        val size = generationDir.sizeOnDisk()
        // Survivors: ours; young (a rolling-update replica may still be serving them); and
        // undeletable (still on the volume, so they must stay in the census).
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
    // Merged, not assigned: optimizers write concurrently with the sweep, and a bare `set` could
    // drop writes that landed during the scan.
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
  public fun systems(): Set<String> =
    root.listFiles()?.filter { it.isDirectory }?.map { it.name }?.toSet().orEmpty()

  /**
   * Disk occupancy as of the last sweep plus writes since. Deliberately not a live census:
   * `/status.json` is polled and walking tens of thousands of files per request is too costly.
   */
  public fun snapshot(): ThemeCacheStoreSnapshot =
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
   * Generation creation time from its manifest, else the directory timestamp, else "now" — an
   * unreadable age must read as young so it errs toward keeping another replica's cache.
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
  public inner class Generation
  internal constructor(
    private val dir: File,
    private val system: String,
    /**
     * Treat everything on disk as dirty regardless of the manifest; set when a cross-build open
     * could not record its boundary ([writeManifest]).
     */
    private val markAllDirtyOnOpen: Boolean = false,
  ) {

    /**
     * Cache keys on disk, read once at open; a listing per lookup would be slower than the renders.
     */
    private val present: MutableSet<String> =
      java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>()).apply {
        dir
          .listFiles()
          ?.filter { it.isFile && it.name.endsWith(PNG_SUFFIX) }
          ?.forEach { add(it.name.removeSuffix(PNG_SUFFIX)) }
      }

    /** How many renders were already on disk when this generation was opened. */
    public val loadedEntries: Int = present.size

    /** This generation's directory name — the fingerprint it was opened under. */
    public val fingerprint: String = dir.name

    /**
     * Renders written before this instant came from another build and are dirty: servable once the
     * sample verifies them, but queued for re-render. Volatile: [markAllDirty] moves it
     * concurrently.
     */
    @Volatile private var dirtyBefore: Long = dirtyBoundary(dir)

    /**
     * Dirty file names, resolved once at open and maintained in memory (per-query `stat`s made
     * `/status` too slow). An entry only becomes clean when this process rewrites it.
     */
    private val dirtyNames: MutableSet<String> =
      java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>()).apply {
        val boundary = dirtyBefore
        if (markAllDirtyOnOpen) {
          addAll(present)
        } else if (boundary > 0L) {
          addAll(
            present.filter { name ->
              val modified = File(dir, "$name$PNG_SUFFIX").lastModified()
              // An unreadable timestamp is treated as dirty.
              modified == 0L || modified < boundary
            }
          )
        }
      }

    /**
     * Renders written while the outgoing replica of a rolling update could still be writing, with
     * each file's mtime right after our write. A later mismatch means the other replica renamed
     * over it, which classification at open can't see. Only populated while [dirtyBefore] is in the
     * future.
     */
    private val atRiskWrites = ConcurrentHashMap<String, Long>()

    /**
     * Re-check renders published during a rollout overlap once it is over; anything that changed
     * under us goes back on the dirty queue.
     *
     * No early return on an empty map: the convergence clear below is try-lock and may have been
     * deferred, and returning early would strand a future-dated boundary in the manifest.
     */
    private fun reconcileAtRiskWrites() {
      if (clock() < dirtyBefore) return
      for ((name, writtenAt) in atRiskWrites.toList()) {
        atRiskWrites.remove(name)
        if (name !in present) continue
        val modified = runCatching { File(dir, "$name$PNG_SUFFIX").lastModified() }.getOrDefault(0L)
        // Unreadable timestamp: treat as not ours.
        if (modified != writtenAt) dirtyNames += name
      }
      // Reconcile can be the last step to convergence, so it must attempt the clear too.
      clearBoundaryIfConvergedUnderLock()
    }

    /**
     * Drop the durable boundary once every render here is this process's and nothing from a rollout
     * overlap is unverified. Called on both paths that can reach convergence.
     *
     * The caller must hold the generation write lock: [markAllDirty] inverts this exact condition.
     */
    private fun clearBoundaryIfConverged() {
      if (dirtyBefore > 0L && dirtyNames.isEmpty() && atRiskWrites.isEmpty()) clearDirtyBoundary()
    }

    /**
     * [clearBoundaryIfConverged] for callers not already holding the generation write lock (the
     * reconcile on the status path). The decision is re-taken under the lock so a concurrent
     * [markAllDirty] isn't erased; the unlocked pre-check is only a cheap early exit.
     */
    private fun clearBoundaryIfConvergedUnderLock() {
      if (dirtyBefore == 0L || dirtyNames.isNotEmpty() || atRiskWrites.isNotEmpty()) return
      val generationWriteLock = tryGenerationWriteLock() ?: return
      try {
        clearBoundaryIfConverged()
      } finally {
        generationWriteLock.close()
      }
    }

    /** Whether [cacheKey] is on disk from an older build and has not been re-rendered since. */
    public fun isDirty(cacheKey: String): Boolean = isDirtyName(fileName(cacheKey))

    /**
     * [isDirty] for a hashed file name. Internal walks must use this: hashing an already-hashed
     * name finds no file and reports everything dirty.
     */
    private fun isDirtyName(name: String): Boolean = name in dirtyNames

    /**
     * How many renders on disk are still an older build's work; also drives the rollout reconcile.
     */
    public fun dirtyCount(): Int {
      reconcileAtRiskWrites()
      return dirtyNames.size
    }

    /**
     * Mark every render on disk dirty by moving the boundary to now — the operator's "regenerate
     * this catalog". Not a delete: entries keep serving while the background pass replaces them.
     */
    public fun markAllDirty(): Int {
      // Under the generation write lock: a concurrent `put` reaching convergence would otherwise
      // clear the boundary this mark just wrote.
      val generationWriteLock = tryGenerationWriteLock() ?: return -1
      try {
        val now = clock()
        // Persist first and refuse the mark if it didn't land; the API promises the request
        // survives a restart.
        val persisted = runCatching {
          val file = File(dir, MANIFEST_NAME)
          val existing = json.decodeFromString(GenerationInputs.serializer(), file.readText())
          file.writeText(json.encodeToString(existing.copy(dirtyBeforeEpochMillis = now)))
        }
          .onFailure { recordFailure(system, "manifest: ${it.message}") }
          .isSuccess
        if (!persisted) return -1
        dirtyBefore = now
        // Everything on disk predates a boundary set to now, so the whole of `present` is dirty.
        dirtyNames.addAll(present)
        // A mark supersedes the overlap bookkeeping.
        atRiskWrites.clear()
        return dirtyNames.size
      } finally {
        generationWriteLock.close()
      }
    }

    // Per-generation counters: a single catalog with an unstable fingerprint hides behind healthy
    // store-wide totals.
    private val generationHits = AtomicLong()
    private val generationMisses = AtomicLong()
    private val generationWrites = AtomicLong()

    /**
     * Renders on disk when this generation was opened (written by another process) — the only ones
     * whose trustworthiness is in question.
     */
    private val adopted: MutableSet<String> =
      java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>()).apply {
        addAll(present)
      }

    /** Whether [cacheKey] came from a previous process rather than from this one. */
    public fun wasAdopted(cacheKey: String): Boolean = fileName(cacheKey) in adopted

    public fun contains(cacheKey: String): Boolean = fileName(cacheKey) in present

    public fun get(cacheKey: String): ByteArray? {
      val name = fileName(cacheKey)
      if (name !in present) {
        misses.incrementAndGet()
        generationMisses.incrementAndGet()
        return null
      }
      val bytes = runCatching { File(dir, "$name$PNG_SUFFIX").readBytes() }.getOrNull()
      if (bytes == null) {
        // Vanished or unreadable: forget it so the optimizer treats it as work still to do.
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
     * Persist one render. Best-effort and never throws; temp file + rename so a crash or full disk
     * never leaves a half-PNG that reads as valid.
     */
    public fun put(cacheKey: String, png: ByteArray, replaceExisting: Boolean = false) {
      val name = fileName(cacheKey)
      // Presence alone isn't enough: a quarantined adopted copy or a dirty entry must be replaced,
      // or the suspect bytes stay on disk and regeneration never clears a flag.
      if (name in present && !(replaceExisting && (name in adopted || name in dirtyNames))) return
      // Two zero-downtime replicas can finish the same render; serialize writes per generation
      // across processes. Try-lock: a visitor must never wait on another replica's disk write.
      val generationWriteLock = tryGenerationWriteLock() ?: return
      val target = File(dir, "$name$PNG_SUFFIX")
      // Writer-unique temp name: a shared one lets two replicas interleave and publish a half-PNG.
      val temp = File(dir, "$name.${writerId}-${tempSequence.incrementAndGet()}$TEMP_SUFFIX")
      val existingSize = target.length()
      try {
        temp.writeBytes(png)
        if (!temp.renameTo(target)) {
          temp.delete()
          recordFailure(system, "rename failed for $name")
          return
        }
        // Size delta, not payload size: racing hosts may both rename over the same key.
        val previousSize = if (name in present) existingSize else 0L
        present += name
        // Replaced by this process, so it is no longer a candidate for verifying the previous one.
        adopted -= name
        // The only way an entry becomes clean.
        dirtyNames -= name
        // Inside the overlap window, remember what we wrote so a co-replica's overwrite can be
        // detected. Strictly before the boundary, which also keeps no-grace configurations out of
        // this entirely.
        if (clock() < dirtyBefore) atRiskWrites[name] = target.lastModified()
        // Converged: clear the durable (possibly future-dated) boundary, or the next start would
        // reclassify this build's own renders as dirty.
        clearBoundaryIfConverged()
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
     * Drop this whole generation when load-time verification finds a mismatch: the fingerprint
     * missed some input, so every entry sharing it is suspect.
     */
    /**
     * Delete only the dirty renders, keeping everything this build wrote; returns the count, or -1
     * if the write lock could not be taken.
     */
    public fun discardDirty(): Int {
      if (dirtyBefore <= 0L) return 0
      return discardNames(dirtyNames.toList(), "dirty")
    }

    /**
     * Delete everything adopted at open, keeping only what this process rendered since — the
     * correct response to a failed sample, and a superset of [discardDirty]. The sample tests
     * renders from another process, which a same-version restart would otherwise inherit as clean.
     */
    public fun discardAdopted(): Int = discardNames(adopted.toList(), "adopted")

    /**
     * Delete [names] under the generation write lock, all-or-nothing, reporting how many went or -1
     * if any delete or the lock itself failed.
     */
    private fun discardNames(names: List<String>, label: String): Int {
      var generationWriteLock = tryGenerationWriteLock()
      var attempt = 0
      while (generationWriteLock == null && attempt < DISCARD_LOCK_ATTEMPTS) {
        attempt++
        runCatching { Thread.sleep(DISCARD_LOCK_BACKOFF_MILLIS) }
        generationWriteLock = tryGenerationWriteLock()
      }
      if (generationWriteLock == null) return -1
      try {
        var removed = 0
        var failed = 0
        for (name in names) {
          val file = File(dir, "$name$PNG_SUFFIX")
          val size = file.length()
          if (runCatching { !file.exists() || file.delete() }.getOrDefault(false)) {
            present.remove(name)
            adopted.remove(name)
            dirtyNames.remove(name)
            knownBytes.addAndGet(-size)
            removed++
          } else {
            failed++
            recordFailure(system, "could not discard $label ${file.name}")
          }
        }
        // All or nothing: the caller lifts the read quarantine on success, so a leftover stale PNG
        // would be served.
        if (failed > 0) return -1
        // Nothing older than the boundary survives, so clear it.
        clearDirtyBoundary()
        return removed
      } finally {
        generationWriteLock.close()
      }
    }

    private fun clearDirtyBoundary() {
      dirtyBefore = 0L
      dirtyNames.clear()
      runCatching {
        val file = File(dir, MANIFEST_NAME)
        val existing = json.decodeFromString(GenerationInputs.serializer(), file.readText())
        file.writeText(json.encodeToString(existing.copy(dirtyBeforeEpochMillis = 0L)))
      }
        .onFailure { recordFailure(system, "manifest: ${it.message}") }
    }

    public fun discard(): Boolean {
      // Retried: the lock is held for one PNG write, and giving up would leave proven-stale bytes
      // on disk.
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
        // The manifest goes too, so the in-memory boundary must go as well.
        dirtyBefore = 0L
        dirtyNames.clear()
        // Subtract before deleting, or the census double-counts until the next sweep.
        knownBytes.addAndGet(-dir.sizeOnDisk())
        return runCatching {
            // The PNGs go but the directory stays: this generation is still attached to a live
            // cache, and later `put`s need it. Every child must go, or the next restart re-adopts
            // the stale ones.
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
     * What this generation has done, for `/status`. [ThemeCacheGenerationSnapshot.adopted] is the
     * only evidence persistence carried anything across a process boundary.
     */
    public fun stats(): ThemeCacheGenerationSnapshot =
      ThemeCacheGenerationSnapshot(
        fingerprint = fingerprint,
        adopted = loadedEntries,
        entries = present.size,
        hits = generationHits.get(),
        misses = generationMisses.get(),
        writes = generationWrites.get(),
      )

    /**
     * `cacheKey` -> stored file name, memoized because [contains] runs per themed render on
     * `/status`.
     *
     * Only names this generation actually holds are remembered, so visitor-minted keys that
     * `CatalogThemeCache.put` refuses to persist can't grow the heap.
     */
    private val fileNames = ConcurrentHashMap<String, String>()

    private fun fileName(cacheKey: String): String {
      fileNames[cacheKey]?.let {
        return it
      }
      // `HexFormat`, not `"%02x".format`: same output (so existing names resolve) without a
      // `Formatter` parse per byte.
      val name = HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(cacheKey.toByteArray()))
      if (name in present || name in adopted) fileNames[cacheKey] = name
      return name
    }
  }

  /** A generation's coordinates, for [sweep]'s live set. */
  public data class GenerationId(val system: String, val fingerprint: String)

  private fun GenerationId.dir(): File? {
    val safeSystem = system.safeName() ?: return null
    val safeFingerprint = fingerprint.safeName() ?: return null
    return File(File(root, safeSystem), safeFingerprint)
  }

  public companion object {
    public const val DEFAULT_MAX_BYTES: Long = 8L * 1024 * 1024 * 1024

    /** Long enough to cover a rollout's readiness window, short enough to reclaim the same day. */
    public const val DEFAULT_SWEEP_GRACE_MILLIS: Long = 60L * 60 * 1000
    public const val MANIFEST_NAME: String = "manifest.json"

    /**
     * Store-root marker with the epoch millis of the last [evictAll]. At the root because it
     * applies to generations not yet written; plain text so an operator can read or clear it by
     * hand.
     */
    public const val EVICTED_NAME: String = "evicted-at"
    public const val MAX_REASON_CHARS: Int = 200
    private const val PNG_SUFFIX = ".png"
    private const val TEMP_SUFFIX = ".png.tmp"
    private const val GENERATION_WRITE_LOCK = ".write.lock"
    /**
     * Bounded retry for [Generation.discard]'s write lock (~1s); the caller is an idle task that
     * must not wedge behind a stuck writer.
     */
    private const val DISCARD_LOCK_ATTEMPTS = 20
    private const val DISCARD_LOCK_BACKOFF_MILLIS = 50L

    /**
     * Names that may become a directory under the store root. Rejected rather than sanitised: a
     * rewritten name could let two catalogs share a generation, and `..` or separators would
     * traverse.
     */
    /** Lowercase, no separators — the shape `"%02x".format(byte)` produced, and stateless. */
    private val HEX: java.util.HexFormat = java.util.HexFormat.of()

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
 * What a generation was fingerprinted from, recorded beside its PNGs so an operator can explain a
 * cache reset. Never read to decide validity.
 */
@Serializable
public data class GenerationInputs(
  val system: String,
  val fingerprint: String,
  /**
   * The build that last opened this generation (stamped at open, see
   * [ThemeCacheStore.writeManifest]) — not necessarily the author of its pixels.
   */
  val toolVersion: String,
  val variant: String,
  val renderConfig: String,
  val createdAtEpochMillis: Long = 0,
  /**
   * Renders older than this came from another build and are dirty. Derived from file timestamps
   * rather than tracked per entry: re-rendering moves an entry past the boundary for free. Zero
   * means nothing is dirty.
   */
  val dirtyBeforeEpochMillis: Long = 0,
)

/** What one [ThemeCacheStore.sweep] reclaimed. */
public data class SweepResult(
  val deletedGenerations: Int,
  val reclaimedBytes: Long,
  val bytes: Long,
  val overCap: Boolean,
)

/**
 * One catalog generation's disk tier activity, for `/status.json`. Reading it:
 * - [adopted] `0` after a restart that should have been warm ⇒ the fingerprint moved.
 * - [adopted] high but [hits] `0` ⇒ nothing is reading the entries, or still quarantined.
 * - [writes] climbing with [adopted] `0` every restart ⇒ disk I/O for nothing.
 */
@Serializable
public data class ThemeCacheGenerationSnapshot(
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
public data class ThemeCacheStoreSnapshot(
  val root: String,
  val generations: Int,
  /**
   * Generation directories per system as of the last sweep; more than one usually means fingerprint
   * churn.
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
