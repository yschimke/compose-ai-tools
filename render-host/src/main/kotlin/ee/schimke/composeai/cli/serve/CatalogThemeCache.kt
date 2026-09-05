package ee.schimke.composeai.cli.serve

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.Serializable

/** Progress for one catalog generation's server-side theme-cache optimization. */
@Serializable
data class ThemeOptimizationSnapshot(
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
   * Entries cached per minute over the pass's lifetime, and the projected seconds to finish at that
   * rate. Null before the pass has done enough to divide by.
   *
   * The point of publishing a RATE rather than only `cached`/`remaining`: a cumulative count read
   * twice looks the same whether the pass is keeping up or crawling, and reading progress off a
   * lifetime average (which includes the server's startup, when the pass is parked) understates the
   * current rate. Both mistakes were made against this catalog before this existed.
   */
  val entriesPerMinute: Double? = null,
  val etaSeconds: Long? = null,
  /**
   * Where the pass's wall-clock actually goes: inside renders vs. waiting. This is the split that
   * says whether throughput is render-bound (nothing to fix without a faster renderer) or
   * wait-bound (a scheduling problem), which `cached` alone cannot distinguish.
   */
  /** [batchMillis] + [warmMillis], kept as the single "rendering" total. */
  val renderMillis: Long = 0,
  /**
   * The render bucket, separated — because a cold start and a steady-state render are not the same
   * cost and the sum reads as though they were.
   *
   * [batchMillis] is time inside theme-render batches: the recurring, per-entry cost, and the only
   * part that scales with how much is left to do. [warmMillis] is time waiting out a cold daemon,
   * paid once per daemon per pass but running to **34–68s** on an Android/Robolectric lane — so on
   * a pass that has had only a handful of turns it can be most of [renderMillis] while producing
   * nothing.
   *
   * Reading only the total, a box whose renders are genuinely slow is indistinguishable from one
   * that is fast but keeps paying for cold starts — and "the renderer is the bottleneck" is the
   * wrong conclusion to draw from the second. It was very nearly drawn from this catalog.
   */
  val batchMillis: Long = 0,
  val warmMillis: Long = 0,
  /** [gateWaitMillis] + [permitWaitMillis], kept as the single "not rendering" total. */
  val waitingMillis: Long = 0,
  /**
   * The two waits, separated — because they have opposite fixes and the sum cannot tell them apart.
   *
   * [gateWaitMillis] is time the idle gate withheld a turn: the box looked busy, so the pass is
   * being deliberately polite and the lever is the quiet window. [permitWaitMillis] is time the
   * pass HAD its turn and queued behind other catalogs for a server-wide render permit: the box was
   * idle and the pass was merely outnumbered, and the lever is how many catalogs prefetch at once.
   *
   * Reading only the total, a deployment where every catalog optimizes simultaneously and starves
   * on permits is indistinguishable from one where a trickle of traffic keeps the gate shut — and
   * loosening the quiet window, the obvious response to the latter, does nothing for the former.
   */
  val gateWaitMillis: Long = 0,
  val permitWaitMillis: Long = 0,
  /** How often the idle gate granted the pass its turn, and how often traffic took it back. */
  val turnsGranted: Int = 0,
  val turnsYielded: Int = 0,
  /**
   * Turns the *ceiling* granted because the gate had withheld one for too long, counted inside
   * [turnsGranted].
   *
   * A number climbing here says the box never looks quiet to the optimizer and the only progress it
   * makes is the forced kind — which is a working cache and a broken gate, not a healthy server. On
   * a box whose idle clock is pinned by a leaked session lease this is the *only* counter that
   * would ever move.
   */
  val turnsForced: Int = 0,
  /**
   * Daemons that actually rendered **concurrently** in the last batch, and the most so far.
   *
   * Deliberately not the batch's job count. The optimizer submits N jobs to an executor and the
   * shared pool hands each one a daemon — but when the live-seat budget affords no replica the pool
   * does not spawn one, it queues the job onto a host already in circulation. So N jobs can be N
   * threads taking turns on a single daemon, and a count of jobs submitted reports that as "N
   * wide". This is the peak concurrent borrow observed inside the batch, which is the number that
   * distinguishes the two.
   */
  val lastBatchWidth: Int = 0,
  val maxBatchWidth: Int = 0,
  /**
   * Cached entries carried over from an older build and still awaiting re-render.
   *
   * Counted inside [cached], not beside it: they are warm and they are being served. This says how
   * much of that warmth is another build's work, which is the difference between a catalog that has
   * converged on this renderer and one that is merely inheriting. Falling steadily is the
   * background regeneration doing its job; stuck with `remaining` at 0 means the pass believes it
   * has finished and is not picking the queue up.
   */
  val dirty: Int = 0,
) {
  /**
   * Every target warm **and** produced by the renderer that is running — the condition the pass may
   * actually stop on.
   *
   * [fullyOptimized] is deliberately left meaning "nothing is missing", because that is what the
   * status row and the completion state have always reported. It is the wrong gate for the worker:
   * a dirty entry is cached, so a catalog that has inherited a whole generation reads as fully
   * optimized while none of it is this build's work.
   */
  val converged: Boolean
    get() = fullyOptimized && dirty == 0
}

/**
 * One catalog generation's rendered-preview cache: memory occupancy, what the reads did, and the
 * disk tier behind it.
 *
 * [entries]/[bytes]/[maxBytes]/[evictions] describe the memory window. Everything after them exists
 * to answer a question occupancy cannot: whether the cache is *being used*. A cache that is filling
 * and a cache that is filling and never read look the same from the outside, and with a disk tier
 * the second one costs I/O on every render for nothing.
 */
@Serializable
data class CatalogRenderCacheSnapshot(
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
   * Reads deliberately refused because the generation was adopted from a previous process and had
   * not yet been verified.
   *
   * Kept out of [misses] so it cannot be read as the cache failing: these are entries the cache
   * holds and is choosing not to serve, and during a cold start they can outnumber real misses.
   * Counted so a hit rate that looks terrible for the first few minutes of a process is explainable
   * rather than alarming.
   */
  val withheld: Long = 0,
  /**
   * Hits over reads — `(memoryHits + diskHits) / (memoryHits + diskHits + misses)` — or null before
   * anything has been read.
   *
   * Published rather than left to the reader because the counters are cumulative over the process
   * and a cumulative pair read once tells you nothing about whether the cache is currently earning
   * its keep. [withheld] is excluded from the denominator for the reason given above.
   */
  val hitRate: Double? = null,
  /** The disk tier's own counters, or null when this catalog has none — see [persistenceOff]. */
  val persisted: ThemeCacheGenerationSnapshot? = null,
  /**
   * Why this catalog has no disk tier, when it has none.
   *
   * Every reason a catalog falls back to memory-only used to be indistinguishable from "the server
   * was started without a cache directory" — an unreadable launch descriptor, a classpath entry the
   * fingerprint could not digest, a generation directory that could not be created. Those are
   * exactly the failures worth knowing about, because they are silent and permanent for the life of
   * the host, so they are named here.
   */
  val persistenceOff: String? = null,
)

/**
 * Rendered PNGs and theme-optimization progress shared by every host incarnation of one catalog
 * generation.
 *
 * A live catalog host is normally suspended after an idle window. Keeping this object in
 * [ServeSessionState] lets the optimized PNGs survive that daemon suspension and be reused when the
 * catalog resumes. Although the optimizer only targets declared themes, the render map also keeps
 * successful on-demand override renders (knobs, locale, font scale, and so on). A catalog refresh
 * builds a fresh session state and therefore a fresh cache, so entries accumulate for exactly as
 * long as the catalog content they were rendered from remains current.
 */
class CatalogThemeCache(
  maxBytes: Long =
    System.getProperty("composeai.serve.catalogRenderCacheMaxBytes")?.toLongOrNull()
      ?: DEFAULT_MAX_BYTES,
  /**
   * Disk tier for this catalog generation, or null to keep the historical memory-only behaviour.
   *
   * **Memory is a window onto this, not a copy of it.** The in-memory cap is 128 MB and a fully
   * warmed m3-catalog is 10,120 PNGs — several times that — so preloading the generation would just
   * thrash the LRU and, worse, would report `cached` as whatever happened to fit rather than what
   * is actually warm. Instead [get] falls through to disk and promotes, and every count of what is
   * cached asks both tiers. That is what lets `cached` keep climbing past the point where memory
   * alone would have started evicting.
   */
  private val persistence: ThemeCacheStore.Generation? = null,
  /**
   * Why there is no disk tier, when [persistence] is null — surfaced as
   * [CatalogRenderCacheSnapshot.persistenceOff].
   */
  private val persistenceOffReason: String? = null,
) {
  val maxBytes: Long = maxBytes.coerceAtLeast(0)
  private val renderLock = Any()
  // Access-order map: the byte cap evicts the least-recently-read render first.
  private val renders = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
  private val targetKeys = ConcurrentHashMap.newKeySet<String>()
  // The bounded set the DISK tier accepts — see [configurePersistable] for why it is not
  // targetKeys.
  private val persistableKeys = ConcurrentHashMap.newKeySet<String>()
  // False while renders ADOPTED FROM A PREVIOUS PROCESS are still unverified — see [get]. Only
  // those
  // are in question: anything this process rendered came from this renderer and needs no checking,
  // which is why the quarantine is keyed on `wasAdopted` rather than on having a disk tier at all.
  private val persistenceTrusted = java.util.concurrent.atomic.AtomicBoolean(false)
  private val failedKeys = ConcurrentHashMap.newKeySet<String>()
  // Consecutive live-render failures per key, and the last reason seen. Both are cleared by a
  // successful [put], so a key only stays latched while it keeps failing.
  private val failureCounts = ConcurrentHashMap<String, Int>()
  private val failureReasons = ConcurrentHashMap<String, String>()
  // Consecutive background `Busy` outcomes per key. Separate from [failureCounts] because the two
  // have very different tolerances — see [BUSY_LATCH]. Cleared by a successful [put] like the rest.
  private val busyCounts = ConcurrentHashMap<String, Int>()
  private val byteCount = AtomicLong(0)
  private val evictionCount = AtomicLong(0)
  // Read outcomes, so `/status` can say whether this cache is answering anything at all. Split by
  // tier because the two have different costs and different fixes: a memory hit is free, a disk hit
  // is the persistence tier earning its I/O, and a miss is a render.
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
  // Entries the OPTIMIZER produced. The rate's denominator is optimizer time, so its numerator has
  // to be optimizer output: foreground renders land in this same cache via `cacheCatalogRender`,
  // and counting them would report a prefetch rate the prefetcher never achieved.
  private val optimizerProduced = java.util.concurrent.atomic.AtomicInteger(0)

  /** The idle gate handed the pass its turn. */
  fun recordTurnGranted() {
    turnsGranted.incrementAndGet()
  }

  /** Traffic took the turn back. */
  fun recordTurnYielded() {
    turnsYielded.incrementAndGet()
  }

  /**
   * The ceiling granted a turn the gate would not have. Counted in [recordTurnGranted] too, so
   * `turnsGranted` stays the total and this is the slice of it the box never actually offered.
   */
  fun recordTurnForced() {
    turnsForced.incrementAndGet()
    turnsGranted.incrementAndGet()
  }

  /** Wall-clock the idle gate withheld a turn because the box looked busy. */
  fun recordGateWait(millis: Long) {
    if (millis > 0) gateWaitMillis.addAndGet(millis)
  }

  /** Wall-clock spent holding a turn but queued behind other catalogs for a render permit. */
  fun recordPermitWait(millis: Long) {
    if (millis > 0) permitWaitMillis.addAndGet(millis)
  }

  /**
   * One batch completed. [width] is the peak number of daemons that rendered **concurrently**, not
   * the job count — see [ThemeOptimizationSnapshot.lastBatchWidth] for why those differ.
   */
  fun recordBatch(width: Int, millis: Long) {
    lastBatchWidth.set(width)
    maxBatchWidth.accumulateAndGet(width, ::maxOf)
    if (millis > 0) batchMillis.addAndGet(millis)
  }

  /** Entries this batch actually produced — the rate's numerator. */
  fun recordProduced(count: Int) {
    if (count > 0) optimizerProduced.addAndGet(count)
  }

  /**
   * A cold daemon warm the optimizer waited out. Real render work, so it counts toward
   * [ThemeOptimizationSnapshot.renderMillis] and the rate's denominator — leaving it out of every
   * bucket made a cold catalog report a rate it was nowhere near. But it is kept apart from
   * [recordBatch] time because it produces no entries and does not recur per entry.
   */
  fun recordWarm(millis: Long) {
    if (millis > 0) warmMillis.addAndGet(millis)
  }

  fun configureTargets(keys: Collection<String>) {
    targetKeys += keys
    configurePersistable(keys)
    refreshCompletion()
  }

  /**
   * Declare the finite set of keys the disk tier will accept, without claiming them as optimization
   * targets.
   *
   * The two are separate because the eager prefetch pass can be switched off
   * (`-Dcomposeai.serve.themeOptimization=false`) while foreground renders of those same declared
   * themes carry on. Gating persistence on [targetKeys] alone meant a disabled optimizer left the
   * set empty for the host's lifetime, so every render a visitor actually asked for was refused by
   * the disk tier and every restart began again — persistence silently doing nothing on exactly the
   * configuration that most needs the renders it does get to be durable.
   *
   * Kept out of [targetKeys] so `/status` still reports no optimization row for a disabled pass,
   * rather than one parked at "waiting" forever.
   */
  fun configurePersistable(keys: Collection<String>) {
    persistableKeys += keys
  }

  /**
   * The render for [key] from memory, or from disk (promoted into memory), or null.
   *
   * The disk read is on the miss path only, so a warm working set costs exactly what it did before
   * persistence existed.
   */
  fun get(key: String): ByteArray? {
    synchronized(renderLock) { renders[key] }
      ?.let {
        memoryHits.incrementAndGet()
        return it
      }
    // Adopted-but-unverified bytes are NOT served. Verification is asynchronous — it needs a lane
    // and a warm daemon — and until it settles these renders are exactly the thing the fingerprint
    // might have got wrong. Serving them meanwhile hands out stale pixels precisely in the window
    // the safety check exists to cover, and traffic can hold that window open for a long time by
    // keeping the box non-idle. A miss here costs a fresh render; a hit here could cost the truth.
    //
    // Only the READ path is withheld. [contains] still reports them, so the optimizer does not
    // re-render what is already on disk while the question is open.
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
    // Promoted through the ordinary write path so it takes part in the LRU and the byte accounting
    // like any other entry — but NOT written back to disk, which is where it just came from.
    //
    // Only if no drop has happened since the epoch was sampled. The disk read above holds neither
    // the render lock nor the generation write lock, by design — a visitor must not queue behind
    // another replica's writes — so a `dropPersisted` can unlink the generation and clear memory
    // in the gap between the sample and here. Promoting then would reinsert the very bytes the
    // drop just declared wrong into the tier in FRONT of the one that was emptied, and every
    // subsequent request would be served them from an empty disk. The bytes still go back to this
    // caller: it asked before the drop and a render it already holds is not made wrong by one.
    remember(key, fromDisk, validEpoch = epoch)
    return fromDisk
  }

  /**
   * Bumped by every [dropPersisted]. Read either side of an unlocked disk read so bytes fetched
   * before a drop cannot be promoted into memory after it.
   */
  private val dropEpoch = AtomicLong()

  /**
   * Targets held from an older build and queued for re-render, in a stable order.
   *
   * Dirty entries are *warm* — [contains] reports them and, once the sample has vouched for the
   * generation, [get] serves them — so the optimizer's "not cached yet" filter passes straight over
   * them, which is correct: a possibly-stale preview beats a cold render, and a build whose
   * renderer genuinely moved is caught by the sample rather than by re-rendering everything on
   * spec. This is the second queue, worked once the gaps are filled, so the store converges on
   * pixels this renderer actually produced instead of trusting a version boundary forever.
   */
  fun dirtyTargets(): List<String> {
    val store = persistence ?: return emptyList()
    if (store.dirtyCount() == 0) return emptyList()
    // Walked from the TARGETS, not from the directory: the store holds hashed file names and the
    // hash is one-way, so what is on disk cannot name itself. The declared target set is the only
    // place the keys still exist.
    return targetKeys.filter(store::isDirty).sorted()
  }

  /**
   * Whether a background pass exists that could refill this cache.
   *
   * The same question [markPersistedDirty] answers with `-1`, asked by a caller that has already
   * done its work and only needs to know whether waking anything would achieve something. With
   * `-Dcomposeai.serve.themeOptimization=false` the startup configures [persistableKeys] and
   * deliberately leaves [targetKeys] empty: renders still persist, but there is no pass with a
   * queue to work, so a wake buys nothing and costs a resumed host, a possible Android daemon cold
   * start and a live seat.
   */
  val hasOptimizationTargets: Boolean
    get() = targetKeys.isNotEmpty()

  /**
   * Mark every persisted render for this catalog dirty, and report how many.
   *
   * The operator's "regenerate this catalog", for pixels suspected wrong by something no
   * fingerprint sees. Deliberately not a delete: the entries keep serving while the background pass
   * replaces them.
   */
  fun markPersistedDirty(): Int {
    val store = persistence ?: return 0
    // A pass that was never given targets cannot regenerate anything. With
    // `-Dcomposeai.serve.themeOptimization=false` the startup configures `persistableKeys` and
    // deliberately leaves `targetKeys` empty, so marking would report a queue the optimizer has no
    // way to work — an operator told "1,606 queued" would wait for a regeneration that is never
    // coming. -1 says the action is unavailable here, which the route reports rather than fakes.
    if (targetKeys.isEmpty()) return -1
    return store.markAllDirty()
  }

  /**
   * Throw this catalog's persisted renders away outright, and forget them in memory too.
   *
   * The decisive half of the pair, for pixels an operator has decided are wrong rather than merely
   * suspect. Every preview goes cold at once and is re-rendered from nothing — which is the cost
   * [markPersistedDirty] exists to avoid, so this is the second choice of the two, not the default.
   *
   * The memory window is cleared with it. Leaving it would keep serving the very renders just
   * declared wrong, from the tier in front of the one that was emptied.
   */
  fun dropPersisted(): Boolean {
    // `true` when there is no disk tier: the memory window below is all this cache has, clearing it
    // is the whole of the drop, and it cannot fail. Reporting false would send the caller into a
    // retry loop against a generation write lock that does not exist — the route turns false into a
    // 409 whose contract is "contended, try again", and every retry would answer the same.
    val discarded = persistence?.discard() ?: true
    synchronized(renderLock) {
      // Inside the lock and BEFORE the clear: a promotion racing this drop takes the lock to
      // insert, so bumping here means it either lands before the clear (and is cleared) or reads
      // the new epoch and declines. Bumping after the clear would leave a window where it does
      // neither.
      dropEpoch.incrementAndGet()
      renders.clear()
      byteCount.set(0)
    }
    state.set("paused")
    completedAt.set(0)
    return discarded
  }

  /** Whether [key] is warm in either tier, without paying to read the bytes. */
  fun contains(key: String): Boolean =
    synchronized(renderLock) { renders.containsKey(key) } || persistence?.contains(key) == true

  fun put(key: String, png: ByteArray) {
    // Disk first, and NOT gated on the memory cap: the disk tier has its own budget and is the
    // authoritative store behind a deliberately smaller memory window, so a render too large for
    // that window must still be persisted rather than silently re-rendered after every restart.
    //
    // **Only configured targets are persisted.** This method also takes successful foreground
    // renders with arbitrary overrides — widths, locales, devices, knob values — and those are
    // unbounded in a way `previews × declaredThemes` is not: on a public box a visitor could mint
    // distinct keys indefinitely, and since a live generation is never evicted to honour
    // `--theme-cache-max-bytes`, that fills the volume. Ad-hoc override renders stay in the bounded
    // memory tier, exactly as they did before persistence existed.
    // While quarantined, a fresh render REPLACES the adopted copy rather than being dropped because
    // a file already sits at that key — see [ThemeCacheStore.Generation.put].
    if (key in persistableKeys)
      persistence?.put(
        key,
        png,
        // Dirty as well as quarantined. A regenerated entry is the whole point of the dirty queue
        // and it must overwrite the older build's bytes; without this the pass renders it, the
        // store declines the write, and the flag never clears.
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
     * Insert only while [dropEpoch] still reads this value — see [get], where a disk read runs
     * unlocked and a drop can land underneath it. Checked INSIDE the lock: comparing before taking
     * it only narrows the window, because the drop that invalidates these bytes can happen between
     * the comparison and the insert.
     */
    validEpoch: Long? = null,
  ) {
    synchronized(renderLock) {
      if (validEpoch != null && dropEpoch.get() != validEpoch) return@synchronized
      if (renders.containsKey(key)) {
        // A regenerated dirty entry has to displace the copy the memory window is still serving,
        // or the read path would keep handing out the older build's pixels for as long as the LRU
        // held them — the disk tier converged and the tier in front of it did not.
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
        // An eviction only un-completes the catalog when the entry is gone for good. With a disk
        // tier it is not: the memory cap is smaller than a warmed catalog by design, so treating
        // every eviction as lost progress would park a fully-warmed catalog at `paused` forever and
        // send the optimizer back to re-render what is already on disk.
        if (eldest.key in targetKeys && persistence?.contains(eldest.key) != true) {
          state.set("paused")
          completedAt.set(0)
        }
      }
    }
  }

  /**
   * Check a sample of the persisted generation against what the renderer produces **now**, and drop
   * the whole generation if they disagree.
   *
   * This is the safety net for the one thing [ThemeCacheFingerprint] cannot promise. The
   * fingerprint covers the inputs it was told about; an input nobody thought of — a base image
   * bumped without a release, a render default that never reached the config string — changes the
   * pixels without changing the name, and every entry under that name is then quietly wrong. Wrong
   * pixels matter more here than in an ordinary build cache: a stale build artifact gets caught by
   * a test, a stale preview is shown to an agent as ground truth.
   *
   * [render] returns the freshly rendered bytes for a cache key, or null if it could not render —
   * which is **not** a mismatch and must not drop anything, or a busy daemon would wipe the cache.
   *
   * Returns true if the generation is trustworthy (verified, or nothing to verify).
   */
  fun verifySample(
    sampleSize: Int = VERIFY_SAMPLE,
    render: (String) -> ByteArray?,
  ): VerifyOutcome {
    val store = persistence ?: return VerifyOutcome.NOTHING_TO_VERIFY
    // Only entries ADOPTED FROM THE PREVIOUS PROCESS can answer the question. On a partly warmed
    // restart, foreground traffic persists missing keys before the idle verification task runs, and
    // sampling those would let five renders this process just made "verify" a generation whose old
    // bytes were never looked at — trusting the cache on the strength of checking itself.
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
        // Only the ADOPTED entries — the ones that were on disk when this generation opened. A
        // generation can now hold renders from two processes at once, and the sample has just
        // *confirmed* the ones this process made by disagreeing with the ones it inherited. Taking
        // the lot would throw away however long this process has spent rendering, to fix a problem
        // those renders do not have.
        //
        // Adopted, NOT dirty. Dirtiness asks "did a different BUILD write this", and the sample
        // never tests that question: it draws its candidates from `wasAdopted`. A same-version
        // restart inherits the previous process's renders as clean, so narrowing to dirty here
        // would delete an older build's leftovers, report a positive count that suppresses the
        // fallback `discard`, lift the quarantine, and go straight back to serving the entries the
        // sample was drawn from.
        val dropped = store.discardAdopted()
        val discarded = if (dropped >= 0) dropped > 0 || store.discard() else false
        synchronized(renderLock) {
          // Same hazard as a drop, and the same fix: these bytes have just been proved wrong, so a
          // promotion already in flight must not put them back after the clear.
          dropEpoch.incrementAndGet()
          renders.clear()
          byteCount.set(0)
        }
        state.set("paused")
        completedAt.set(0)
        // Trust follows the DISCARD, not the detection. `discard` can fail — its generation write
        // lock is held by a concurrent foreground render, and it retries but does not block
        // forever — and trusting persistence anyway would leave the very PNGs just proved wrong on
        // disk and immediately serve them as verified. While `persistenceTrusted` stays false the
        // adopted entries are withheld from the read path (see [get]) and the next verification
        // pass tries again, which is the quarantine this case needs.
        if (discarded) persistenceTrusted.set(true)
        return if (discarded) VerifyOutcome.MISMATCH else VerifyOutcome.MISMATCH_UNDISCARDED
      }
    }
    // Zero successful comparisons is NOT a pass. Every sampled render can come back Busy, Failed or
    // served from a cache during a cold start, and treating that as "verified" would latch the
    // check permanently on the one occasion it was never actually performed — leaving a stale
    // generation to serve wrong pixels for the life of the process. The caller retries instead.
    if (compared > 0) persistenceTrusted.set(true)
    return if (compared > 0) VerifyOutcome.VERIFIED else VerifyOutcome.NO_EVIDENCE
  }

  /** What [verifySample] managed to establish. */
  enum class VerifyOutcome {
    /** At least one persisted render was re-rendered and matched. */
    VERIFIED,
    /** No disk tier, or nothing adopted from it — there is nothing that could be stale. */
    NOTHING_TO_VERIFY,
    /** The renderer answered nothing usable, so the question is still open. Ask again. */
    NO_EVIDENCE,
    /** A persisted render no longer matches; the generation has been discarded. */
    MISMATCH,
    /**
     * A persisted render no longer matches, and the generation could **not** be discarded — its
     * write lock stayed held through every retry.
     *
     * Separate from [MISMATCH] because the two need opposite treatment from the caller. After a
     * successful discard the question is answered: the suspect bytes are gone and verification can
     * latch. Here they are still on disk, so the caller must NOT latch — the adopted entries stay
     * withheld from the read path and the next pass has to ask again. Latching would leave them
     * quarantined for the life of the process while the optimizer, which reads `contains`, went on
     * believing they were present: repeated foreground renders, stale files, and no further attempt
     * to clear either.
     */
    MISMATCH_UNDISCARDED;

    /** Whether the persisted renders may be trusted from here on. */
    val settled: Boolean
      get() = this == VERIFIED || this == NOTHING_TO_VERIFY
  }

  fun markRunning(nowMillis: Long) {
    startedAt.compareAndSet(0, nowMillis)
    state.set("running")
  }

  fun markPaused() {
    if (!snapshot().fullyOptimized) state.set("paused")
  }

  /**
   * Mark [key] as one the optimizer could not fill, for the `/status` `failed` count.
   *
   * This is a **metric**, not a verdict: the optimizer gives up after a bounded number of attempts,
   * and it gives up on a key the daemon was merely too busy to get to just as it does on one that
   * genuinely threw. Only a [reason] — captured from a real [RenderOutcome.Failed] — makes the key
   * terminal for [failureReason]. Without that distinction, three `Busy` outcomes during a warm
   * would tell the next visitor the preview can never render.
   */
  fun markFailed(key: String, reason: String? = null) {
    failedKeys += key
    reason?.let { failureReasons[key] = it }
  }

  /**
   * Record one live-render failure for [key] on the **on-demand** lane and report whether that key
   * has now latched as unrenderable ([FAILURE_LATCH] consecutive failures).
   *
   * Without this, only the background optimizer ever marked a key failed, so a preview the daemon
   * genuinely cannot render — a `painterResource` whose drawable isn't in the bundle, say — was
   * re-attempted on every request forever. Each attempt occupies the daemon's render lock long
   * enough to make *other* previews back off as [RenderOutcome.Busy], so one broken card degrades
   * the whole grid. Latching lets the render lane answer immediately instead.
   *
   * [FAILURE_LATCH] rather than one strike because a first failure can be a cold-start timeout or a
   * daemon restart; a successful [put] clears the count, so only a run of failures latches.
   */
  fun recordRenderFailure(key: String, reason: String): Boolean {
    failureReasons[key] = reason
    val count = failureCounts.merge(key, 1, Int::plus) ?: 1
    if (count < FAILURE_LATCH) return false
    failedKeys += key
    return true
  }

  /**
   * Record one **background** `Busy` outcome for [key] and report whether it has now latched
   * ([BUSY_LATCH] consecutive).
   *
   * `Busy` is "ask again", and for a warming daemon that is exactly right — which is why the
   * optimizer deliberately left it unmarked. But "ask again" with no ceiling is indistinguishable
   * from "never", and the optimizer has no other way to notice: it does not re-enter a finished
   * pass, so a key that answers `Busy` on the one pass it gets is simply abandoned, uncounted.
   *
   * Latching supplies the missing terminal state. Once latched the key gets a [reason], so
   * [failureReason] answers the request lane immediately instead of sending the browser back into a
   * `retry-after` loop it can never win, `markPassFinished` reports `degraded` rather than a
   * `paused` that looks like ordinary throttling, and `/status` shows a non-zero `failed` naming
   * how many previews are stuck. A successful [put] clears the count, so a genuinely contended key
   * that eventually renders is never penalised.
   */
  fun recordBackgroundBusy(key: String): Boolean {
    val count = busyCounts.merge(key, 1, Int::plus) ?: 1
    if (count < BUSY_LATCH) return false
    failedKeys += key
    failureReasons[key] =
      "no live lane produced this theme render after $count attempts (daemon busy or absent)"
    return true
  }

  /**
   * Why [key] cannot be rendered, once it has latched as failed; null while it may still succeed.
   * Callers use this to answer a request without going near the daemon.
   *
   * Requires **both** halves: the key is latched, *and* a real render failure supplied a reason. A
   * key in [failedKeys] with no reason is one the optimizer ran out of attempts on — retryable
   * `Busy`, most often — and must stay retryable for a request, or a preview that was merely
   * contended during the optimization pass would be reported as permanently dead to every later
   * visitor.
   */
  fun failureReason(key: String): String? = if (key in failedKeys) failureReasons[key] else null

  fun markPassFinished(nowMillis: Long) {
    if (targetKeys.all(::contains)) {
      completedAt.compareAndSet(0, nowMillis)
      state.set("complete")
    } else {
      state.set(if (failedKeys.isEmpty()) "paused" else "degraded")
    }
  }

  fun snapshot(): ThemeOptimizationSnapshot {
    // Counted across BOTH tiers. Counting memory alone would report a fully warmed catalog as
    // partially cached the moment the 128 MB window started evicting, which is precisely the
    // condition persistence exists to create.
    val cachedTargets = targetKeys.count(::contains)
    val total = targetKeys.size
    val complete = total > 0 && cachedTargets == total
    // Read each counter ONCE and derive everything from those values. Reading them per-field lets a
    // wait that finishes mid-snapshot land in one field and not another, publishing a row where
    // `waitingMillis < gateWaitMillis + permitWaitMillis` — a self-contradicting diagnostic is
    // worse than a slightly stale one.
    val batch = batchMillis.get()
    val warm = warmMillis.get()
    val render = batch + warm
    val gateWait = gateWaitMillis.get()
    val permitWait = permitWaitMillis.get()
    val waiting = gateWait + permitWait
    val rate = ratePerMinute(render + waiting)
    // Read the dirty tier ONCE, here, for the same reason the counters above are read once — and
    // with one extra constraint: `dirtyCount` is where the rollout reconcile hangs, so calling it
    // in the `dirty` field below would run that reconcile *after* `failed` had already asked
    // `isDirty` about each failed key. A key the reconcile re-dirties would then land in one field
    // and not the other. Reconciling first makes both fields describe the same instant.
    val dirtyTotal = persistence?.dirtyCount() ?: 0
    return ThemeOptimizationSnapshot(
      state =
        if (complete) "complete" else state.get().let { if (it == "complete") "paused" else it },
      total = total,
      cached = cachedTargets,
      remaining = (total - cachedTargets).coerceAtLeast(0),
      // A failed key that is now cached has been rendered since, so it is no longer a failure —
      // EXCEPT when what is cached is another build's work. A dirty entry is `contains` by design
      // (that is the whole point of serving it while the pass replaces it), so `!contains` alone
      // counted a repeatedly-failing dirty re-render as zero, and the status row this catalog's
      // dirty state exists to drive could never report one. Cheap despite the per-key question:
      // this walks `failedKeys`, which is empty on a healthy catalog, not the 10,440 target keys.
      failed =
        failedKeys.count {
          it in targetKeys && (!contains(it) || persistence?.isDirty(it) == true)
        },
      cachedBytes = byteCount.get(),
      fullyOptimized = complete,
      startedAtEpochMillis = startedAt.get().takeIf { it > 0 },
      completedAtEpochMillis = completedAt.get().takeIf { it > 0 },
      // Rate over the pass's ACTIVE time (render + gate wait), not wall-clock since it started:
      // wall-clock includes stretches where the pass held no turn at all, which drags the figure
      // toward zero and hides whether it is keeping up while it runs.
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
      // The store's own count, not `dirtyTargets().size`: `/status` snapshots every catalog on
      // every request and m3-catalog alone declares 10,440 targets, so the filtered walk belongs on
      // the optimizer's path — which runs per slice — and not on this one. Read above, so the
      // reconcile it carries runs before `failed` reads the same state.
      dirty = dirtyTotal,
    )
  }

  fun renderCacheSnapshot(): CatalogRenderCacheSnapshot {
    // Read once and derive, for the reason [snapshot] gives: a rate computed from counters read
    // separately can publish a hit rate that does not match the counts printed beside it.
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

  companion object {
    const val DEFAULT_MAX_BYTES: Long = 128L * 1024 * 1024

    /**
     * Persisted renders re-rendered and compared when a generation is adopted.
     *
     * Small on purpose. This is a smoke test for "did the fingerprint miss an input", and an input
     * that changes the renderer changes it for every preview — so a handful of entries answers the
     * question as well as a thousand would, at a cost (a few seconds) that a startup can absorb
     * against the 28 hours of rendering it is protecting.
     */
    const val VERIFY_SAMPLE: Int = 5

    /**
     * Consecutive on-demand render failures before a key is treated as permanently unrenderable.
     */
    const val FAILURE_LATCH = 3

    /**
     * Consecutive `Busy` outcomes on the BACKGROUND lane before a key is treated as one the
     * optimizer cannot fill.
     *
     * Deliberately far looser than [FAILURE_LATCH]: `Busy` really does mean "ask again" for a
     * warming daemon, and a key that is merely contended must survive a long run of them. What it
     * must not have is *no* ceiling. Without one the pass can never converge — `markPassFinished`
     * only reports `complete` when every target is cached, and a key that answers `Busy` forever is
     * neither cached nor failed, so the catalog sits at `paused` with `failed: 0` and nothing ever
     * says which previews are stuck.
     *
     * Observed on meshcore-mobile: 84 of 372 targets (21 previews x 4 declared themes) pinned at
     * `paused 288/372, failed: 0` across two server lifetimes, while all fourteen other catalogs on
     * the same box reached `complete`. On the request lane those same previews answered `503 render
     * busy; retry shortly` on 39 of 39 attempts spread over several minutes — a `retry-after` the
     * server could never honour, which the grid's three retries then burned before showing "Theme
     * preview unavailable".
     */
    const val BUSY_LATCH = 12
  }
}
