package ee.schimke.composeai.renderer

import ee.schimke.composeai.scroll.ScrollSeam
import ee.schimke.composeai.scroll.ScrollStep

/**
 * Per-preview record of `@ScrollingPreview(LONG)` strides that did not land where the plan put them
 * — the sibling of [VisualSettleDiagnostics], armed and drained by the same render loop.
 *
 * The LONG driver verifies every stride against the pixels the content actually moved and corrects
 * a short or long landing (see `driveScrollByViewport`). When it still cannot land — the scroller
 * kept snapping elsewhere, or the content never stopped moving — the slice it captures is at an
 * unplanned offset and the seam the stitcher cuts there is the one thing in the output that cannot
 * be trusted. Before this record the only trace was the mis-stitched PNG itself; routing the step
 * into `<png>.warnings.json` gives a consumer (and the visual-diff bot) the handle to fail on.
 *
 * A stride that landed — the overwhelming majority — records nothing.
 */
object ScrollDriveDiagnostics {

  /** One stride that missed its planned offset after every correction the driver was allowed. */
  data class UnlandedStep(
    /** What was being driven, as the render loop names it ("scroll LONG"). */
    val role: String,
    val step: ScrollStep,
  )

  /**
   * One seam the stitcher could not verify — the slices on either side never agreed on a shift
   * ([ScrollSeam.Verdict.MISMATCH]) or shared too little varied content to decide one
   * ([ScrollSeam.Verdict.LOW_SIGNAL]).
   */
  data class UnverifiedSeam(val role: String, val seam: ScrollSeam)

  private val unlanded = java.util.Collections.synchronizedList(mutableListOf<UnlandedStep>())
  private val unverified = java.util.Collections.synchronizedList(mutableListOf<UnverifiedSeam>())

  /** Reset the per-preview buffers. Called by the render loop before each preview's render. */
  fun beginPreview() {
    synchronized(unlanded) { unlanded.clear() }
    synchronized(unverified) { unverified.clear() }
  }

  /**
   * Record [seam] for the stitch in [role] when the stitcher could not verify it, and echo its
   * diagnostic to stderr. Verified seams are dropped.
   */
  fun recordSeam(role: String, seam: ScrollSeam) {
    if (seam.verified) return
    synchronized(unverified) { unverified.add(UnverifiedSeam(role = role, seam = seam)) }
    System.err.println(describe(UnverifiedSeam(role, seam)))
  }

  /** Snapshot and clear the unverified seams the just-finished preview render recorded. */
  fun drainSeams(): List<UnverifiedSeam> =
    synchronized(unverified) {
      val all = unverified.toList()
      unverified.clear()
      all
    }

  /** The human-readable line for [entry], used for stderr and the sidecar. */
  fun describe(entry: UnverifiedSeam): String =
    "${entry.role}: ${entry.seam.describe()}; the rows either side of this seam may be " +
      "duplicated or missing."

  /**
   * Record [step] for the drive in [role] when it did not land, and echo its diagnostic to stderr.
   * Landed steps are dropped — this is a warning channel, not a log of every stride.
   */
  fun record(role: String, step: ScrollStep) {
    if (step.landed) return
    synchronized(unlanded) { unlanded.add(UnlandedStep(role = role, step = step)) }
    System.err.println(describe(UnlandedStep(role, step)))
  }

  /** Snapshot and clear what the just-finished preview render recorded. */
  fun drainPreview(): List<UnlandedStep> =
    synchronized(unlanded) {
      val all = unlanded.toList()
      unlanded.clear()
      all
    }

  /** The human-readable line for [entry], used for stderr and the sidecar. */
  fun describe(entry: UnlandedStep): String =
    "${entry.role}: ${entry.step.describe()}; the slice captured there sits at an unplanned " +
      "offset and its seam may be mis-stitched."
}
