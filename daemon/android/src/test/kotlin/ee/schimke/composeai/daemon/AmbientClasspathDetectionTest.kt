package ee.schimke.composeai.daemon

import java.net.URLClassLoader
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Guards [isWearAmbientAvailable] against the regression that hit
 * `run-agent-audit-samples.py` after PR #891: Robolectric's
 * `ShadowMap.obtainShadowInfo` reflectively resolves
 * `androidx.wear.ambient.AmbientLifecycleObserver` from
 * [ShadowAmbientLifecycleObserver]'s `@Implements` value at sandbox bootstrap, which throws
 * `TypeNotPresentException` on plain-Android consumers (`:samples:android`) whose runtime
 * classpath doesn't ship the Wear AAR. Detection has to return `false` against a classloader
 * without the class so the daemon can suppress shadow / extension registration on those
 * consumers. The wear-positive direction is covered end-to-end on `:samples:wear` integration —
 * exercising it from a unit test would need either the AAR on the test classpath (it isn't, the
 * connector keeps wear `compileOnly`) or a hand-rolled bytecode stub, neither of which adds
 * meaningful coverage over the integration path.
 */
class AmbientClasspathDetectionTest {

  @Test
  fun returnsFalseWhenWearAmbientMissingFromLoader() {
    val emptyLoader = URLClassLoader(emptyArray(), ClassLoader.getSystemClassLoader().parent)
    assertFalse(
      "AmbientLifecycleObserver must be reported absent on a classloader that does not ship it",
      isWearAmbientAvailable(emptyLoader),
    )
  }

  @Test
  fun returnsFalseWhenLoaderIsNullAndSystemClasspathLacksWear() {
    // The daemon/android module declares :data-ambient-connector as `implementation` and the
    // connector keeps `androidx.wear:wear` as `compileOnly`, so the unit-test classpath here
    // doesn't include `AmbientLifecycleObserver`. If that ever changes (a transitive bumps it
    // onto the test runtime), this test starts failing — at which point the wear AAR has crept
    // into the daemon's main runtime classpath and the gating logic needs revisiting.
    assertFalse(
      "Daemon test classpath unexpectedly ships androidx.wear.ambient",
      isWearAmbientAvailable(null),
    )
  }
}
