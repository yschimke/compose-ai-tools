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

  /**
   * One still captured at an author-declared exact coordinate — `@SettledPreview(afterMs = …)`.
   *
   * The positive claim, and the reason it needs a record of its own (issue #4829). An animation
   * that never ends — an `InfiniteTransition`, an indeterminate progress indicator — cannot
   * quiesce, so under auto settle it walks the whole budget and reports
   * [VisualSettleOutcome .STILL_CHANGING] forever. That outcome then means two unrelated things:
   * "your reveal is broken" and "this is a spinner, working exactly as designed". A consumer cannot
   * act on either, because silencing the second silences the first.
   *
   * An exact `afterMs` already resolves the *capture*: it is a deterministic coordinate, so
   * `shouldAdvanceClockForVisualSettling` skips the quiescence probe entirely and the still is
   * taken at that instant on both backends. What was missing was the *declaration* reaching a
   * consumer — nothing was recorded, which is indistinguishable from an ordinary static preview.
   * Recording it makes "deterministic phase of a continuous animation" a thing a catalog can
   * publish and assert on, as distinct from "did not settle", which is a bug report.
   */
  data class PinnedCapture(
    /** What was captured, as the render loop names it. */
    val role: String,
    /** The virtual-time coordinate the author pinned, in milliseconds. */
    val atMs: Long,
  )

  private val unsettled = java.util.Collections.synchronizedList(mutableListOf<UnsettledCapture>())
  private val pinned = java.util.Collections.synchronizedList(mutableListOf<PinnedCapture>())

  /** Reset the per-preview buffers. Called by the render loop before each preview's render. */
  fun beginPreview() {
    synchronized(unsettled) { unsettled.clear() }
    synchronized(pinned) { pinned.clear() }
  }

  /**
   * Record that the still for [role] was captured at the exact coordinate [atMs].
   *
   * Not a warning, so nothing is echoed to stderr: this is the author's declaration being honoured,
   * and a spinner that says where its phase is has done the right thing.
   */
  fun recordPinnedPhase(role: String, atMs: Long) {
    synchronized(pinned) { pinned.add(PinnedCapture(role = role, atMs = atMs)) }
  }

  /** Snapshot and clear the phase pins the just-finished preview render recorded. */
  fun drainPinned(): List<PinnedCapture> =
    synchronized(pinned) {
      val all = pinned.toList()
      pinned.clear()
      all
    }

  /** The human-readable line for [capture]. */
  fun describe(capture: PinnedCapture): String =
    "${capture.role}: captured at the declared ${capture.atMs}ms phase; " +
      "a chosen coordinate, not a failed settle."

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
