package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-desktop.1.3 DoD: submit 10 dummy renders to a single host instance; assert all complete and the
 * render-thread classloader is reused across all of them.
 *
 * Reuse is the load-bearing property — without it the daemon's value proposition collapses (every
 * render would re-bootstrap the Compose runtime, which is the cost we are trying to amortise; see
 * DESIGN.md § 13). On desktop the classloader is just the JVM's app classloader (no Robolectric
 * `InstrumentingClassLoader` to verify against), but the assertion still proves invariance — if
 * `DesktopHost` were spawning a new render thread per submission, or otherwise recycling the
 * runtime per request, this test would catch it.
 */
class DesktopHostTest {

  @Test
  fun tenRendersShareOneRenderThreadClassloader() {
    val host = DesktopHost()
    host.start()
    try {
      val results = (1..10).map { i -> host.submit(RenderRequest.Render(payload = "render-$i")) }

      // Sanity: 10 distinct results came back.
      assertEquals(10, results.size)
      assertEquals(10, results.map { it.id }.toSet().size)

      // The load-bearing assertion: every render observed the same classloader identity. Use
      // System.identityHashCode (captured into the result) so we are comparing object identity
      // rather than any classloader's overridden hashCode().
      val classLoaderHashes = results.map { it.classLoaderHashCode }.toSet()
      assertEquals(
        "expected exactly one classloader across 10 renders, saw $classLoaderHashes " +
          "(per-render names: ${results.map { it.classLoaderName }.toSet()})",
        1,
        classLoaderHashes.size,
      )

      // Sanity-check the classloader name is non-null and resembles a JVM app classloader. We
      // intentionally do NOT assert it differs from the host classloader (as the Android test
      // does) — on desktop they are the same, by design.
      val name = results.first().classLoaderName
      assertNotNull(name)
    } finally {
      host.shutdown()
    }

    // Post-shutdown: the render thread must not have observed an InterruptedException. The
    // no-mid-render-cancellation invariant (DESIGN § 9) requires the daemon never interrupt the
    // render thread; this assertion catches a future regression that introduces a stray
    // `interrupt()` call.
    assertFalse(
      "render thread observed an InterruptedException — DESIGN § 9 invariant violated",
      host.renderThreadInterrupted,
    )
  }

  /**
   * Capability-bit contract — `supportsInteractive` reflects whether a `previewSpecResolver` was
   * wired at construction. Clients consume this verbatim as
   * `InitializeResult.capabilities.interactive`; advertising `true` while
   * `acquireInteractiveSession` would throw is a contract violation that misleads the panel into
   * showing a v2 affordance the daemon can't honour.
   */
  @Test
  fun supportsInteractiveReflectsResolverPresence() {
    val withoutResolver = DesktopHost()
    assertFalse(
      "DesktopHost without a previewSpecResolver must advertise interactive=false",
      withoutResolver.supportsInteractive,
    )

    val withResolver = DesktopHost(previewSpecResolver = { null })
    assertTrue(
      "DesktopHost with a previewSpecResolver must advertise interactive=true",
      withResolver.supportsInteractive,
    )
  }
}
