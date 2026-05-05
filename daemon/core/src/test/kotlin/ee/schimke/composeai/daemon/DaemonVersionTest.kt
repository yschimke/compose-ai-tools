package ee.schimke.composeai.daemon

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the contract that [DaemonVersion.value] resolves to whatever version `daemon/core`'s build
 * baked into `daemon-version.properties`, not the `0.0.0-dev` literal that was the only thing
 * showing up in `[daemon] ready ...` lines on released installs because [DaemonMain] never threaded
 * the version through to [JsonRpcServer].
 */
class DaemonVersionTest {
  @Test
  fun bakedValueIsNotTheFallback() {
    val v = DaemonVersion.value
    assertNotEquals(
      "DaemonVersion.value resolved to the fallback — `daemon-version.properties` missing or empty",
      "0.0.0-dev",
      v,
    )
    assertTrue("expected non-blank version, got '$v'", v.isNotBlank())
  }
}
