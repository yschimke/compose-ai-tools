package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1.3 DoD: submit 10 dummy renders to a single host instance; assert all
 * complete and the sandbox classloader is reused across all of them.
 *
 * Reuse is the load-bearing property — without it the daemon's value
 * proposition collapses (every render would re-bootstrap the sandbox,
 * which is the multi-second cost we are trying to amortise; see DESIGN.md
 * § 13). If this test ever flips and starts seeing distinct classloader
 * hash codes per render, escalate per TODO.md "Risks to track" → B1.3.
 */
class RobolectricHostTest {

  @Test
  fun tenRendersShareOneSandboxClassloader() {
    val host = RobolectricHost()
    host.start()
    try {
      val results = (1..10).map { i -> host.submit(RenderRequest.Render(payload = "render-$i")) }

      // Sanity: 10 distinct results came back.
      assertEquals(10, results.size)
      assertEquals(10, results.map { it.id }.toSet().size)

      // The load-bearing assertion: every render observed the same
      // contextClassLoader identity. Use System.identityHashCode (captured
      // into the result) so we are comparing object identity rather than
      // any classloader's overridden hashCode().
      val classLoaderHashes = results.map { it.classLoaderHashCode }.toSet()
      assertEquals(
        "expected exactly one sandbox classloader across 10 renders, saw $classLoaderHashes " +
          "(per-render names: ${results.map { it.classLoaderName }.toSet()})",
        1,
        classLoaderHashes.size,
      )

      // Also assert the sandbox classloader is *not* the host classloader —
      // proves we are running inside Robolectric's instrumenting loader,
      // not just on the test JVM's app classloader.
      val hostClHash = System.identityHashCode(RobolectricHost::class.java.classLoader)
      val sandboxClHash = classLoaderHashes.single()
      assertNotEquals(
        "sandbox classloader equals host classloader — Robolectric did not bootstrap a sandbox",
        hostClHash,
        sandboxClHash,
      )

      // The classloader name should mention Robolectric's instrumenting loader.
      val name = results.first().classLoaderName
      assertNotNull(name)
      assertTrue(
        "expected an instrumenting/sandbox classloader, got $name",
        name.contains("Instrument") ||
          name.contains("Sandbox") ||
          name.contains("Robolectric"),
      )
    } finally {
      host.shutdown()
    }
  }
}
