package ee.schimke.composeai.daemon.harness

import org.junit.Ignore
import org.junit.Test

/**
 * B2.3 desktop real-mode soak placeholder. Disabled — pulling this in is a follow-up; the B2.3
 * task brief says "Do NOT run real-mode tests in verification."
 *
 * Intent when enabled:
 * - Spawn a real `DesktopHost` daemon via the `realModeScenario` helper.
 * - Run 50 sequential renders against a small fixture composable.
 * - Capture two `tookMs` series:
 *   1. With measurement enabled (the production path).
 *   2. With measurement disabled (a hypothetical `composeai.daemon.skipMeasurement=true` toggle
 *      on the engine — to be added when this test is enabled).
 * - Assert `mean(withMeasurement) - mean(withoutMeasurement) < 10ms` per render. This is the
 *   B2.3 DoD's "measurement adds < 10ms to render time" threshold, averaged over 50 renders to
 *   absorb single-render jitter.
 *
 * The "skip measurement" toggle does not exist yet — adding it is part of enabling this test.
 * Until then, the unit + fake-mode harness covers B2.3's wire-format contract end-to-end.
 */
@Ignore("B2.3 real-mode soak — enable with the measurement-disable toggle (B2.4 follow-up).")
class B23SoakDesktopRealModeTest {

  @Test
  fun b23_soak_real_mode_desktop_measurement_overhead_under_10ms() {
    // Placeholder. See class KDoc.
  }
}
