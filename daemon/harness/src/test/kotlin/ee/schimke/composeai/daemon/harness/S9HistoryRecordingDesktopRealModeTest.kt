package ee.schimke.composeai.daemon.harness

import org.junit.Ignore
import org.junit.Test

/**
 * Real-mode desktop counterpart to [S9HistoryRecordingTest] — placeholder for H1+H2.
 *
 * The fake-mode S9 already covers the wire-format end-to-end. The real-mode landing belongs
 * alongside the rest of the desktop real-mode catalogue once the gradle plugin's daemon launch
 * descriptor wires the `composeai.daemon.historyDir` sysprop (a follow-up task that's NOT in scope
 * for this PR). Keeping the placeholder here pins the intent and makes the future landing point
 * obvious to anyone scanning the harness suite.
 */
@Ignore("S9 real-mode desktop placeholder — see KDoc")
class S9HistoryRecordingDesktopRealModeTest {
  @Test
  fun s9_history_recording_real_mode_desktop_placeholder() {
    // Placeholder body — the @Ignore annotation skips this test in JUnit. Real implementation
    // lands when the gradle plugin emits `composeai.daemon.historyDir` in the daemon descriptor.
  }
}
