package ee.schimke.composeai.cli.serve

import kotlinx.serialization.Serializable

/**
 * Render-performance counters for one daemon-backed [ServeHost], so `/status.json` reports cold vs
 * warm render behaviour without tailing logs. Measures the full serve-side round trip (renderNow →
 * renderFinished → PNG read), not daemon engine time (see `composeai.daemon.perfettoTrace`).
 *
 * Thread-safe. Percentiles come from a ring of the last [WINDOW_SIZE] successful renders; all-time
 * min/max/avg/first are kept separately.
 */
public class RenderPerfStats {
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
  public fun recordCacheHit(): Unit = synchronized(lock) { cacheHits++ }

  /** The bounded render-lock acquire backed off ([RenderOutcome.Busy] → caller serves baked). */
  public fun recordBusy(): Unit = synchronized(lock) { busy++ }

  /**
   * A render refused because the [RenderCircuitBreaker] is open. Separate from [recordFailed] since
   * the daemon was never asked; counting it as failed would inflate the rate that tripped the
   * breaker.
   */
  public fun recordShortCircuit(): Unit = synchronized(lock) { shortCircuited++ }

  /**
   * A render ending in [RenderOutcome.Failed]; [timeout] when it blew its budget. [reason] is kept
   * (truncated) so `/status.json` can say why a lane is failing.
   */
  public fun recordFailed(durationMs: Long, timeout: Boolean, reason: String? = null): Unit =
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
   * A successful render of [durationMs]. [cold] marks renders before the host's first success, so
   * first-render latency is separated from steady state.
   */
  public fun recordOk(durationMs: Long, cold: Boolean): Unit =
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

  public fun snapshot(): RenderPerfSnapshot =
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

  public companion object {
    /** Ring size for the recent-durations percentile window. */
    public const val WINDOW_SIZE: Int = 128

    /** Cap on the carried failure-reason text — enough for a message, not a stack trace. */
    public const val MAX_FAILURE_REASON_LENGTH: Int = 300

    /** Number of render errors retained for the human status page. */
    public const val FAILURE_WINDOW_SIZE: Int = 10
  }
}

/** One recent failed render, newest-first in [RenderPerfSnapshot.recentFailures]. */
@Serializable
public data class RenderFailureSample(
  val atEpochMillis: Long,
  val durationMs: Long,
  val timedOut: Boolean,
  val reason: String,
)

/**
 * Point-in-time [RenderPerfStats] on `/status.json` (`runningServers[].renderStats` and the server
 * aggregate). Durations are serve-side round-trip milliseconds; null means no sample yet. Additive
 * on `compose-preview-serve/status/v1`.
 */
@Serializable
public data class RenderPerfSnapshot(
  /** Renders attempted against the daemon (ok + failed; excludes cache hits and busy backoffs). */
  val renders: Long,
  val ok: Long,
  val failed: Long,
  /** Subset of [failed] that blew the render budget. */
  val timedOut: Long,
  /** Bounded lock acquires that backed off to baked ([RenderOutcome.Busy]). */
  val busy: Long,
  /** Renders refused by an open [RenderCircuitBreaker]; excluded from [renders] and [failed]. */
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
  /** The most recent failure reason (truncated) and when, explaining a non-zero [failed]. */
  val lastFailureReason: String? = null,
  val lastFailureAtEpochMillis: Long? = null,
  /** Bounded, newest-first failure detail for status diagnostics. */
  val recentFailures: List<RenderFailureSample> = emptyList(),
  /** Set while this lane's [RenderCircuitBreaker] is open; null when healthy. */
  val breaker: RenderBreakerSnapshot? = null,
) {
  public companion object {
    /**
     * Server-wide roll-up: counts sum, min/max span, `avgMs` is ok-weighted, `firstRenderMs` is the
     * worst first render. Percentiles don't merge, so they stay null.
     */
    public fun aggregate(snapshots: List<RenderPerfSnapshot>): RenderPerfSnapshot? {
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
        // Fatal trips win over rate trips, then the most recent, so the roll-up names the most
        // urgent one.
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
