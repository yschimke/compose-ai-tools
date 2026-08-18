package ee.schimke.composeai.cli.serve

import kotlinx.serialization.Serializable

/**
 * Aggregate render-performance counters for one daemon-backed [ServeHost] — the serve-side
 * companion to the daemon's own `compose-ai-daemon: [+Nms]` stderr markers, kept queryable so
 * `/status.json` can report cold vs warm render behaviour without anyone tailing container logs.
 *
 * Recorded by [ServeRenderHost.render] around the full serve-side render round-trip (renderNow →
 * renderFinished → PNG read), which is the latency a `/render` caller actually experiences —
 * deliberately not the daemon-internal engine time, which the per-phase trace recorder
 * (`composeai.daemon.perfettoTrace`) already covers.
 *
 * Thread-safe; all methods take one short critical section. Percentiles come from a bounded ring of
 * the most recent [WINDOW_SIZE] successful render durations, so a long-lived daemon reports recent
 * behaviour rather than an all-time blur (the all-time min/max/avg/first are kept separately).
 */
class RenderPerfStats {
  private val lock = Any()

  private var renders = 0L
  private var ok = 0L
  private var failed = 0L
  private var timedOut = 0L
  private var busy = 0L
  private var shortCircuited = 0L
  private var cacheHits = 0L
  private var coldOk = 0L
  private var firstRenderMs: Long? = null
  private var coldMaxMs = 0L
  private var minMs = Long.MAX_VALUE
  private var maxMs = 0L
  private var totalMs = 0L
  private var lastMs: Long? = null
  private var lastRenderFailed = false
  private var lastFailureReason: String? = null
  private var lastFailureAtEpochMillis: Long? = null
  private val recentFailures = ArrayDeque<RenderFailureSample>()
  private val window = LongArray(WINDOW_SIZE)
  private var windowCount = 0
  private var windowIdx = 0

  /** A `/render` served straight from the PNG cache — no daemon round-trip. */
  fun recordCacheHit(): Unit = synchronized(lock) { cacheHits++ }

  /** The bounded render-lock acquire backed off ([RenderOutcome.Busy] → caller serves baked). */
  fun recordBusy(): Unit = synchronized(lock) { busy++ }

  /**
   * A render refused without asking the daemon because this host's [RenderCircuitBreaker] is open.
   * Its own counter rather than a [recordFailed]: the daemon was never asked, so folding these into
   * `failed` would inflate the very failure rate that tripped the breaker and hide how much work
   * the breaker is saving.
   */
  fun recordShortCircuit(): Unit = synchronized(lock) { shortCircuited++ }

  /**
   * A render that ended in [RenderOutcome.Failed]; [timeout] when it blew its render budget.
   * [reason] is the outcome's failure text — kept (truncated) so `/status.json` can say WHY a
   * catalog's live lane is failing without anyone tailing container logs: a daemon whose every
   * render fails otherwise shows only a climbing `failed` counter while the composite silently
   * serves baked fallback.
   */
  fun recordFailed(durationMs: Long, timeout: Boolean, reason: String? = null): Unit =
    synchronized(lock) {
      renders++
      failed++
      lastRenderFailed = true
      if (timeout) timedOut++
      lastMs = durationMs
      if (reason != null) {
        lastFailureReason = reason.take(MAX_FAILURE_REASON_LENGTH)
        lastFailureAtEpochMillis = System.currentTimeMillis()
        recentFailures.addLast(
          RenderFailureSample(
            atEpochMillis = lastFailureAtEpochMillis!!,
            durationMs = durationMs,
            timedOut = timeout,
            reason = lastFailureReason!!,
          )
        )
        while (recentFailures.size > FAILURE_WINDOW_SIZE) recentFailures.removeFirst()
      }
    }

