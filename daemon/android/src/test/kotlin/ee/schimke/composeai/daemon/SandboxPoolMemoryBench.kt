package ee.schimke.composeai.daemon

import com.sun.management.OperatingSystemMXBean
import java.io.File
import java.lang.management.ManagementFactory
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SANDBOX-POOL.md bench — boots `RobolectricHost(sandboxCount = 4)` and prints the footprint of the
 * daemon JVM plus each of its sandbox worker processes.
 *
 * **Rewritten for the out-of-process pool (issue #3072).** The pre-#3072 bench measured this JVM's
 * heap and committed-virtual size on the premise that all N sandboxes lived here; they don't any
 * more (Robolectric's native runtime binds one classloader per process, so extra sandboxes get
 * extra JVMs). Reading only this JVM would now under-report the pool by a factor of N and quietly
 * turn into a meaningless number, so the bench reads each worker's resident set from
 * `/proc/<pid>/status` instead and reports the pool total.
 *
 * **Not a correctness test.** It asserts only that the workers actually booted and that the total
 * is in a sane range; real measurement variation across hardware / Robolectric versions makes
 * tighter bounds flaky. The numbers are the artifact — they print to stdout/stderr and land in the
 * JUnit XML's `<system-out>`.
 *
 * Pair with [RobolectricHostTest] (a single sandbox, one JVM, same Robolectric stack) to compare
 * against the one-sandbox baseline. Run both, eyeball the deltas, write the numbers up in
 * SANDBOX-POOL.md when the picture changes.
 */
class SandboxPoolMemoryBench {

  @Test
  fun `report sandboxCount=4 memory footprint`() {
    val sandboxCount = 4

    val baseline = sample()

    val host = RobolectricHost(sandboxCount = sandboxCount)
    try {
      host.start()
      // Run a few stub renders so the JIT and Robolectric's per-sandbox shadow caches have warmed
      // up; otherwise the post-boot snapshot under-represents the true working set.
      repeat(2 * sandboxCount) { i ->
        host.submit(RenderRequest.Render(payload = "bench-warmup-$i"))
      }

      val warm = sample()
      val workerPids = host.workerPidsForTest().filterNotNull()
      val workerRssMb = workerPids.associateWith(::residentSetMb)
      val poolTotalMb = warm.nativeHeapMb + workerRssMb.values.sum()

      val report = buildString {
        appendLine("---- sandbox-pool memory bench (out-of-process, #3072) ----")
        appendLine("daemon JVM (slot 0, 1 sandbox):")
        appendLine(
          "  baseline: heap=${baseline.heapMb} MiB  committedVirtual=${baseline
          .nativeHeapMb} MiB"
        )
        appendLine("  warm:     heap=${warm.heapMb} MiB  committedVirtual=${warm.nativeHeapMb} MiB")
        appendLine("workers (${workerPids.size} × 1 sandbox):")
        for ((pid, rss) in workerRssMb) appendLine("  pid=$pid rss=$rss MiB")
        appendLine("pool total (daemon committedVirtual + worker RSS): $poolTotalMb MiB")
        appendLine("-----------------------------------------------------------")
      }
      println(report)
      System.err.println(report)

      assertTrue(
        "sandboxCount=$sandboxCount should own ${sandboxCount - 1} worker processes, " +
          "saw ${workerPids.size}",
        workerPids.size == sandboxCount - 1,
      )
      // Loose sanity: every worker is a real JVM hosting a Robolectric sandbox, so its RSS is well
      // above nothing and well under a runaway. `0` also covers a platform without /proc, where
      // [residentSetMb] can't read a value — treat that as "not measurable", not as a failure.
      for ((pid, rss) in workerRssMb) {
        assertTrue("worker pid=$pid RSS looks implausible ($rss MiB)", rss == 0L || rss in 32..8192)
      }
    } finally {
      host.shutdown()
    }
  }

  private data class Sample(val heapMb: Long, val nativeHeapMb: Long)

  private fun sample(): Sample {
    // Force a GC before reading heap so transient allocations don't pollute the snapshot. This is
    // a hint, not a guarantee — HotSpot mostly honours System.gc() for instrumentation paths.
    System.gc()
    val r = Runtime.getRuntime()
    val heapMb = (r.totalMemory() - r.freeMemory()) / (1024L * 1024L)
    val nativeHeapMb: Long = runCatching {
      val osBean = ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean
      osBean?.committedVirtualMemorySize?.div(1024L * 1024L) ?: 0L
    }
      .getOrDefault(0L)
    return Sample(heapMb = heapMb, nativeHeapMb = nativeHeapMb)
  }

  /** Resident set of [pid] in MiB from `/proc`; `0` where that isn't readable (non-Linux, gone). */
  private fun residentSetMb(pid: Long): Long = runCatching {
    File("/proc/$pid/status")
      .takeIf { it.isFile }
      ?.readLines()
      ?.firstOrNull { it.startsWith("VmRSS:") }
      ?.filter(Char::isDigit)
      ?.toLongOrNull()
      ?.div(1024L) ?: 0L
  }
    .getOrDefault(0L)
}
