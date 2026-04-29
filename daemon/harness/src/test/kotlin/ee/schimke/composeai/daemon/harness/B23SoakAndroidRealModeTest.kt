package ee.schimke.composeai.daemon.harness

import org.junit.Ignore
import org.junit.Test

/**
 * B2.3 Android real-mode soak placeholder. Disabled — same rationale as
 * [B23SoakDesktopRealModeTest]. The B2.3 brief excludes real-mode soak from this task's
 * verification step.
 *
 * Intent when enabled: 50 sequential `RobolectricHost` renders, comparing tookMs distributions
 * with/without measurement to verify the < 10ms-per-render overhead threshold from the B2.3 DoD.
 * Robolectric sandbox cold-boot dominates the first render (~5–15s); the test must take that out
 * of the average.
 */
@Ignore("B2.3 real-mode soak — enable with the measurement-disable toggle (B2.4 follow-up).")
class B23SoakAndroidRealModeTest {

  @Test
  fun b23_soak_real_mode_android_measurement_overhead_under_10ms() {
    // Placeholder. See class KDoc.
  }
}