  /**
   * A successful render taking [durationMs] end-to-end. [cold] marks renders issued while the host
   * had not yet completed any successful render — the cold-start population the background-boot /
   * warm-render work targets — so `/status` can separate first-render latency from steady state.
   */
  fun recordOk(durationMs: Long, cold: Boolean): Unit =
    synchronized(lock) {
      renders++
      ok++
      lastRenderFailed = false
      if (cold) {
        coldOk++
        if (firstRenderMs == null) firstRenderMs = durationMs
        if (durationMs > coldMaxMs) coldMaxMs = durationMs
      }
      if (durationMs < minMs) minMs = durationMs
      if (durationMs > maxMs) maxMs = durationMs
      totalMs += durationMs
      lastMs = durationMs
      window[windowIdx] = durationMs
      windowIdx = (windowIdx + 1) % window.size
      if (windowCount < window.size) windowCount++
    }

  fun snapshot(): RenderPerfSnapshot =
    synchronized(lock) {
      val sorted = window.copyOf(windowCount).also { it.sort() }
      fun pct(p: Double): Long? =
        if (sorted.isEmpty()) null else sorted[((sorted.size - 1) * p).toInt()]
      RenderPerfSnapshot(
        renders = renders,
        ok = ok,
        failed = failed,
        timedOut = timedOut,
        busy = busy,
        shortCircuited = shortCircuited,
        cacheHits = cacheHits,
        coldRenders = coldOk,
        firstRenderMs = firstRenderMs,
        coldMaxMs = if (coldOk > 0) coldMaxMs else null,
        minMs = if (ok > 0) minMs else null,
        maxMs = if (ok > 0) maxMs else null,
        avgMs = if (ok > 0) totalMs / ok else null,
        lastMs = lastMs,
        p50Ms = pct(0.5),
        p95Ms = pct(0.95),
        windowSize = windowCount,
        lastRenderFailed = lastRenderFailed,
        lastFailureReason = lastFailureReason,
        lastFailureAtEpochMillis = lastFailureAtEpochMillis,
        recentFailures = recentFailures.toList().asReversed(),
      )
    }

  companion object {
    /** Ring size for the recent-durations percentile window. */
    const val WINDOW_SIZE: Int = 128

    /** Cap on the carried failure-reason text — enough for a message, not a stack trace. */
    const val MAX_FAILURE_REASON_LENGTH: Int = 300

    /** Number of render errors retained for the human status page. */
    const val FAILURE_WINDOW_SIZE: Int = 10
  }
}

/** One recent failed render, newest-first in [RenderPerfSnapshot.recentFailures]. */
@Serializable
data class RenderFailureSample(
  val atEpochMillis: Long,
  val durationMs: Long,
  val timedOut: Boolean,
  val reason: String,
)

/**
 * Point-in-time projection of [RenderPerfStats], serialized verbatim onto `/status.json`
 * (`runningServers[].renderStats` and the server-wide `renderStats` aggregate). All duration fields
 * are wall-clock milliseconds of the serve-side render round-trip; null means "no sample yet".
 * Additive on `compose-preview-serve/status/v1`.
 */
