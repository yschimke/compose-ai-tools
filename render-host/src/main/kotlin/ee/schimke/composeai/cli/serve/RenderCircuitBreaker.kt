package ee.schimke.composeai.cli.serve

import kotlinx.serialization.Serializable

/**
 * Classifies a render failure as fatal (no retry of any request can clear it) or transient. A
 * daemon that can't link a symbol, resolve a class or find a method fails identically for every
 * input, so retrying only does harm ([RenderCircuitBreaker]).
 */
public object RenderFailureClassifier {
  /**
   * Failure-reason substrings meaning a linkage/classpath fault; matched as text because the daemon
   * reports failures as messages across RPC. Deliberately only [LinkageError] subtypes and
   * [ClassNotFoundException]: an NPE in one composition says nothing about the next.
   */
  private val FATAL_MARKERS =
    listOf(
      "UnsatisfiedLinkError",
      "NoClassDefFoundError",
      "NoSuchMethodError",
      "NoSuchFieldError",
      "ClassNotFoundException",
      "ExceptionInInitializerError",
      "IncompatibleClassChangeError",
      "AbstractMethodError",
      "UnsupportedClassVersionError",
      "VerifyError",
      // The base type, last: a daemon that reports a bare `LinkageError` is in the same bucket.
      "LinkageError",
    )

  /** The linkage fault named in [reason], or null when it is an ordinary (retryable) failure. */
  public fun fatalMarker(reason: String): String? = FATAL_MARKERS.firstOrNull { it in reason }

  public fun isFatal(reason: String): Boolean = fatalMarker(reason) != null
}

/**
 * Per-daemon breaker that stops a [ServeRenderHost] re-attempting renders it has proved it cannot
 * serve. Without it a linkage failure was retried thousands of times, starving optimization and
 * users while the catalog still advertised itself as healthy.
 *
 * Two independent trips:
 * - Fatal classification: the first [RenderFailureClassifier]-fatal failure opens the breaker
 *   terminally (no cooldown, no probe).
 * - Sustained failure rate: once [minSamples] outcomes are in the rolling window and at least
 *   [failureRateThreshold] failed, it opens. Catches unclassified fatal errors. Not terminal: after
 *   [probeCooldownMillis] one probe render is admitted, and a success closes it.
 *
 * While open, [blockedReason] short-circuits renders with the underlying failure text (an
 * actionable answer instead of "503 busy"), and the host stops advertising its live lane.
 * Thread-safe.
 */
