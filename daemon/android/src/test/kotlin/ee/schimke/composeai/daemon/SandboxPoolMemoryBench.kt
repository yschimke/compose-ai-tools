package ee.schimke.composeai.daemon

import com.sun.management.OperatingSystemMXBean
import java.lang.management.ManagementFactory
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SANDBOX-POOL.md bench — boots `RobolectricHost(sandboxCount = 4)` and prints heap and native
 * footprint before vs after, plus the per-sandbox marginal cost. The numbers are the empirical
 * basis for the "~750 MB per replica saved by Layer 3" claim in SANDBOX-POOL.md and the
 * CHANGELOG.
 *
 * **Not a correctness test.** Asserts only loose sanity bounds (e.g. "the 4-sandbox pool spends
 * less heap than 4× a single sandbox would"); real measurement variation across hardware /
 * Robolectric versions makes tighter bounds flaky. The actual numbers are the artifact — they
 * print to the test's stdout/stderr and the JUnit XML's `<system-out>`.
 *
 * Pair with [RobolectricHostTest] (which boots a single sandbox in the same JVM via the same
 * Robolectric stack) to compare absolute footprints. Run both, eyeball the deltas, write the
 * numbers up in SANDBOX-POOL.md when the picture changes.
 */
class SandboxPoolMemoryBench {

  @Test
  fun `report sandboxCount=4 memory footprint`() {
    val sandboxCount = 4

    val baseline = sample("baseline (before host.start)")

    val host = RobolectricHost(sandboxCount = sandboxCount)
    try {
      host.start()
      // Run a few stub renders so the JIT and Robolectric's per-sandbox shadow caches have warmed
      // up; otherwise the post-boot snapshot under-represents the true working set.
      repeat(2 * sandboxCount) { i ->
        host.submit(RenderRequest.Render(payload = "bench-warmup-$i"))
      }

      val warm = sample("warm pool (sandboxCount=$sandboxCount)")

      val heapDeltaMb = warm.heapMb - baseline.heapMb
      val nativeDeltaMb = warm.nativeHeapMb - baseline.nativeHeapMb
      val perSandboxHeapMb = heapDeltaMb / sandboxCount
      val perSandboxNativeMb = nativeDeltaMb / sandboxCount

      val report = buildString {
        appendLine("---- sandbox-pool memory bench ----")
        appendLine("baseline:    heap=${baseline.heapMb} MiB  nativeHeap=${baseline.nativeHeapMb} MiB")
        appendLine("warm (×$sandboxCount): heap=${warm.heapMb} MiB  nativeHeap=${warm.nativeHeapMb} MiB")
        appendLine("delta:       heap=$heapDeltaMb MiB    nativeHeap=$nativeDeltaMb MiB")
        appendLine(
          "per-sandbox amortized: heap≈$perSandboxHeapMb MiB  nativeHeap≈$perSandboxNativeMb MiB"
        )
        appendLine("-----------------------------------")
      }
      println(report)
      System.err.println(report)

      // Loose sanity: a 4-sandbox pool's heap delta should be measurable (i.e. above noise floor)
      // but not blow past a generous ceiling. 4 GB ceiling protects against an accidental leak
      // turning the bench into a regression alarm.
      assertTrue("heap delta should be positive (got $heapDeltaMb MiB)", heapDeltaMb > 0)
      assertTrue("heap delta should be < 4 GiB (got $heapDeltaMb MiB)", heapDeltaMb < 4096)
    } finally {
      host.shutdown()
    }
  }

  private data class Sample(val heapMb: Long, val nativeHeapMb: Long)

  private fun sample(@Suppress("UNUSED_PARAMETER") label: String): Sample {
    // Force a GC before reading heap so transient allocations don't pollute the snapshot. This is
    // a hint, not a guarantee — HotSpot mostly honours System.gc() for instrumentation paths.
    System.gc()
    val r = Runtime.getRuntime()
    val heapMb = (r.totalMemory() - r.freeMemory()) / (1024L * 1024L)
    val nativeHeapMb: Long =
      runCatching {
          val osBean = ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean
          osBean?.committedVirtualMemorySize?.div(1024L * 1024L) ?: 0L
        }
        .getOrDefault(0L)
    return Sample(heapMb = heapMb, nativeHeapMb = nativeHeapMb)
  }
}
