package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Waiting for a busy worker spends the caller's budget: a request queued behind a slow render is
 * refused when its own timeout runs out, rather than admitted late with a fresh timeout.
 */
class RcJvmWorkerPoolAdmissionTest {

  @TempDir lateinit var temp: Path

  @Test
  fun `admission to a busy pool counts against the render timeout`() {
    val worker =
      File(temp.toFile(), "slow-worker").apply {
        writeText(SLOW_WORKER)
        setExecutable(true)
      }
    val pool =
      RcJvmWorkerPool(
        classpath = emptyList(),
        javaBin = "",
        extraJvmArgs = emptyList(),
        maxWorkers = 1,
        maxRendersPerWorker = 100,
        maxWorkerAgeMillis = 60_000L,
        renderTimeoutSeconds = 30L,
        workerCommand = listOf(worker.absolutePath),
        lane = "test-lane",
      )
    val executor = Executors.newSingleThreadExecutor()
    try {
      val spec = RcJvmRenderSpec(widthPx = 8, heightPx = 8, density = 1f)
      // Holds the only worker for ~4s.
      val first =
        executor.submit<RcJvmWorkerPool.PoolResult> {
          pool.render(ByteArray(1), spec, "", RcJvmServerRenderer.Format.PNG, timeoutSeconds = 30L)
        }
      Thread.sleep(500)
      val started = System.nanoTime()
      val second =
        pool.render(ByteArray(1), spec, "", RcJvmServerRenderer.Format.PNG, timeoutSeconds = 1L)
      val waitedMillis = (System.nanoTime() - started) / 1_000_000L
      assertIs<RcJvmWorkerPool.PoolResult.Unusable>(second)
      assertTrue(second.reason.contains("test-lane worker pool stayed busy"), second.reason)
      assertTrue(waitedMillis < 2_500L, "refused within its own budget, waited ${waitedMillis}ms")
      assertIs<RcJvmWorkerPool.PoolResult.Ok>(first.get(30, TimeUnit.SECONDS))
    } finally {
      executor.shutdownNow()
      pool.close()
    }
  }

  private companion object {
    /** The worker frame protocol, answering every request after a 4s pause. */
    val SLOW_WORKER =
      """
      #!/usr/bin/env python3
      import struct, sys, time
      out, inp = sys.stdout.buffer, sys.stdin.buffer
      out.write(struct.pack(">ii", 0x52435731, 2)); out.flush()
      while True:
          head = inp.read(4)
          if len(head) < 4: sys.exit(0)
          rid, _w, _h, _d, _f, _t, seeds_len = struct.unpack(">iiiiiii", inp.read(28))
          inp.read(seeds_len)
          (doc_len,) = struct.unpack(">i", inp.read(4))
          inp.read(doc_len)
          time.sleep(4)
          out.write(struct.pack(">iiii", 0x52435231, rid, 0, 2) + b"ok"); out.flush()
      """
        .trimIndent()
  }
}