public class RenderCircuitBreaker(
  /** Outcomes that must be in the window before the rate trip can fire at all. */
  private val minSamples: Int = MIN_SAMPLES,
  /** Failure fraction of the window at or above which the rate trip fires. */
  private val failureRateThreshold: Double = FAILURE_RATE_THRESHOLD,
  private val windowSize: Int = WINDOW_SIZE,
  /** How long a rate-tripped breaker stays shut before admitting one probe render. */
  private val probeCooldownMillis: Long = PROBE_COOLDOWN_MILLIS,
  private val clock: () -> Long = System::currentTimeMillis,
  /**
   * One extra sentence explaining a fatal trip's failure text, or null. The open breaker's reason
   * is the only diagnosis outsiders see; the serve path supplies
   * [SkikoNativePairing.linkageDiagnosis]. Called once under the lock on the trip; anything it
   * throws is dropped.
   */
  private val linkageDiagnosis: (String) -> String? = { null },
) {
  private val lock = Any()

  // Rolling window (true = failed), bounded so the rate reflects recent behaviour.
  private val window = ArrayDeque<Boolean>()

  private var openReason: String? = null
  private var fatal = false
  private var openedAtEpochMillis: Long? = null
  private var tripFailureRate: Double? = null
  // When the next probe may be admitted; only meaningful for a rate trip.
  private var nextProbeAtMillis: Long = 0
  private var shortCircuited = 0L

  /**
   * Why this render must not be attempted, or null to proceed. Mutating: past the cooldown a
   * rate-tripped breaker returns null once and re-arms (one probe). Use [peekReason] for read-only
   * looks so status polls can't spend the probe.
   */
  public fun blockedReason(): String? =
    synchronized(lock) {
      val reason = openReason ?: return null
      if (fatal) {
        shortCircuited++
        return reason
      }
      val now = clock()
      if (now >= nextProbeAtMillis) {
        // Half-open: admit this render and re-arm, so a failing probe waits another cooldown.
        nextProbeAtMillis = now + probeCooldownMillis
        return null
      }
      shortCircuited++
      reason
    }

  /** Read-only [blockedReason]: never admits a probe, never counts a short-circuit. */
  public fun peekReason(): String? = synchronized(lock) { openReason }

  /** A render succeeded. Closes a rate-tripped breaker; a fatal one stays open. */
  public fun recordOk(): Unit =
    synchronized(lock) {
      push(false)
      if (fatal) return
      openReason = null
      openedAtEpochMillis = null
      tripFailureRate = null
      window.clear()
    }

  /** A render failed with [reason]; trips the breaker when fatal or when the rate says so. */
  public fun recordFailure(reason: String): Unit =
    synchronized(lock) {
      push(true)
      if (fatal) return
      val marker = RenderFailureClassifier.fatalMarker(reason)
      if (marker != null) {
        fatal = true
        val diagnosis = runCatching { linkageDiagnosis(reason) }.getOrNull()
        openReason =
          "render lane disabled after a non-recoverable $marker — retrying cannot help. " +
            "Last failure: $reason" +
            diagnosis?.let { " $it" }.orEmpty()
        openedAtEpochMillis = clock()
        tripFailureRate = failureRate()
        return
      }
      if (window.size < minSamples) return
      val rate = failureRate()
      if (rate < failureRateThreshold) return
      if (openReason == null) {
        openedAtEpochMillis = clock()
        nextProbeAtMillis = clock() + probeCooldownMillis
      }
      tripFailureRate = rate
      openReason =
        "render lane disabled after ${(rate * 100).toInt()}% of the last ${window.size} renders " +
          "failed; retrying periodically. Last failure: $reason"
    }

  /** Point-in-time state for `/status.json`, or null while the breaker has never tripped. */
  public fun snapshot(): RenderBreakerSnapshot? =
    synchronized(lock) {
      val reason = openReason ?: return null
      RenderBreakerSnapshot(
        open = true,
        fatal = fatal,
        reason = reason,
        openedAtEpochMillis = openedAtEpochMillis,
        failureRate = tripFailureRate,
        sampleCount = window.size,
        shortCircuitedRenders = shortCircuited,
      )
    }

  private fun push(failed: Boolean) {
    window.addLast(failed)
    while (window.size > windowSize) window.removeFirst()
  }

  private fun failureRate(): Double =
    if (window.isEmpty()) 0.0 else window.count { it }.toDouble() / window.size

  public companion object {
    public const val MIN_SAMPLES: Int = 20
    public const val FAILURE_RATE_THRESHOLD: Double = 0.9
    public const val WINDOW_SIZE: Int = 50

    /**
     * Cooldown between probe renders on a rate-tripped breaker: one render a minute for a wedged
     * daemon, short enough that a transient wave heals within a browse.
     */
    public const val PROBE_COOLDOWN_MILLIS: Long = 60_000L
  }
}

/**
 * Open-breaker state for one daemon's render lane on `/status.json` (`renderStats.breaker`); absent
 * when healthy. Additive on `compose-preview-serve/status/v1`.
 */
@Serializable
public data class RenderBreakerSnapshot(
  val open: Boolean,
  /** A linkage/classpath fault: terminal, never probed, needs a redeploy rather than a retry. */
  val fatal: Boolean,
  /** Human-readable text also returned to callers in place of the render. */
  val reason: String,
  val openedAtEpochMillis: Long? = null,
  /** Failure fraction of the outcome window when the breaker tripped. */
  val failureRate: Double? = null,
  val sampleCount: Int = 0,
  /** Renders refused outright while open — the work this breaker is not doing. */
  val shortCircuitedRenders: Long = 0,
)
