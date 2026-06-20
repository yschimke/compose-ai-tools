package ee.schimke.composeai.renderer

/**
 * Maps a recording's frame index to the TalkBack focus stop that should be highlighted on it —
 * issue #1956, Phase 1. The focus walk dwells [DEFAULT_DWELL_MS] on each stop (long enough to read
 * the caption), then advances to the next, holding on the final stop for the rest of the capture.
 *
 * Pure and deterministic so the silent recording path (drive [TalkBackFocusOverlay] per frame) and a
 * future spoken-audio track (schedule one utterance per stop at the same times) stay in lock-step.
 */
object TalkBackOverlayFrames {

  /** Default per-stop dwell. ~0.9s reads a short announcement comfortably at any fps. */
  const val DEFAULT_DWELL_MS: Long = 900L

  /**
   * The focus-stop index to highlight on [frameIndex] (rendered at [fps]). Clamped to
   * `0..stopCount-1`; once the walk reaches the last stop it holds there. Returns `-1` when there's
   * nothing to focus ([stopCount] <= 0).
   */
  fun focusedStopForFrame(
    frameIndex: Int,
    fps: Int,
    stopCount: Int,
    dwellMs: Long = DEFAULT_DWELL_MS,
  ): Int {
    if (stopCount <= 0) return -1
    if (fps <= 0 || dwellMs <= 0L) return 0
    val tMs = frameIndex.toLong() * 1000L / fps.toLong()
    val stop = (tMs / dwellMs).toInt()
    return stop.coerceIn(0, stopCount - 1)
  }

  /**
   * Total frames a full walk over [stopCount] stops needs at [fps] (one [dwellMs] window per stop,
   * plus a final frame so the last stop is actually rendered). `0` when there's nothing to walk.
   */
  fun totalFrames(
    fps: Int,
    stopCount: Int,
    dwellMs: Long = DEFAULT_DWELL_MS,
  ): Int {
    if (stopCount <= 0 || fps <= 0 || dwellMs <= 0L) return 0
    val durationMs = stopCount.toLong() * dwellMs
    return (durationMs * fps / 1000L).toInt() + 1
  }
}
