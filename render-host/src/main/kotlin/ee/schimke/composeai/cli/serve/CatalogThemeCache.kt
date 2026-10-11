package ee.schimke.composeai.cli.serve

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.Serializable

/** Progress for one catalog generation's server-side theme-cache optimization. */
@Serializable
public data class ThemeOptimizationSnapshot(
  val state: String,
  val total: Int,
  val cached: Int,
  val remaining: Int,
  val failed: Int,
  val cachedBytes: Long,
  val fullyOptimized: Boolean,
  val startedAtEpochMillis: Long? = null,
  val completedAtEpochMillis: Long? = null,
  /**
   * Entries cached per minute over the pass's active time, and projected seconds to finish; null
   * until there is enough to divide by. A rate, because cumulative counts can't show crawling.
   */
  val entriesPerMinute: Double? = null,
  val etaSeconds: Long? = null,
  /**
   * Where the pass's wall-clock goes: rendering vs. waiting, i.e. render-bound or scheduling-bound.
   */
  /** [batchMillis] + [warmMillis], kept as the single "rendering" total. */
  val renderMillis: Long = 0,
  /**
   * [batchMillis] is per-entry render time; [warmMillis] is cold-daemon startup (up to ~68s on
   * Android), paid once per daemon. Split so slow renders aren't confused with repeated cold
   * starts.
   */
  val batchMillis: Long = 0,
  val warmMillis: Long = 0,
  /** [gateWaitMillis] + [permitWaitMillis], kept as the single "not rendering" total. */
  val waitingMillis: Long = 0,
  /**
   * [gateWaitMillis] is time the idle gate withheld a turn (lever: the quiet window);
   * [permitWaitMillis] is time queued behind other catalogs for a render permit (lever: prefetch
   * concurrency). Split because they have opposite fixes.
   */
  val gateWaitMillis: Long = 0,
  val permitWaitMillis: Long = 0,
  /** How often the idle gate granted the pass its turn, and how often traffic took it back. */
  val turnsGranted: Int = 0,
  val turnsYielded: Int = 0,
  /**
   * Turns the ceiling forced after the gate withheld one too long (counted inside [turnsGranted]).
   * Climbing means the box never looks idle to the optimizer.
   */
  val turnsForced: Int = 0,
  /**
   * Daemons that actually rendered concurrently in the last batch, and the peak so far. Not the job
   * count: without spare seats the pool queues jobs onto one daemon.
   */
  val lastBatchWidth: Int = 0,
  val maxBatchWidth: Int = 0,
  /**
   * Cached entries inherited from an older build and awaiting re-render (counted inside [cached]).
   * Stuck with `remaining` at 0 means the pass isn't picking up the dirty queue.
   */
  val dirty: Int = 0,
) {
  /**
   * Every target warm and produced by the running renderer — what the pass may stop on.
   * [fullyOptimized] only means "nothing missing", which dirty inherited entries also satisfy.
   */
  val converged: Boolean
    get() = fullyOptimized && dirty == 0
}

/**
 * One catalog generation's rendered-preview cache: memory occupancy, read outcomes, and the disk
 * tier. The read counters show whether the cache is actually being used, not just filled.
 */
@Serializable
public data class CatalogRenderCacheSnapshot(
  val entries: Int,
  val bytes: Long,
  val maxBytes: Long,
  val evictions: Long,
  /** Reads served from the memory window. */
  val memoryHits: Long = 0,
  /** Reads the memory window missed and the disk tier answered. */
  val diskHits: Long = 0,
  /** Reads neither tier could answer, so a render had to happen. */
  val misses: Long = 0,
  /**
   * Reads refused because the adopted generation isn't verified yet; kept out of [misses] so early
   * cold-start hit rates are explainable.
   */
  val withheld: Long = 0,
  /**
   * `(memoryHits + diskHits) / (memoryHits + diskHits + misses)`, or null before any read; excludes
   * [withheld].
   */
  val hitRate: Double? = null,
  /** The disk tier's own counters, or null when this catalog has none — see [persistenceOff]. */
  val persisted: ThemeCacheGenerationSnapshot? = null,
  /**
   * Why this catalog has no disk tier, when it has none — these fallbacks are otherwise silent and
   * permanent for the host's lifetime.
   */
  val persistenceOff: String? = null,
)

