package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DwfApkDaemonBridgeRuntimeCacheTest {
  @Test
  fun `a runtime converted before the clock rewrite is not reused`() {
    // v3 jars were converted without ZonedDateTime.now() -> SimulatorClock; reusing one keeps a
    // pinned time out of the live draw.
    assertNotEquals("runtime-v3-abc.jar", DwfApkDaemonBridge.runtimeClassesFileName("abc"))
    assertEquals("runtime-v4-abc.jar", DwfApkDaemonBridge.runtimeClassesFileName("abc"))
  }
}
