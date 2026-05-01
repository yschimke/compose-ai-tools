package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SANDBOX-POOL.md Layer 2 — boots [RobolectricHost] with `sandboxCount = 2`, submits a spread of
 * stub renders, and asserts that:
 *
 * 1. Both slots accept renders (each handles at least one).
 * 2. The two slots have **distinct** sandbox classloaders — proving Robolectric's sandbox cache
 *    didn't collapse the pool to a single shared sandbox via the
 *    [SandboxHoldingRunner]/[SandboxHoldingHints] discriminator + constructor-snapshot path.
 * 3. Renders dispatched to the same slot consistently see that slot's classloader (i.e. the slot
 *    dispatch in [RobolectricHost.submit] is stable — `Math.floorMod(id, sandboxCount)` is keyed
 *    on the request id which is monotonic per process).
 *
 * **Why ids are bucketed by `id and 1`**: [RobolectricHost.submit] dispatches via
 * `Math.floorMod(id, sandboxCount.toLong())`. With sandboxCount=2 that's `id mod 2 = id and 1`.
 * Result.id matches the request id, so we bucket the observed results that way.
 *
 * **Why `legacyStubPayload` and not real RenderSpecs**: [RobolectricHostTest] already submits the
 * `payload="render-N"` shape that the B1.3-era stub path was built for. Reusing that path keeps
 * the assertion focused on slot dispatch and sandbox identity rather than the heavier render-
 * engine work (Roborazzi capture, bitmap save, etc.).
 */
class RobolectricHostPoolTest {

  @Test
  fun twoSandboxesServeDistinctClassloaders() {
    val host = RobolectricHost(sandboxCount = 2)
    try {
      host.start()
      val results = (1..20).map { i -> host.submit(RenderRequest.Render(payload = "render-$i")) }
      assertEquals(20, results.size)

      val byBucket = results.groupBy { (it.id and 1L).toInt() }
      assertEquals(
        "expected dispatch to land renders in both buckets (sandboxCount=2)",
        setOf(0, 1),
        byBucket.keys,
      )

      // Both buckets must consistently see *one* sandbox classloader each — slot dispatch is
      // stable (id-keyed, `Math.floorMod`).
      val bucket0Hashes = byBucket.getValue(0).map { it.classLoaderHashCode }.toSet()
      val bucket1Hashes = byBucket.getValue(1).map { it.classLoaderHashCode }.toSet()
      assertEquals(
        "bucket 0 should see exactly one classloader, saw $bucket0Hashes",
        1,
        bucket0Hashes.size,
      )
      assertEquals(
        "bucket 1 should see exactly one classloader, saw $bucket1Hashes",
        1,
        bucket1Hashes.size,
      )

      // The load-bearing assertion: the two buckets see *different* classloaders. If the
      // discriminator failed to break Robolectric's sandbox cache, both buckets would land on the
      // same cached sandbox and these would match — proving the pool collapsed to a single
      // sandbox.
      assertNotEquals(
        "expected distinct sandbox classloaders across slots — see SANDBOX-POOL.md if this " +
          "regresses (discriminator may be back to colliding on the cache key)",
        bucket0Hashes.single(),
        bucket1Hashes.single(),
      )

      // Sanity probe: classloader names look like Robolectric's instrumenting loader on both
      // sides, so we know we're inside two real sandboxes (not on the test JVM's app classloader).
      val sampleNames =
        setOf(
          byBucket.getValue(0).first().classLoaderName,
          byBucket.getValue(1).first().classLoaderName,
        )
      for (cl in sampleNames) {
        assertTrue(
          "expected an instrumenting/sandbox classloader, got '$cl'",
          cl.contains("Instrument") || cl.contains("Sandbox") || cl.contains("Robolectric"),
        )
      }
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun rejectsHolderWithSandboxCountAboveOne() {
    val holder =
      UserClassLoaderHolder(
        urls = emptyList(),
        parentSupplier = { ClassLoader.getSystemClassLoader() },
      )
    val ex =
      assertThrows(IllegalArgumentException::class.java) {
        RobolectricHost(userClassloaderHolder = holder, sandboxCount = 2)
      }
    assertTrue(
      "error should explain why holder + sandboxCount>1 is forbidden, got: ${ex.message}",
      ex.message?.contains("per-slot") == true,
    )
  }

  private fun <T : Throwable> assertThrows(expected: Class<T>, block: () -> Unit): T {
    try {
      block()
    } catch (t: Throwable) {
      if (expected.isInstance(t)) {
        @Suppress("UNCHECKED_CAST")
        return t as T
      }
      throw AssertionError(
        "expected ${expected.name}, got ${t.javaClass.name}: ${t.message}",
        t,
      )
    }
    throw AssertionError("expected ${expected.name} to be thrown")
  }
}
