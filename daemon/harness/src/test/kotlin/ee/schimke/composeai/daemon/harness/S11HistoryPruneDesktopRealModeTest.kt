package ee.schimke.composeai.daemon.harness

import org.junit.Ignore
import org.junit.Test

/**
 * Real-mode desktop counterpart to [S11HistoryPruneTest] — placeholder for H4 prune.
 *
 * The fake-mode S11 proves the wire shape end-to-end (params + result + disk effect). The
 * real-mode landing waits on the production daemon being driven through a real renderNow loop
 * that produces history entries before pruning runs against them. Out of scope for the H4 PR.
 */
@Ignore("S11 real-mode desktop placeholder — see KDoc")
class S11HistoryPruneDesktopRealModeTest {
  @Test
  fun s11_history_prune_real_mode_desktop_placeholder() {
    // Placeholder body — the @Ignore annotation skips this test in JUnit.
  }
}
