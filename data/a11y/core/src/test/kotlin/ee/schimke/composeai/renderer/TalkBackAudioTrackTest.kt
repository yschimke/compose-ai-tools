package ee.schimke.composeai.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the spoken-cue scheduling that backs the optional TTS track (issue #1956 Phase 4).
 */
class TalkBackAudioTrackTest {

  private val nodes =
    listOf(
      AccessibilityNode(
        label = "Settings",
        role = "Heading",
        merged = true,
        boundsInScreen = "0,0,10,10",
      ),
      AccessibilityNode(
        label = "Buy now",
        role = "Button",
        states = listOf("clickable"),
        merged = true,
        boundsInScreen = "0,20,10,30",
      ),
      // Unmerged child — not a focus stop, never spoken.
      AccessibilityNode(label = "inner", merged = false, boundsInScreen = "0,21,5,29"),
    )

  @Test
  fun `one cue per focus stop in traversal order, spoken text matches the utterance`() {
    val cues = TalkBackAudioTrack.plan(nodes, dwellMs = 900L)
    assertEquals(2, cues.size)
    assertEquals(TalkBackSpokenCue(0, 0L, "Settings, heading"), cues[0])
    assertEquals(TalkBackSpokenCue(1, 900L, "Buy now, button, double-tap to activate"), cues[1])
  }

  @Test
  fun `cue start times lock-step with the overlay dwell`() {
    val dwell = 900L
    val cues = TalkBackAudioTrack.plan(nodes, dwellMs = dwell)
    // Each cue starts exactly when the silent overlay first focuses its stop.
    cues.forEach { cue ->
      val fps = 30
      val firstFrameOnStop = ((cue.startMs * fps) / 1000L).toInt()
      assertEquals(
        "cue ${cue.stopIndex} must start as the overlay focuses that stop",
        cue.stopIndex,
        TalkBackOverlayFrames.focusedStopForFrame(
          firstFrameOnStop,
          fps,
          stopCount = 2,
          dwellMs = dwell,
        ),
      )
    }
  }

  @Test
  fun `total duration is one dwell window per stop`() {
    assertEquals(1800L, TalkBackAudioTrack.totalDurationMs(nodes, dwellMs = 900L))
  }

  @Test
  fun `no focus stops yields no cues`() {
    val onlyChild =
      listOf(AccessibilityNode(label = "x", merged = false, boundsInScreen = "0,0,1,1"))
    assertTrue(TalkBackAudioTrack.plan(onlyChild).isEmpty())
    assertEquals(0L, TalkBackAudioTrack.totalDurationMs(onlyChild))
  }
}