/**
 * Rendered PNGs and theme-optimization progress shared by every host incarnation of one catalog
 * generation. Held in [ServeSessionState] so renders survive daemon suspension; also keeps
 * successful on-demand override renders. A catalog refresh builds a fresh cache.
 */
public class CatalogThemeCache(
  maxBytes: Long =
    System.getProperty("composeai.serve.catalogRenderCacheMaxBytes")?.toLongOrNull()
      ?: DEFAULT_MAX_BYTES,
  /**
   * Disk tier for this generation, or null for memory-only. Memory is a smaller window onto disk,
   * not a copy: [get] falls through and promotes, and "cached" counts ask both tiers.
   */
  private val persistence: ThemeCacheStore.Generation? = null,
  /** Why there is no disk tier ([CatalogRenderCacheSnapshot.persistenceOff]). */
  private val persistenceOffReason: String? = null,
) {
  public val maxBytes: Long = maxBytes.coerceAtLeast(0)
  private val renderLock = Any()
  // Access-order map: the byte cap evicts the least-recently-read render first.
  private val renders = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
  private val targetKeys = ConcurrentHashMap.newKeySet<String>()
  // The bounded set the disk tier accepts; see [configurePersistable].
  private val persistableKeys = ConcurrentHashMap.newKeySet<String>()
  // False while renders adopted from a previous process are unverified (see [get]); renders made by
  // this process need no checking.
  private val persistenceTrusted = java.util.concurrent.atomic.AtomicBoolean(false)
  private val failedKeys = ConcurrentHashMap.newKeySet<String>()
  // Consecutive live-render failures per key and the last reason; cleared by a successful [put].
  private val failureCounts = ConcurrentHashMap<String, Int>()
  private val failureReasons = ConcurrentHashMap<String, String>()
  // Consecutive background `Busy` outcomes per key, with a looser latch ([BUSY_LATCH]).
  private val busyCounts = ConcurrentHashMap<String, Int>()
  private val byteCount = AtomicLong(0)
  private val evictionCount = AtomicLong(0)
  // Read outcomes by tier, for `/status`: memory hit, disk hit, miss (= a render).
  private val memoryHits = AtomicLong(0)
  private val diskHits = AtomicLong(0)
  private val readMisses = AtomicLong(0)
  private val withheldReads = AtomicLong(0)
  private val state = AtomicReference("waiting")
  private val startedAt = AtomicLong(0)
  private val completedAt = AtomicLong(0)
  private val batchMillis = AtomicLong(0)
  private val warmMillis = AtomicLong(0)
  private val gateWaitMillis = AtomicLong(0)
  private val permitWaitMillis = AtomicLong(0)
  private val turnsGranted = java.util.concurrent.atomic.AtomicInteger(0)
  private val turnsYielded = java.util.concurrent.atomic.AtomicInteger(0)
  private val turnsForced = java.util.concurrent.atomic.AtomicInteger(0)
  private val lastBatchWidth = java.util.concurrent.atomic.AtomicInteger(0)
  private val maxBatchWidth = java.util.concurrent.atomic.AtomicInteger(0)
  // Optimizer-produced entries only, matching the rate's optimizer-time denominator.
  private val optimizerProduced = java.util.concurrent.atomic.AtomicInteger(0)

  /** The idle gate handed the pass its turn. */
  public fun recordTurnGranted() {
    turnsGranted.incrementAndGet()
  }

  /** Traffic took the turn back. */
  public fun recordTurnYielded() {
    turnsYielded.incrementAndGet()
  }

  /** A turn the ceiling forced; also counted in [recordTurnGranted]. */
  public fun recordTurnForced() {
    turnsForced.incrementAndGet()
    turnsGranted.incrementAndGet()
  }

  /** Wall-clock the idle gate withheld a turn because the box looked busy. */
  public fun recordGateWait(millis: Long) {
    if (millis > 0) gateWaitMillis.addAndGet(millis)
  }

  /** Wall-clock spent holding a turn but queued behind other catalogs for a render permit. */
  public fun recordPermitWait(millis: Long) {
    if (millis > 0) permitWaitMillis.addAndGet(millis)
  }

  /** One batch completed. [width] is the peak concurrent daemons, not the job count. */
  public fun recordBatch(width: Int, millis: Long) {
    lastBatchWidth.set(width)
    maxBatchWidth.accumulateAndGet(width, ::maxOf)
    if (millis > 0) batchMillis.addAndGet(millis)
  }

  /** Entries this batch actually produced — the rate's numerator. */
  public fun recordProduced(count: Int) {
    if (count > 0) optimizerProduced.addAndGet(count)
  }

  /**
   * A cold daemon warm the optimizer waited out: counts toward render time and the rate's
   * denominator, but kept apart from [recordBatch] since it produces no entries.
   */
  public fun recordWarm(millis: Long) {
    if (millis > 0) warmMillis.addAndGet(millis)
  }

  public fun configureTargets(keys: Collection<String>) {
    targetKeys += keys
    configurePersistable(keys)
    refreshCompletion()
  }

  /**
   * Declare the keys the disk tier will accept, without claiming them as optimization targets, so
   * renders still persist when the prefetch pass is disabled
   * (`-Dcomposeai.serve.themeOptimization=false`) and `/status` shows no optimization row.
   */
  public fun configurePersistable(keys: Collection<String>) {
    persistableKeys += keys
  }

  /** The render for [key] from memory, or from disk (promoted into memory), or null. */
  public fun get(key: String): ByteArray? {
    synchronized(renderLock) { renders[key] }
      ?.let {
        memoryHits.incrementAndGet()
        return it
      }
    // Adopted-but-unverified bytes are not served: they are exactly what a wrong fingerprint would
    // get wrong. [contains] still reports them so the optimizer doesn't re-render them meanwhile.
    val store =
      persistence
        ?: run {
          readMisses.incrementAndGet()
          return null
        }
    if (!persistenceTrusted.get() && store.wasAdopted(key)) {
      withheldReads.incrementAndGet()
      return null
    }
    // Sampled BEFORE the read, so the comparison below spans the whole unlocked window.
    val epoch = dropEpoch.get()
    val fromDisk =
      store.get(key)
        ?: run {
          readMisses.incrementAndGet()
          return null
        }
    diskHits.incrementAndGet()
    // Promote via the normal write path (LRU + accounting) but not back to disk. Only if no drop
    // happened since the epoch was sampled: the disk read is unlocked, and promoting after a
    // `dropPersisted` would resurrect bytes just declared wrong.
    remember(key, fromDisk, validEpoch = epoch)
    return fromDisk
  }

  /**
   * Bumped by every [dropPersisted]; checked around an unlocked disk read so pre-drop bytes can't
   * be promoted after it.
   */
  private val dropEpoch = AtomicLong()

  /**
   * Targets inherited from an older build and queued for re-render, in a stable order. Dirty
   * entries stay warm and served; this second queue is worked after gaps are filled so the store
   * converges.
   */
  public fun dirtyTargets(): List<String> {
    val store = persistence ?: return emptyList()
    if (store.dirtyCount() == 0) return emptyList()
    // Walked from the targets: on-disk names are one-way hashes.
    return targetKeys.filter(store::isDirty).sorted()
  }

  /**
   * Whether a background pass exists that could refill this cache (false when theme optimization is
   * disabled, so waking a host would buy nothing).
   */
  public val hasOptimizationTargets: Boolean
    get() = targetKeys.isNotEmpty()

  /** Mark every persisted render dirty and report how many — "regenerate" without going cold. */
  public fun markPersistedDirty(): Int {
    val store = persistence ?: return 0
    // No targets means no pass to regenerate anything; -1 says the action is unavailable here.
    if (targetKeys.isEmpty()) return -1
    return store.markAllDirty()
  }

  /**
   * Discard this catalog's persisted renders and the memory window. Everything goes cold, so
   * [markPersistedDirty] is the preferred choice.
   */
  public fun dropPersisted(): Boolean {
    // `true` with no disk tier: clearing memory can't fail, and false would mean a 409 retry loop.
    val discarded = persistence?.discard() ?: true
    synchronized(renderLock) {
      // Inside the lock and before the clear, so a racing promotion either is cleared or sees the
      // new epoch.
      dropEpoch.incrementAndGet()
      renders.clear()
      byteCount.set(0)
    }
    state.set("paused")
    completedAt.set(0)
    return discarded
  }

  /** Whether [key] is warm in either tier, without paying to read the bytes. */
  public fun contains(key: String): Boolean =
    synchronized(renderLock) { renders.containsKey(key) } || persistence?.contains(key) == true

  public fun put(key: String, png: ByteArray) {
    // Disk first, not gated on the memory cap: disk is authoritative with its own budget.
    //
    // Only configured targets are persisted: ad-hoc override renders are unbounded (a visitor can
    // mint keys indefinitely) and live generations are never evicted, so they stay memory-only.
    // While quarantined, a fresh render replaces the adopted copy
    // ([ThemeCacheStore.Generation.put]).
    if (key in persistableKeys)
      persistence?.put(
        key,
        png,
        // A regenerated dirty entry must overwrite the older build's bytes, or the flag never
        // clears.
        replaceExisting = !persistenceTrusted.get() || persistence.isDirty(key),
      )
    remember(key, png, replaceExisting = true)
    failedKeys.remove(key)
    failureCounts.remove(key)
    failureReasons.remove(key)
    busyCounts.remove(key)
    refreshCompletion()
  }

  /** Hold [png] in the memory tier under the byte cap, evicting least-recently-read first. */
  private fun remember(
    key: String,
    png: ByteArray,
    replaceExisting: Boolean = false,
    /**
     * Insert only while [dropEpoch] still reads this value (see [get]); checked inside the lock.
     */
    validEpoch: Long? = null,
  ) {
    synchronized(renderLock) {
      if (validEpoch != null && dropEpoch.get() != validEpoch) return@synchronized
      if (renders.containsKey(key)) {
        // A regenerated dirty entry must displace the copy memory is still serving.
        if (!replaceExisting) return@synchronized
        renders.remove(key)?.let { byteCount.addAndGet(-it.size.toLong()) }
      }
      if (png.size.toLong() > maxBytes) return@synchronized
      renders[key] = png
      byteCount.addAndGet(png.size.toLong())
      while (byteCount.get() > maxBytes && renders.isNotEmpty()) {
        val eldest = renders.entries.iterator().next()
        renders.remove(eldest.key)
        byteCount.addAndGet(-eldest.value.size.toLong())
        evictionCount.incrementAndGet()
        // With a disk tier an eviction isn't lost progress; only un-complete when the entry is
        // gone.
        if (eldest.key in targetKeys && persistence?.contains(eldest.key) != true) {
          state.set("paused")
          completedAt.set(0)
        }
      }
    }
  }

  /**
   * Re-render a sample of the persisted generation and drop it if the pixels disagree — the safety
   * net for inputs [ThemeCacheFingerprint] doesn't cover.
   *
   * [render] returns fresh bytes for a cache key, or null if it couldn't render, which is not a
   * mismatch (a busy daemon must not wipe the cache). Returns true if the generation is
   * trustworthy.
   */
  public fun verifySample(
    sampleSize: Int = VERIFY_SAMPLE,
    render: (String) -> ByteArray?,
  ): VerifyOutcome {
    val store = persistence ?: return VerifyOutcome.NOTHING_TO_VERIFY
    // Only entries adopted from the previous process can answer the question; sampling this
    // process's own renders would verify nothing.
    val candidates = persistableKeys.filter(store::wasAdopted).sorted().take(sampleSize)
    if (candidates.isEmpty()) {
      // Nothing was adopted, so nothing can be stale: everything from here is this renderer's own.
      persistenceTrusted.set(true)
      return VerifyOutcome.NOTHING_TO_VERIFY
    }
    var compared = 0
    for (key in candidates) {
      val cached = store.get(key) ?: continue
      val fresh = render(key) ?: continue
      compared++
      if (!fresh.contentEquals(cached)) {
        // Discard adopted entries only: keeps this process's confirmed renders. Not dirty-only — a
        // same-version restart inherits old renders as clean, and the sample tests adoption.
        val dropped = store.discardAdopted()
        val discarded = if (dropped >= 0) dropped > 0 || store.discard() else false
        synchronized(renderLock) {
          // Bytes just proved wrong: stop an in-flight promotion putting them back.
          dropEpoch.incrementAndGet()
          renders.clear()
          byteCount.set(0)
        }
        state.set("paused")
        completedAt.set(0)
        // Trust follows a successful discard, not the detection; if it failed, stay quarantined and
        // retry.
        if (discarded) persistenceTrusted.set(true)
        return if (discarded) VerifyOutcome.MISMATCH else VerifyOutcome.MISMATCH_UNDISCARDED
      }
    }
    // Zero successful comparisons is not a pass (all Busy/Failed during a cold start); retry
    // instead.
    if (compared > 0) persistenceTrusted.set(true)
    return if (compared > 0) VerifyOutcome.VERIFIED else VerifyOutcome.NO_EVIDENCE
  }

  /** What [verifySample] managed to establish. */
  public enum class VerifyOutcome {
    /** At least one persisted render was re-rendered and matched. */
    VERIFIED,
    /** No disk tier, or nothing adopted from it — there is nothing that could be stale. */
    NOTHING_TO_VERIFY,
    /** The renderer answered nothing usable, so the question is still open. Ask again. */
    NO_EVIDENCE,
    /** A persisted render no longer matches; the generation has been discarded. */
    MISMATCH,
    /**
     * A mismatch, but the generation could not be discarded (write lock held through every retry).
     * The caller must not latch verification: the entries stay withheld and the next pass asks
     * again.
     */
    MISMATCH_UNDISCARDED;

    /** Whether the persisted renders may be trusted from here on. */
    public val settled: Boolean
      get() = this == VERIFIED || this == NOTHING_TO_VERIFY
  }

  public fun markRunning(nowMillis: Long) {
    startedAt.compareAndSet(0, nowMillis)
    state.set("running")
  }

  public fun markPaused() {
    if (!snapshot().fullyOptimized) state.set("paused")
  }

  /**
   * Mark [key] as one the optimizer could not fill, for the `/status` `failed` count. A metric, not
   * a verdict: only a [reason] from a real [RenderOutcome.Failed] makes it terminal for
   * [failureReason].
   */
  public fun markFailed(key: String, reason: String? = null) {
    failedKeys += key
    reason?.let { failureReasons[key] = it }
  }

  /**
   * Record an on-demand render failure for [key]; returns whether it has latched as unrenderable
   * ([FAILURE_LATCH] consecutive). Latching stops one broken preview from repeatedly holding the
   * render lock; a successful [put] clears the count.
   */
  public fun recordRenderFailure(key: String, reason: String): Boolean {
    failureReasons[key] = reason
    val count = failureCounts.merge(key, 1, Int::plus) ?: 1
    if (count < FAILURE_LATCH) return false
    failedKeys += key
    return true
  }

  /**
   * Record a background `Busy` for [key]; returns whether it has latched ([BUSY_LATCH]
   * consecutive). Without a ceiling a perpetually-busy key is neither cached nor failed and the
   * pass never converges; latching gives it a reason and a terminal state. A successful [put]
   * clears the count.
   */
  public fun recordBackgroundBusy(key: String): Boolean {
    val count = busyCounts.merge(key, 1, Int::plus) ?: 1
    if (count < BUSY_LATCH) return false
    failedKeys += key
    failureReasons[key] =
      "no live lane produced this theme render after $count attempts (daemon busy or absent)"
    return true
  }

  /**
   * Why [key] cannot be rendered, or null while it may still succeed. Needs both a latch and a real
   * failure reason: keys the optimizer merely ran out of attempts on must stay retryable.
   */
  public fun failureReason(key: String): String? =
    if (key in failedKeys) failureReasons[key] else null

  public fun markPassFinished(nowMillis: Long) {
    if (targetKeys.all(::contains)) {
      completedAt.compareAndSet(0, nowMillis)
      state.set("complete")
    } else {
      state.set(if (failedKeys.isEmpty()) "paused" else "degraded")
    }
  }

  public fun snapshot(): ThemeOptimizationSnapshot {
    // Counted across both tiers, since memory is smaller than a warmed catalog by design.
    val cachedTargets = targetKeys.count(::contains)
    val total = targetKeys.size
    val complete = total > 0 && cachedTargets == total
    // Read each counter once so the published row is self-consistent.
    val batch = batchMillis.get()
    val warm = warmMillis.get()
    val render = batch + warm
    val gateWait = gateWaitMillis.get()
    val permitWait = permitWaitMillis.get()
    val waiting = gateWait + permitWait
    val rate = ratePerMinute(render + waiting)
    // Read once, before `failed`: `dirtyCount` runs the rollout reconcile, which can change
    // `isDirty`.
    val dirtyTotal = persistence?.dirtyCount() ?: 0
    return ThemeOptimizationSnapshot(
      state =
        if (complete) "complete" else state.get().let { if (it == "complete") "paused" else it },
      total = total,
      cached = cachedTargets,
      remaining = (total - cachedTargets).coerceAtLeast(0),
      // A cached failed key is no longer a failure unless what's cached is dirty (another build's).
      failed =
        failedKeys.count {
          it in targetKeys && (!contains(it) || persistence?.isDirty(it) == true)
        },
      cachedBytes = byteCount.get(),
      fullyOptimized = complete,
      startedAtEpochMillis = startedAt.get().takeIf { it > 0 },
      completedAtEpochMillis = completedAt.get().takeIf { it > 0 },
      // Rate over active time (render + gate wait), not wall-clock.
      entriesPerMinute = rate,
      etaSeconds =
        rate
          ?.takeIf { it > 0 }
          ?.let { ((total - cachedTargets).coerceAtLeast(0) / it * 60).toLong() },
      renderMillis = render,
      batchMillis = batch,
      warmMillis = warm,
      waitingMillis = waiting,
      gateWaitMillis = gateWait,
      permitWaitMillis = permitWait,
      turnsGranted = turnsGranted.get(),
      turnsYielded = turnsYielded.get(),
      turnsForced = turnsForced.get(),
      lastBatchWidth = lastBatchWidth.get(),
      maxBatchWidth = maxBatchWidth.get(),
      // The store's count, not `dirtyTargets().size`: too expensive per `/status` request.
      dirty = dirtyTotal,
    )
  }

  public fun renderCacheSnapshot(): CatalogRenderCacheSnapshot {
    // Read once and derive, as in [snapshot].
    val memory = memoryHits.get()
    val disk = diskHits.get()
    val missed = readMisses.get()
    val reads = memory + disk + missed
    return synchronized(renderLock) {
      CatalogRenderCacheSnapshot(
        entries = renders.size,
        bytes = byteCount.get(),
        maxBytes = maxBytes,
        evictions = evictionCount.get(),
        memoryHits = memory,
        diskHits = disk,
        misses = missed,
        withheld = withheldReads.get(),
        hitRate = if (reads > 0) (memory + disk).toDouble() / reads else null,
        persisted = persistence?.stats(),
        persistenceOff = if (persistence == null) persistenceOffReason else null,
      )
    }
  }

  /** [activeMillis] is passed in so the rate divides by the same numbers the snapshot publishes. */
  private fun ratePerMinute(activeMillis: Long): Double? {
    val produced = optimizerProduced.get()
    if (produced <= 0 || activeMillis <= 0) return null
    return produced / (activeMillis / 60_000.0)
  }

  private fun refreshCompletion() {
    if (targetKeys.isNotEmpty() && targetKeys.all(::contains)) {
      state.set("complete")
    }
  }

  public companion object {
    public const val DEFAULT_MAX_BYTES: Long = 128L * 1024 * 1024

    /**
     * Persisted renders re-rendered and compared when a generation is adopted. Small on purpose: an
     * input that changes the renderer changes every preview, so a handful answers the question.
     */
    public const val VERIFY_SAMPLE: Int = 5

    /**
     * Consecutive on-demand render failures before a key is treated as permanently unrenderable.
     */
    public const val FAILURE_LATCH: Int = 3

    /**
     * Consecutive background `Busy` outcomes before a key is treated as unfillable. Far looser than
     * [FAILURE_LATCH] since `Busy` usually means "ask again", but it needs some ceiling or the pass
     * sits at `paused` with `failed: 0` forever.
     */
    public const val BUSY_LATCH: Int = 12
  }
}
