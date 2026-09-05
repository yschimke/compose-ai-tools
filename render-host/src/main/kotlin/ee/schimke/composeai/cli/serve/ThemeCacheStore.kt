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
  /** Cache for [evictedAtEpochMillis]; -1 until the marker has been looked for. */
  private val evictedAt = AtomicLong(-1)
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
    val marked = writeManifest(dir, inputs)
    knownGenerations.incrementAndGet()
    knownGenerationsBySystem.getAndUpdate { it + (system to (it[system] ?: 0) + 1) }
    knownBytes.addAndGet(dir.sizeOnDisk())
    return Generation(dir, system, markAllDirtyOnOpen = marked == MarkOutcome.UNRECORDED)
  }

  /**
   * Write the generation's manifest, or refresh it when a **different build** has opened the same
   * generation.
   *
   * The early return used to be unconditional, on the reasoning that the directory name is the
   * decision and the manifest is only commentary. That held while the fingerprint keyed on the tool
   * version, because a new build could never reach an existing generation. It no longer does — a
   * release now adopts its predecessor's renders (see [ThemeCacheFingerprint]) — so leaving the
   * manifest alone would have it name a build that has not been near this directory since.
   *
   * What the refreshed manifest then records is the build that last **opened** the generation, and
   * [GenerationInputs.toolVersion] says so. It is deliberately not the build that last *wrote* a
   * PNG here, and the difference is load-bearing during a rollout: this rewrite happens at open,
   * before this process has rendered anything, and the incoming replica can fail readiness
   * immediately afterwards while the outgoing one carries on as the volume's only real writer. An
   * investigator reading `toolVersion` as "who made these pixels" would be pointed at a build that
   * made none of them. Deferring the rewrite to the first successful [Generation.put] would fix
   * that half and break the other: [dirtyBeforeEpochMillis] has to be on disk *before* a single
   * render is served from this directory, or the adopted set is trusted for however long the first
   * write takes. The boundary is the correctness half, so it wins, and the version is documented as
   * what it actually is.
   *
   * [GenerationInputs.createdAtEpochMillis] is preserved across the rewrite: it is what the sweep's
   * grace window reads to spare a generation belonging to a replica that is still serving, and
   * restamping it on every open would make a long-lived generation permanently young.
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
    // Everything on this volume older than the last eviction plus the rollout grace window is
    // untrusted, whoever wrote it and whatever build they were — see [evictAll]. `+ graceMillis`
    // for the same reason the cross-build boundary uses it: the writes that have to be caught are
    // the OUTGOING replica's, and those land after the eviction instant, not before it.
    val evictionBoundary = evictedAtEpochMillis().takeIf { it > 0 }?.plus(graceMillis) ?: 0L
    // An unreadable manifest beside a live generation is rewritten rather than left: the sweep
    // falls back to the directory's own timestamp for it, and a readable one is strictly better.
    //
    // The eviction clause is what stops the same-build case slipping through. An operator can evict
    // and restart WITHOUT a release — that is the ordinary shape of "I know the pixels moved" — and
    // then the outgoing replica carries this build's own version string, the early return fires on
    // it, and the boundary it repopulated the generation under is whatever the previous manifest
    // said. Which is usually zero.
    if (
      existing != null &&
        existing.toolVersion == inputs.toolVersion &&
        existing.dirtyBeforeEpochMillis >= evictionBoundary
    )
      return MarkOutcome.NOT_NEEDED
    val createdAt = existing?.createdAtEpochMillis?.takeIf { it > 0 } ?: clock()
    // Renders are here that this build did not write. Usually the manifest says so; when it is
    // MISSING OR CORRUPT it says nothing, and "says nothing" was being read as "this build created
    // the generation". A manifest write interrupted mid-flight leaves exactly that state beside a
    // full set of another build's PNGs, which would then open with a zero boundary, verify on a
    // five-entry sample, and never be regenerated. Files of unknown ownership are not ours.
    val inherited =
      existing != null || dir.listFiles()?.any { it.name.endsWith(PNG_SUFFIX) } == true
    // A DIFFERENT build is opening renders another one wrote, so everything already here is dirty.
    // On a generation this build created there is nothing older to mark, and the boundary stays at
    // zero.
    //
    // `+ graceMillis`, not `clock()`, because the rollout this deployment performs is
    // ZERO-DOWNTIME: the outgoing replica keeps serving — and keeps rendering into this same
    // directory — while the incoming one boots. Renders it writes after this instant would carry a
    // timestamp past a bare `clock()` boundary and be filed as this build's work, which is exactly
    // the stale-pixel case the boundary exists to catch, and the sample cannot catch them either
    // because it only examines what was present at open. The grace window is the same one the sweep
    // uses to decide another replica may still be live, which is the same question.
    //
    // The cost is that some of this build's OWN early renders fall under the boundary and are
    // re-rendered once. That is the right direction to be wrong in.
    //
    // `maxOf` with the eviction boundary rather than either one alone: the two answer different
    // questions ("did another BUILD write here" and "was this volume evicted under a writer that
    // is still going"), a generation can be in both states at once, and the later line is the only
    // one that makes both answers safe. The eviction half applies even to a directory this process
    // created — a generation deleted by the eviction and recreated under the same fingerprint by
    // the outgoing replica reaches here as brand new, which is precisely the case.
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
    // A cross-build open whose boundary did NOT reach the disk is the dangerous case, and it used
    // to be swallowed: the failure was recorded, `open` still handed back a usable generation, and
    // that generation re-read the PREVIOUS manifest's boundary — commonly zero — so every file
    // another build wrote was filed as this build's own work. A five-entry sample then verifies the
    // generation and a renderer change outside the sample serves stale pixels for the life of the
    // process. On a full or read-only volume the honest answer is that nothing here is known to be
    // ours, so the generation opens with everything present marked dirty in memory. That costs a
    // re-render of a catalog the volume could not record anything about, which is the right
    // direction to be wrong in.
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
   * The dirty boundary recorded in [dir]'s manifest, or 0 when there is none.
   *
   * Read back off disk rather than carried from [writeManifest]'s caller, because the boundary
   * belongs to the generation rather than to this process's idea of it: on a rolling update the
   * replica that opened the directory first is the one that set it, and re-deriving it here would
   * move a line the other replica is already regenerating against.
   */
  private fun dirtyBoundary(dir: File): Long = runCatching {
    json
      .decodeFromString(GenerationInputs.serializer(), File(dir, MANIFEST_NAME).readText())
      .dirtyBeforeEpochMillis
  }
    .getOrDefault(0L)

  /**
   * Delete every generation in the store, unconditionally, and report how many went.
   *
   * The escape hatch for "the pixels on this volume are wrong and I already know it" — a change to
   * the render environment that [ThemeCacheFingerprint.rendererIdentity] does not read, say, which
   * no fingerprint sees and which a five-entry verification sample can miss. [sweep] cannot serve
   * this purpose: it deliberately spares any generation inside its grace window, which is exactly
   * the freshly-written one an operator wants gone.
   *
   * ### Deleting is not enough on a shared volume
   *
   * This is called before this process opens a generation, so it cannot race **this** process's
   * writes — and that used to be the whole of the reasoning, which was a single-process argument
   * about a volume that is not single-process. The deployment this store was built for performs a
   * **zero-downtime rollout**: the outgoing replica keeps serving, and keeps writing PNGs, against
   * the same mounted `/config` volume while the incoming one boots and evicts. Every render it
   * publishes in that window repopulates a generation with precisely the pixels the eviction was
   * meant to destroy, and they land *after* the deletion, so they carry fresh timestamps and read
   * as this build's own work.
   *
   * So the deletion also leaves a mark. [EVICTED_NAME] records when it happened, and every
   * generation opened from here on treats anything written before that instant plus the rollout
   * grace window as dirty — the same boundary mechanism, and the same grace window, that
   * [writeManifest] already uses for the cross-build case, because it is the same question about
   * the same other replica. An operator gets the eviction they asked for without having to prove
   * that every other writer has stopped first.
   *
   * The mark is not a lock and does not claim to be one. It cannot stop the old replica writing; it
   * makes what the old replica writes untrusted, which is the outcome that was wanted.
   */
  fun evictAll(): Int {
    var deleted = 0
    for (systemDir in root.listFiles()?.filter { it.isDirectory }.orEmpty()) {
      for (generationDir in systemDir.listFiles()?.filter { it.isDirectory }.orEmpty()) {
        if (generationDir.deleteRecursively()) deleted++
        else recordFailure(systemDir.name, "could not evict ${generationDir.name}")
      }
      if (systemDir.listFiles()?.isEmpty() == true) systemDir.delete()
    }
    // Stamped AFTER the deletion, so a crash midway leaves the volume looking un-evicted rather
    // than leaving surviving generations under a boundary nothing has been deleted against. The
    // operator re-runs the flag; the alternative silently reports a completed eviction that only
    // half happened.
    val at = clock()
    val stamped = runCatching { File(root, EVICTED_NAME).writeText(at.toString()) }.isSuccess
    if (stamped) evictedAt.set(at)
    // A volume that cannot record the eviction cannot make the old replica's repopulated renders
    // dirty either, and that is worth saying out loud rather than reporting a clean eviction.
    else recordFailure("store", "evicted but could not record the boundary in $EVICTED_NAME")
    knownGenerations.set(0)
    knownGenerationsBySystem.set(emptyMap())
    knownBytes.set(0)
    return deleted
  }

  /**
   * When this store was last evicted, or 0 when it never was.
   *
   * Read off disk once and remembered: it is consulted on every [open] and it can only be changed
   * by an [evictAll] in this same process, which updates the cached value itself. A concurrently
   * deployed replica evicting under us is not a case worth polling for — that replica is the one
   * booting, and the boundary it writes is read by the *next* open on either side.
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
  inner class Generation
  internal constructor(
    private val dir: File,
    private val system: String,
    /**
     * Treat everything already on disk as dirty regardless of what the manifest says.
     *
     * Set when a cross-build open could not record its boundary — see [writeManifest]. The volume
     * could not be told that another build's renders are here, so this process must not conclude
     * from that same volume that they are its own.
     */
    private val markAllDirtyOnOpen: Boolean = false,
  ) {

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

    /**
     * Renders written before this instant came from a build that is not the one running, and are
     * **dirty**: still servable once the load-time sample has vouched for them, but queued for
     * re-render so the store converges on pixels this renderer actually produced.
     *
     * Volatile because [markAllDirty] moves it while the optimizer is reading it.
     */
    @Volatile private var dirtyBefore: Long = dirtyBoundary(dir)

    /**
     * The dirty file names, resolved **once** and then maintained in memory.
     *
     * The boundary and each file's timestamp decide who is dirty, but only when this set is built:
     * asking the filesystem per query turned `/status` into tens of thousands of synchronous `stat`
     * calls, because the snapshot walks every target of every catalog and m3-catalog alone declares
     * 10,440. A render leaves the set when this process rewrites it, which is the only way an entry
     * becomes clean.
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
              // A timestamp that cannot be read is treated as dirty: "I cannot tell how old this
              // is" and "this is current" are not the same answer, and only one is safe to guess.
              modified == 0L || modified < boundary
            }
          )
        }
      }

    /**
     * Renders this process published while the outgoing replica of a rolling update could still
     * have been publishing too, against the modification time each had immediately after our write.
     *
     * The boundary alone does not close the overlap. It files a co-replica's LATER write as dirty
     * only if that write is the one classification sees, and classification happens once, at open —
     * so a key we render first and the outgoing replica then renames over is removed from
     * [dirtyNames] by our own write and never looked at again. Our memory tier keeps serving the
     * right pixels until it evicts, at which point the read falls through to another build's bytes
     * under a generation that reports itself converged.
     *
     * Recording our own write time makes that detectable without a second classification pass: a
     * file whose timestamp no longer matches what we left is one somebody else has since replaced.
     * Populated only while [dirtyBefore] is still in the future, which is exactly the window in
     * which a second writer can exist, so on the ordinary path this map stays empty.
     */
    private val atRiskWrites = ConcurrentHashMap<String, Long>()

    /**
     * Re-check the renders published during a rollout overlap, once that overlap is over.
     *
     * Costs one `stat` per at-risk write, once: the map is emptied on the way out and never
     * refilled, because past [dirtyBefore] no other replica is still writing here. Anything that
     * moved under us goes back on the dirty queue to be rendered again.
     *
     * An **empty** at-risk map is not an early return, even though it has nothing to stat. The
     * convergence clear below can be refused — it is try-lock, and a foreground render publishing
     * at that instant holds the lock — and the map is drained by the loop whether or not the clear
     * that follows it succeeds. Returning early on an empty map would therefore strand a
     * future-dated boundary in the manifest permanently, on the one interleaving where the clear
     * had to be deferred: every later call would see nothing to reconcile and never reach the
     * retry. The cost of not returning is three field reads in
     * [clearBoundaryIfConvergedUnderLock]'s own pre-check, which is what keeps the status path off
     * the file lock.
     */
    private fun reconcileAtRiskWrites() {
      if (clock() < dirtyBefore) return
      for ((name, writtenAt) in atRiskWrites.toList()) {
        atRiskWrites.remove(name)
        if (name !in present) continue
        val modified = runCatching { File(dir, "$name$PNG_SUFFIX").lastModified() }.getOrDefault(0L)
        // A timestamp we cannot read is treated the same way classification treats one: unknown is
        // not the same answer as ours.
        if (modified != writtenAt) dirtyNames += name
      }
      // The reconcile is itself a way of REACHING convergence, and in the ordinary case — no
      // co-replica overwrote anything — it is the last thing that happens: the dirty set emptied
      // some time ago and the at-risk set empties here, with no further write to notice. Leaving
      // the clear to `put` alone stranded the future-dated boundary in the manifest for exactly the
      // rollout this bookkeeping exists to serve.
      clearBoundaryIfConvergedUnderLock()
    }

    /**
     * Drop the durable boundary once every render on this volume is one this process made, and
     * nothing it wrote during a rollout overlap is still unverified.
     *
     * Called from both places convergence can be reached — the write that clears the last dirty
     * name, and the reconcile that clears the last at-risk one — because either can be the last.
     *
     * **The caller must hold the generation write lock.** The condition read here is exactly the
     * one [markAllDirty] inverts, so a reader that decides outside the lock is deciding about a
     * state that can have been replaced by the time it acts. See
     * [clearBoundaryIfConvergedUnderLock].
     */
    private fun clearBoundaryIfConverged() {
      if (dirtyBefore > 0L && dirtyNames.isEmpty() && atRiskWrites.isEmpty()) clearDirtyBoundary()
    }

    /**
     * [clearBoundaryIfConverged] for a caller that does **not** already hold the generation write
     * lock — the rollout reconcile, which reaches convergence from `dirtyCount()` on the status
     * path rather than from inside a write.
     *
     * The interleaving this closes loses an operator's regenerate outright. The reconcile observes
     * both sets empty; [markAllDirty] then takes the lock, persists a fresh boundary, repopulates
     * `dirtyNames` and answers the admin route `{"queued": true, "entries": N}`; and the reconcile,
     * still acting on what it saw before any of that, writes a zero boundary over the new mark and
     * clears the queue it never looked at. The endpoint has promised a request that survives the
     * next roll and there is nothing left of it, in memory or on disk.
     *
     * So the decision is re-taken under the lock, and `markAllDirty` — which is already try-lock
     * and already answers `-1` when it cannot have the lock — reports an honest failure instead of
     * a mark that is about to be erased. Whichever of the two gets the lock first, the other sees
     * its result rather than a stale copy of the state it replaced.
     *
     * The pre-check outside the lock is not the decision, only an early exit: `dirtyCount()` is on
     * the status path and the ordinary answer is "there is no boundary to clear", which should not
     * cost a file lock per call.
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
    fun isDirty(cacheKey: String): Boolean = isDirtyName(fileName(cacheKey))

    /**
     * The same question asked of a **file name** rather than a cache key.
     *
     * [present] holds hashed names, not the keys they were derived from — the store never needs to
     * map back, and could not: the hash is one-way. So every internal walk over what is on disk
     * goes through this, and only the caller-facing [isDirty] hashes. Routing an internal walk
     * through that one instead hashes an already-hashed name, lands on a file that does not exist,
     * and — since a missing file reads as dirty — quietly reports the entire generation dirty.
     */
    private fun isDirtyName(name: String): Boolean = name in dirtyNames

    /**
     * How many renders on disk are still an older build's work.
     *
     * The one call every dirty-queue read already goes through, so it is where the rollout
     * reconcile hangs — see [reconcileAtRiskWrites] for why that costs nothing on the ordinary
     * path.
     */
    fun dirtyCount(): Int {
      reconcileAtRiskWrites()
      return dirtyNames.size
    }

    /**
     * Mark every render currently on disk dirty, by moving the boundary to now.
     *
     * The operator's "regenerate this catalog" — for pixels suspected wrong by something no
     * fingerprint sees, a base image that changed the installed fonts being the case that motivated
     * it. Deliberately not a delete: the entries keep serving while the background pass replaces
     * them, so asking for a refresh does not cost every preview a cold render.
     */
    fun markAllDirty(): Int {
      // Under the generation write lock, because the transition races the one thing that undoes it.
      // A render publishing the last dirty entry calls `clearDirtyBoundary` from inside `put`,
      // which holds this lock — so an unlocked mark could write its boundary, be overwritten by
      // that older in-flight convergence, then repopulate `dirtyNames` and report a durable mark
      // that is not on disk. Regeneration would proceed in this process and a restart before it
      // finished would silently forget the rest of the operator's request.
      val generationWriteLock = tryGenerationWriteLock() ?: return -1
      try {
        val now = clock()
        // Persist FIRST, and refuse to claim the mark if it did not land. The contract this action
        // sells is that a request survives the next roll; a full or read-only volume would
        // otherwise leave the boundary in memory only, answer the operator 200 with a queued count,
        // and forget the whole thing at the next restart — the one failure a durable-sounding API
        // must not have.
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
        // A mark supersedes the overlap bookkeeping: every one of those entries is dirty now, so
        // there is nothing left for the reconcile to decide, and leaving it populated would block
        // the convergence clear for a window that has already been overtaken.
        atRiskWrites.clear()
        return dirtyNames.size
      } finally {
        generationWriteLock.close()
      }
    }

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
      // `|| isDirty` is what lets the regeneration pass actually land. A dirty entry an operator
      // marked was written by THIS process, so it is not in `adopted`, and the adopted-only test
      // would drop its replacement on the floor — the pass would re-render the catalog every slice
      // and never clear a single flag.
      if (name in present && !(replaceExisting && (name in adopted || name in dirtyNames))) return
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
        // ...and no longer another build's work. This is the ONLY way an entry becomes clean, which
        // is what keeps the dirty set honest without re-reading the volume: it shrinks exactly when
        // a render is rewritten, and nothing else touches it.
        dirtyNames -= name
        // Inside the overlap window, remember what we left behind so a co-replica renaming over it
        // can be caught later; outside it there is no second writer and nothing to remember.
        // Strictly BEFORE the boundary: the boundary is when the overlap window closes, so a write
        // at or past it has no second writer to be at risk from. It also keeps a store configured
        // with no grace window — every test that is not about the rollout, and any deployment that
        // is not zero-downtime — out of this bookkeeping entirely.
        if (clock() < dirtyBefore) atRiskWrites[name] = target.lastModified()
        // Converged: every render on this volume is now one this process made. Clearing the durable
        // boundary is what makes that survive a restart — a cross-build open dates the boundary a
        // grace window into the FUTURE, so leaving it behind would have the next start reclassify
        // this build's own early renders as another build's work and regenerate them again, once
        // per restart, forever.
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
     * Drop this whole generation — used when load-time verification finds a cached render that no
     * longer matches what the renderer produces.
     *
     * The whole generation, not the offending entry: a mismatch means the fingerprint failed to
     * capture some input, so every entry sharing it is suspect. Keeping the rest would be trusting
     * the same broken identity that just proved untrustworthy.
     */
    /**
     * Delete only the **dirty** renders, keeping everything this build wrote, and report how many
     * went.
     *
     * What a failed sample verification should do now that a generation can hold renders from two
     * builds at once. [discard] takes the lot, which was right while every entry present at open
     * came from the same suspect build — it is wrong once this process has spent an hour rendering
     * fresh entries into the same directory, because those are the one thing the sample just
     * *confirmed* and throwing them away is a straight hour of rendering lost.
     *
     * Returns -1 when the generation write lock could not be taken, matching [discard]'s "could
     * not" so the caller can keep the entries quarantined rather than assume they are gone.
     */
    fun discardDirty(): Int {
      if (dirtyBefore <= 0L) return 0
      return discardNames(dirtyNames.toList(), "dirty")
    }

    /**
     * Delete every render this process **adopted** — everything that was on disk when the
     * generation opened — keeping only what this process has rendered since, and report how many
     * went.
     *
     * The correct answer to a failed sample, and a strict superset of [discardDirty]. Dirtiness
     * asks "did a different BUILD write this", which is not the same question as "did a different
     * PROCESS write this", and the sample only ever examines the second kind. A same-version
     * restart of a partly converged generation inherits the earlier process's renders as *clean* —
     * its own build wrote them — so a mismatch caused by an untracked input such as the base image
     * or the installed fonts would delete the older build's leftovers, report a positive count that
     * suppresses the fallback [discard], lift the quarantine, and go on serving the very renders
     * the sample was drawn from. Adoption is the boundary the sample actually tests, so it is the
     * boundary the discard has to use.
     */
    fun discardAdopted(): Int = discardNames(adopted.toList(), "adopted")

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
        // ALL or nothing. The caller is `verifySample`, which reads any success as licence to trust
        // this generation and lift the read quarantine — so one PNG left behind by a failed delete
        // would go from "proved stale" to "served", which is the single outcome this whole path
        // exists to prevent. Reporting the failure keeps the quarantine and lets the next pass try
        // again.
        if (failed > 0) return -1
        // Nothing older than the boundary survives, so the boundary has nothing left to mark. Left
        // in place it would make every entry this build writes from here look dirty the moment a
        // filesystem timestamp rounded the wrong way.
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
        // The manifest goes with everything else below, so the in-memory boundary must go too or
        // this object would keep marking a directory it just emptied.
        dirtyBefore = 0L
        dirtyNames.clear()
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

    /**
     * Store-root marker recording the epoch millis of the last [evictAll].
     *
     * At the ROOT rather than inside a generation, because the generations it applies to are the
     * ones that do not exist yet: an eviction deletes what is on the volume, and what it has to
     * survive is what gets written next. A plain integer in a plain file so that an operator
     * looking at a mounted volume can read and clear it without tooling.
     */
    const val EVICTED_NAME: String = "evicted-at"
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
  /**
   * The build that last **opened** this generation — not necessarily one that wrote a PNG into it.
   *
   * Rewritten by [ThemeCacheStore.writeManifest] at open time, before this process has rendered
   * anything, so a replica that fails readiness still leaves its version here. Read it as "the last
   * build that thought this generation was its own", which is the question the dirty boundary
   * beside it was set to answer; do not read it as authorship of the pixels. See that function's
   * doc for why it is stamped at open rather than on the first write.
   */
  val toolVersion: String,
  val variant: String,
  val renderConfig: String,
  val createdAtEpochMillis: Long = 0,
  /**
   * When a build other than the one that wrote them last opened this generation — the line
   * separating **dirty** renders from fresh ones.
   *
   * Dirtiness is derived from this and each file's own timestamp rather than tracked per entry: a
   * render written before the boundary came from an older build, one written after came from this
   * one, and re-rendering an entry moves its timestamp past the boundary for free. That is the
   * whole of the bookkeeping — no parallel index to keep consistent with 18,604 files, and no write
   * amplification on the regeneration path it exists to drive.
   *
   * Zero means nothing is dirty: every render here was written by a build that agrees with this
   * one.
   */
  val dirtyBeforeEpochMillis: Long = 0,
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
