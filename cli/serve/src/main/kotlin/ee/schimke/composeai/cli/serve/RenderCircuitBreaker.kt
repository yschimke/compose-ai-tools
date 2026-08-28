package ee.schimke.composeai.cli.serve

import kotlinx.serialization.Serializable

/**
 * Classifies a render failure reason as **fatal** (a fault no retry of any request can clear) or
 * transient.
 *
 * A daemon that fails to link a native symbol, resolve a class, or find a method is broken at the
 * classpath/runtime level: the same call will fail identically for every preview, every override,
 * forever. Retrying is not merely useless, it is actively harmful — see [RenderCircuitBreaker].
 */
object RenderFailureClassifier {
  /**
   * Substrings of a failure reason that mean "linkage/classpath fault". Matched against the reason
   * text because that is all the serve host has: the daemon reports its render failure as a message
   * (`render failed: UnsatisfiedLinkError: 'long org.jetbrains.skia…'`), not a typed throwable
   * across the RPC boundary.
   *
   * Every entry is a [LinkageError] subtype (or [ClassNotFoundException], its checked cousin) —
   * deliberately narrow. A `NullPointerException` from one preview's composition says nothing about
   * the next preview; a `NoSuchMethodError` says the two halves of the classpath disagree, which no
   * input can route around.
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
  fun fatalMarker(reason: String): String? = FATAL_MARKERS.firstOrNull { it in reason }

  fun isFatal(reason: String): Boolean = fatalMarker(reason) != null
}

/**
 * Per-daemon breaker that stops a [ServeRenderHost] from re-attempting renders it has proved it
 * cannot serve.
 *
 * ## Why
 *
 * Issue #3448: an `m3-catalog` daemon hit an `UnsatisfiedLinkError` — a JVM linkage failure that
 * cannot succeed on retry, ever, for any input — and the host retried it **3794 times in ~14
 * minutes**, still going. Nothing backed off and nothing gave up, with three separate consequences:
 * the theme-optimization pass kept feeding a renderer that could not render (275s of gate wait, a
 * ~7h ETA on work where every item fails); user requests never reached the renderer and timed out
 * into `503 render busy; retry shortly`, a diagnosis wrong in both directions; and the catalog went
 * on advertising itself live and healthy at a 95% failure rate.
 *
 * ## What
 *
 * Two independent trips, because the first is precise and the second is the backstop:
 * - **Fatal classification.** The first failure [RenderFailureClassifier] calls fatal opens the
 *   breaker **terminally** — no cooldown, no probe. There is nothing to wait for.
 * - **Sustained failure rate.** Regardless of class, once [minSamples] outcomes are in the rolling
 *   window and at least [failureRateThreshold] of them failed, the breaker opens. This catches an
 *   unclassified fatal error (a message shape nobody anticipated) without needing to name it. A
 *   rate-tripped breaker is *not* terminal: after [probeCooldownMillis] it lets exactly one render
 *   through, and a success closes it — a genuine wave of transient failures heals itself.
 *
 * While open, [blockedReason] short-circuits renders with the underlying failure text, which is
 * what turns the useless `503 busy; retry shortly` into an actionable answer naming the linkage
 * error, and what lets the host drop its `hasLiveStream` / publish a [ServeDegradation] instead of
 * claiming health.
 *
 * Thread-safe; every method takes one short critical section.
 */
class RenderCircuitBreaker(
  /** Outcomes that must be in the window before the rate trip can fire at all. */
  private val minSamples: Int = MIN_SAMPLES,
  /** Failure fraction of the window at or above which the rate trip fires. */
  private val failureRateThreshold: Double = FAILURE_RATE_THRESHOLD,
  private val windowSize: Int = WINDOW_SIZE,
  /** How long a rate-tripped breaker stays shut before admitting one probe render. */
  private val probeCooldownMillis: Long = PROBE_COOLDOWN_MILLIS,
  private val clock: () -> Long = System::currentTimeMillis,
  /**
   * Given the failure text of a **fatal** trip, one extra sentence explaining it — or null when
   * there is nothing to add.
   *
   * The open breaker's reason is what every refused render answers with, so it is the only
   * diagnosis anyone outside the box ever sees. A bare `UnsatisfiedLinkError: 'int
   * org.jetbrains.skia…'` names the missing symbol and nothing that explains it, which is how
   * issue #4220 was reported and why its cause had to be inferred from outside; the serve path
   * supplies [SkikoNativePairing.linkageDiagnosis] here so the same body also names the Skiko skew
   * the daemon's own classpath carries. Called once, under the lock, on the trip only — never on
   * the hot path. Anything it throws is dropped: a diagnosis must not cost the trip.
   */
  private val linkageDiagnosis: (String) -> String? = { null },
) {
  private val lock = Any()

  // Rolling outcome window: true = failed. Bounded, so the rate reflects recent behaviour rather
  // than an all-time blur — a daemon that served fine for an hour before breaking must still trip.
  private val window = ArrayDeque<Boolean>()

  private var openReason: String? = null
  private var fatal = false
  private var openedAtEpochMillis: Long? = null
  private var tripFailureRate: Double? = null
  // When the next probe may be admitted; only meaningful for a rate trip.
  private var nextProbeAtMillis: Long = 0
  private var shortCircuited = 0L

  /**
   * Why this render must not be attempted, or null to proceed.
   *
   * **Mutating**: a rate-tripped breaker past its cooldown returns null here and arms the next
   * cooldown, admitting exactly one probe render. Use [peekReason] for a read-only look (status
   * reporting, the HTTP failure latch) so a status poll can't spend the probe.
   */
  fun blockedReason(): String? =
    synchronized(lock) {
      val reason = openReason ?: return null
      if (fatal) {
        shortCircuited++
        return reason
      }
      val now = clock()
      if (now >= nextProbeAtMillis) {
        // Half-open: admit this one render and re-arm, so a probe that also fails doesn't open the
        // floodgates until the next cooldown elapses.
        nextProbeAtMillis = now + probeCooldownMillis
        return null
      }
      shortCircuited++
      reason
    }

  /** Read-only [blockedReason]: never admits a probe, never counts a short-circuit. */
  fun peekReason(): String? = synchronized(lock) { openReason }

  /** A render succeeded. Closes a rate-tripped breaker; a fatal one stays open. */
  fun recordOk(): Unit =
    synchronized(lock) {
      push(false)
      if (fatal) return
      openReason = null
      openedAtEpochMillis = null
      tripFailureRate = null
      window.clear()
    }

  /** A render failed with [reason]; trips the breaker when fatal or when the rate says so. */
  fun recordFailure(reason: String): Unit =
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
  fun snapshot(): RenderBreakerSnapshot? =
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

  companion object {
    const val MIN_SAMPLES: Int = 20
    const val FAILURE_RATE_THRESHOLD: Double = 0.9
    const val WINDOW_SIZE: Int = 50

    /**
     * Cooldown between probe renders on a rate-tripped breaker. Long enough that a wedged daemon
     * costs one render a minute instead of thousands (the #3448 behaviour), short enough that a
     * transient wave — a daemon restart, a burst of cold-start timeouts — heals within a browse.
     */
    const val PROBE_COOLDOWN_MILLIS: Long = 60_000L
  }
}

/**
 * Open-breaker state for one daemon's render lane, serialized onto `/status.json` under
 * `renderStats.breaker`. Present only while the breaker is open — its absence is the healthy case.
 * Additive on `compose-preview-serve/status/v1`.
 */
@Serializable
data class RenderBreakerSnapshot(
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
