package ee.schimke.composeai.daemon.harness

import org.junit.Ignore
import org.junit.Test

/**
 * Real-mode desktop counterpart to [S10MainHistoryReadTest] — placeholder for H10-read.
 *
 * The fake-mode S10 already proves the wire shape end-to-end. The real-mode landing waits on the
 * gradle plugin emitting the `composeai.daemon.gitRefHistory` sysprop into the daemon launch
 * descriptor (a follow-up task that's NOT in scope for this PR). Keeping the placeholder here pins
 * the intent and makes the future landing point obvious to anyone scanning the harness suite.
 */
@Ignore("S10 real-mode desktop placeholder — see KDoc")
class S10MainHistoryReadDesktopRealModeTest {
  @Test
  fun s10_main_history_read_real_mode_desktop_placeholder() {
    // Placeholder body — the @Ignore annotation skips this test in JUnit. Real implementation
    // lands when the gradle plugin emits `composeai.daemon.gitRefHistory` in the daemon descriptor.
  }
}
