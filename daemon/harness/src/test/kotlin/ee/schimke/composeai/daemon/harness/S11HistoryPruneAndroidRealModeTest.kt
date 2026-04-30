package ee.schimke.composeai.daemon.harness

import org.junit.Ignore
import org.junit.Test

/**
 * Real-mode android counterpart to [S11HistoryPruneTest] — placeholder for H4 prune.
 *
 * The fake-mode S11 proves the wire shape end-to-end. The real-mode android landing waits on the
 * Robolectric sandbox + history feedback loop being driven through a renderNow burst that generates
 * entries to prune. Out of scope for the H4 PR.
 */
@Ignore("S11 real-mode android placeholder — see KDoc")
class S11HistoryPruneAndroidRealModeTest {
  @Test
  fun s11_history_prune_real_mode_android_placeholder() {
    // Placeholder body — the @Ignore annotation skips this test in JUnit.
  }
}