@Serializable
data class RenderPerfSnapshot(
  /** Renders attempted against the daemon (ok + failed; excludes cache hits and busy backoffs). */
  val renders: Long,
  val ok: Long,
  val failed: Long,
  /** Subset of [failed] that blew the render budget. */
  val timedOut: Long,
  /** Bounded lock acquires that backed off to baked ([RenderOutcome.Busy]). */
  val busy: Long,
  /**
   * Renders refused without asking the daemon because the host's [RenderCircuitBreaker] is open —
   * the retry storm that is no longer happening. Excluded from [renders] and [failed]: the daemon
   * was never asked.
   */
  val shortCircuited: Long = 0,
  /** `/render`s served from the PNG cache without waking the daemon. */
  val cacheHits: Long,
  /** Successful renders issued before the host's first success — the cold-start population. */
  val coldRenders: Long,
  /** Duration of the host's very first successful render (the cold-start headline number). */
  val firstRenderMs: Long? = null,
  val coldMaxMs: Long? = null,
  val minMs: Long? = null,
  val maxMs: Long? = null,
  val avgMs: Long? = null,
  /** Duration of the most recent render (ok or failed). */
  val lastMs: Long? = null,
  /** Percentiles over the last [windowSize] successful renders. */
  val p50Ms: Long? = null,
  val p95Ms: Long? = null,
  val windowSize: Int = 0,
  /** Current lane signal: true only when the most recently completed daemon render failed. */
  val lastRenderFailed: Boolean = false,
  /**
   * The most recent failure's reason text (truncated) + when it happened — the "why" behind a
   * non-zero [failed] counter, so a catalog whose live lane silently falls back to baked is
   * diagnosable from `/status.json` alone.
   */
  val lastFailureReason: String? = null,
  val lastFailureAtEpochMillis: Long? = null,
  /** Bounded, newest-first failure detail for status diagnostics. */
  val recentFailures: List<RenderFailureSample> = emptyList(),
  /**
   * Set while this lane's [RenderCircuitBreaker] is open — the host has stopped attempting renders
   * and is answering with [RenderBreakerSnapshot.reason] instead. Null is the healthy case.
   */
  val breaker: RenderBreakerSnapshot? = null,
) {
  companion object {
    /**
     * Server-wide roll-up across daemons for the `/status` summary. Counts sum; min/max span;
     * `avgMs` is ok-weighted; `firstRenderMs` reports the WORST first render (the number the
     * cold-start work drives down). Percentiles don't merge across windows, so they stay null.
     */
    fun aggregate(snapshots: List<RenderPerfSnapshot>): RenderPerfSnapshot? {
      if (snapshots.isEmpty()) return null
      val ok = snapshots.sumOf { it.ok }
      return RenderPerfSnapshot(
        renders = snapshots.sumOf { it.renders },
        ok = ok,
        failed = snapshots.sumOf { it.failed },
        timedOut = snapshots.sumOf { it.timedOut },
        busy = snapshots.sumOf { it.busy },
        shortCircuited = snapshots.sumOf { it.shortCircuited },
        cacheHits = snapshots.sumOf { it.cacheHits },
        coldRenders = snapshots.sumOf { it.coldRenders },
        firstRenderMs = snapshots.mapNotNull { it.firstRenderMs }.maxOrNull(),
        coldMaxMs = snapshots.mapNotNull { it.coldMaxMs }.maxOrNull(),
        minMs = snapshots.mapNotNull { it.minMs }.minOrNull(),
        maxMs = snapshots.mapNotNull { it.maxMs }.maxOrNull(),
        avgMs = if (ok > 0) snapshots.sumOf { (it.avgMs ?: 0) * it.ok } / ok else null,
        lastMs = null,
        p50Ms = null,
        p95Ms = null,
        windowSize = snapshots.sumOf { it.windowSize },
        lastRenderFailed = snapshots.any { it.lastRenderFailed },
        // Most recent failure across daemons (by timestamp) so the roll-up carries a "why" too.
        lastFailureReason =
          snapshots
            .filter { it.lastFailureAtEpochMillis != null }
            .maxByOrNull { it.lastFailureAtEpochMillis!! }
            ?.lastFailureReason,
        lastFailureAtEpochMillis = snapshots.mapNotNull { it.lastFailureAtEpochMillis }.maxOrNull(),
        recentFailures =
          snapshots
            .flatMap { it.recentFailures }
            .sortedByDescending { it.atEpochMillis }
            .take(RenderPerfStats.FAILURE_WINDOW_SIZE),
        // A fatal (linkage) trip wins over a rate trip, then the most recent — the roll-up should
        // name the breaker that most needs a human, not whichever daemon happens to sort first.
        breaker =
          snapshots
            .mapNotNull { it.breaker }
            .filter { it.open }
            .sortedWith(
              compareByDescending<RenderBreakerSnapshot> { it.fatal }
                .thenByDescending { it.openedAtEpochMillis ?: 0 }
            )
            .firstOrNull(),
      )
    }
  }
}
