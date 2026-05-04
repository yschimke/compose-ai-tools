package ee.schimke.composeai.data.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeRuntimeTracingAvailabilityTest {
  @Test
  fun detectsComposeRuntimeTracingWhenClassIsVisible() {
    val loader = androidx.compose.runtime.tracing.ComposeRuntimeTracing::class.java.classLoader

    assertTrue(ComposeRuntimeTracingAvailability.isAvailable(loader))
  }

  @Test
  fun reportsUnavailableWhenClassLoaderCannotSeeTracingClass() {
    val loader = object : ClassLoader(null) {}

    assertFalse(ComposeRuntimeTracingAvailability.isAvailable(loader))
  }
}
