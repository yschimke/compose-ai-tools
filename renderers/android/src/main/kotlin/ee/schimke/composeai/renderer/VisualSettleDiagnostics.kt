package ee.schimke.composeai.renderer

/**
 * Per-preview record of still captures whose quiescence probe did not end in a settled frame.
 *
 * The sibling of [FontResolutionDiagnostics] and [CoilLoadDiagnostics], and armed/drained by the
 * same render loop. Before issue #4239 the only trace of an unsettled capture was a line on stderr,
 * so a catalog shipped a half-drawn sticker on a green build and nothing downstream could see it —
 * `CatalogRenderTest` already fails on blank captures but had nothing to fail on here. Routing the
 * outcome into `<png>.warnings.json` gives a consumer the same handle.
 *
 * A settled capture — and a genuinely static preview, which is the overwhelming majority — records
 * nothing, so the sidecar stays absent for a clean render.
 */
object VisualSettleDiagnostics {

  /** One still capture that finished its sample budget without settling. */
  data class UnsettledCapture(
    /**
     * What was being captured, as the render loop names it ("preview still", "scroll LONG slice").
     */
    val role: String,
    /** Why the probe stopped. Never a quiescent outcome — those are not recorded. */
    val outcome: VisualSettleOutcome,
    /** The sample budget the probe spent. */
    val samples: Int = VISUAL_SETTLE_MAX_SAMPLES,
  )

  private val unsettled = java.util.Collections.synchronizedList(mutableListOf<UnsettledCapture>())

  /** Reset the per-preview buffer. Called by the render loop before each preview's render. */
  fun beginPreview() {
    synchronized(unsettled) { unsettled.clear() }
  }

  /**
   * Record [outcome] for a capture in [role], and echo its diagnostic to stderr. Quiescent outcomes
   * are dropped — this is a warning channel, not a log of every capture.
   *
   * Returns [outcome] so a call site can stay a single expression.
   */
  fun record(role: String, outcome: VisualSettleOutcome): VisualSettleOutcome {
    val line = outcome.describe(role) ?: return outcome
    synchronized(unsettled) { unsettled.add(UnsettledCapture(role = role, outcome = outcome)) }
    System.err.println(line)
    return outcome
  }

  /** Snapshot and clear what the just-finished preview render recorded. */
  fun drainPreview(): List<UnsettledCapture> =
    synchronized(unsettled) {
      val all = unsettled.toList()
      unsettled.clear()
      all
    }

  /** The human-readable line for [capture], used for stderr and the sidecar. */
  fun describe(capture: UnsettledCapture): String =
    capture.outcome.describe(capture.role) ?: "${capture.role}: settled."
}
