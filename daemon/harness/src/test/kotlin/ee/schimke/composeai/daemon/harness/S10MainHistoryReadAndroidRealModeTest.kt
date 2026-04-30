package ee.schimke.composeai.daemon.harness

import org.junit.Ignore
import org.junit.Test

/**
 * Real-mode Android counterpart to [S10MainHistoryReadTest] — placeholder for H10-read.
 *
 * Same rationale as [S10MainHistoryReadDesktopRealModeTest]: the fake-mode S10 already covers the
 * wire shape end-to-end, the Robolectric real-mode landing waits on the gradle plugin emitting
 * `composeai.daemon.gitRefHistory` into the daemon launch descriptor.
 */
@Ignore("S10 real-mode Android placeholder — see KDoc")
class S10MainHistoryReadAndroidRealModeTest {
  @Test
  fun s10_main_history_read_real_mode_android_placeholder() {
    // Placeholder body — the @Ignore annotation skips this test in JUnit.
  }
}
