package ee.schimke.composeai.daemon

import java.net.URLClassLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Guards [isRemoteComposeAvailable] against the same regression class
 * [AmbientClasspathDetectionTest] catches for Wear ambient: the `:data-remotecompose-connector`
 * keeps `androidx.compose.remote:*` as `compileOnly`, so the daemon's main runtime classpath
 * doesn't ship the alpha AAR. The host-side classloader probe has to return `false` for that case
 * so DaemonMain skips registering `data/remotecompose` on plain-Android consumers
 * (`:samples:android`) — the Robolectric sandbox classloader for a Remote-Compose-shipping consumer
 * (`:samples:remotecompose`) gets a separate `true` answer when `RobolectricHost`'s lazy engine
 * init asks the question via the sandbox loader. The remote-compose-positive direction is covered
 * end-to-end on `:samples:remotecompose` — exercising it from a unit test would need the alpha AAR
 * on the test classpath (it isn't) or a hand-rolled bytecode stub, neither of which adds meaningful
 * coverage over the integration path.
 */
class RemoteComposeClasspathDetectionTest {

  @Test
  fun returnsFalseWhenRemoteComposeMissingFromLoader() {
    val emptyLoader = URLClassLoader(emptyArray(), ClassLoader.getSystemClassLoader().parent)
    assertFalse(
      "HostAction must be reported absent on a classloader that does not ship it",
      isRemoteComposeAvailable(emptyLoader),
    )
  }

  @Test
  fun nullLoaderUsesSystemClasspath() {
    // The daemon's test runtime intentionally includes Remote Compose player artifacts, while
    // published consumers only receive them when their own runtime requests them. Keep this test
    // focused on the null-loader contract instead of assuming which optional artifacts happen to
    // be present on the evolving test classpath.
    assertEquals(
      "A null loader must delegate Remote Compose detection to the system classloader",
      isRemoteComposeAvailable(ClassLoader.getSystemClassLoader()),
      isRemoteComposeAvailable(null),
    )
  }
}
