package ee.schimke.composeai.renderer

/**
 * One spoken cue in a TalkBack walk: the [text] TalkBack would speak on focus stop [stopIndex],
 * scheduled to begin at [startMs] from the start of the recording.
 */
data class TalkBackSpokenCue(val stopIndex: Int, val startMs: Long, val text: String)

/**
 * Plans the timed spoken-announcement track for a TalkBack walk — issue #1956, Phase 4 (the
 * "read out the text" exploration). It assigns each focus stop the utterance
 * [TalkBackUtterance.compose] produces and the time the focus overlay lands on that stop, so a
 * downstream step can synthesize one TTS clip per cue, lay them out on a silent timeline at
 * [TalkBackSpokenCue.startMs], and mux the result into the MP4/WEBM via
 * [ee.schimke.composeai.daemon.FfmpegEncoder]'s optional audio track.
 *
 * Crucially it dwells on the **same** [TalkBackOverlayFrames.DEFAULT_DWELL_MS] the silent overlay
 * uses, so the spoken word and the green focus rectangle land on each control at the same instant —
 * the caption you read and the audio you'd hear stay in lock-step by construction.
 *
 * Pure and dependency-free: it produces the *plan*. Turning a cue's [text] into PCM is a separate,
 * environment-dependent concern (an on-device or CLI TTS engine), kept out of this module so the
 * scheduling stays unit-testable and engine-agnostic.
 */
object TalkBackAudioTrack {

  /**
   * One cue per focus stop, in traversal order, each starting when the walk reaches it. Empty when
   * there are no focus stops. Cues with an empty utterance are dropped — there's nothing to speak.
   */
  fun plan(
    nodes: List<AccessibilityNode>,
    dwellMs: Long = TalkBackOverlayFrames.DEFAULT_DWELL_MS,
  ): List<TalkBackSpokenCue> {
    val step = if (dwellMs > 0L) dwellMs else TalkBackOverlayFrames.DEFAULT_DWELL_MS
    return TalkBackTraversal.focusStops(nodes).mapIndexedNotNull { i, node ->
      val text = TalkBackUtterance.compose(node)
      if (text.isEmpty()) null else TalkBackSpokenCue(stopIndex = i, startMs = i * step, text = text)
    }
  }

  /** Total walk duration (one dwell window per focus stop). `0` when nothing is focusable. */
  fun totalDurationMs(
    nodes: List<AccessibilityNode>,
    dwellMs: Long = TalkBackOverlayFrames.DEFAULT_DWELL_MS,
  ): Long {
    val step = if (dwellMs > 0L) dwellMs else TalkBackOverlayFrames.DEFAULT_DWELL_MS
    return TalkBackTraversal.focusStops(nodes).size.toLong() * step
  }
}
