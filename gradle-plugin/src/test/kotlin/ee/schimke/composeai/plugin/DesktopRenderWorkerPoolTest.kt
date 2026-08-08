package ee.schimke.composeai.plugin

import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Covers [DesktopRenderWorkerPool] against [DesktopRenderWorkerPoolStub] — a worker speaking the
 * real frames without Compose or Skiko, so the protocol, warm reuse and every failure path are
 * asserted on machines with no native render stack.
 *
 * The distinction these are built around is the pool's whole failure posture: `Failed` means the
 * *renderer* answered "I cannot draw this capture" and the caller must not fork a retry (that would
 * double the cost of every broken preview), while `Unusable` means the *pool* could not serve and
 * forking that capture is correct.
 */
class DesktopRenderWorkerPoolTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun aWarmWorkerServesSuccessiveCapturesAndCarriesTheRequest() {
    pool().use { pool ->
      val first = tempFolder.newFile("first.txt")
      val second = tempFolder.newFile("second.txt")

      assertOk(pool.render(argsFor(first), null))
      assertOk(pool.render(argsFor(second), null))

      // `#2` is the point of the pool: the second capture was drawn by the same process, so it
      // paid no JVM boot. A fresh worker would report `#1` again.
      assertEquals("2:#1", first.readText())
      assertEquals("2:#2", second.readText())
      assertEquals(2, pool.servedWarm.get())
    }
  }

  @Test
  fun theOverrideSeedRidesTheRequestRatherThanTheWorkerEnvironment() {
    pool().use { pool ->
      val seeded = tempFolder.newFile("seeded.txt")
      val plain = tempFolder.newFile("plain.txt")
      pool.render(argsFor(seeded), """{"name":"v"}""")
      pool.render(argsFor(plain), null)

      // Same worker, different seeds — the per-capture seed must not stick to the process, or a
      // preview would render with the variant knobs of whatever ran before it.
      assertTrue(seeded.readText(), seeded.readText().contains("""{"name":"v"}"""))
      assertEquals("2:#2", plain.readText())
    }
  }

  @Test
  fun aCaptureTheRendererRejectsIsFailedNotUnusable() {
    pool(mode = "failed").use { pool ->
      val result = pool.render(argsFor(tempFolder.newFile("x.txt")), null)
      assertTrue(
        "expected Failed, got $result",
        result is DesktopRenderWorkerPool.WorkerResult.Failed,
      )
      val failed = result as DesktopRenderWorkerPool.WorkerResult.Failed
      assertTrue(failed.reason, failed.reason.contains("stub refused"))
    }
  }

  @Test
  fun aWorkerThatDiesMidCaptureIsUnusableSoTheCallerForks() {
    pool(mode = "crash").use { pool ->
      assertUnusable(pool.render(argsFor(tempFolder.newFile("x.txt")), null))
    }
  }

  @Test
  fun aRendererSpeakingAnotherProtocolVersionIsRefused() {
    pool(mode = "badVersion").use { pool ->
      val result = pool.render(argsFor(tempFolder.newFile("x.txt")), null)
      val unusable = assertUnusable(result)
      assertTrue(unusable.reason, unusable.reason.contains("protocol"))
    }
  }

  @Test
  fun aWorkerIsRetiredAfterItsCaptureBudget() {
    pool(maxRendersPerWorker = 1).use { pool ->
      val first = tempFolder.newFile("a.txt")
      val second = tempFolder.newFile("b.txt")
      pool.render(argsFor(first), null)
      pool.render(argsFor(second), null)
      // Both `#1`: the budget of one retired the first worker, bounding any native leak.
      assertEquals("2:#1", first.readText())
      assertEquals("2:#1", second.readText())
    }
  }

  @Test
  fun closingThePoolReleasesAWorkerThatIsMidCapture() {
    // A checked-out worker is absent from the idle set, so a shutdown that drains only `idle`
    // leaves its JVM alive and its caller blocked on a pipe read forever — and stopping the
    // watchdog removes the scheduled kill that was the only other way out.
    val pool = pool(mode = "hang")
    val outcome = ArrayBlockingQueue<DesktopRenderWorkerPool.WorkerResult>(1)
    val thread = Thread { outcome.add(pool.render(argsFor(tempFolder.newFile("x.txt")), null)) }
    thread.start()
    Thread.sleep(2_000)

    pool.close()

    val result =
      outcome.poll(60, TimeUnit.SECONDS)
        ?: error("closing the pool left a mid-capture caller blocked — it never returned")
    assertUnusable(result)
    thread.join(10_000)
    assertTrue("render thread did not finish after shutdown", !thread.isAlive)
  }

  @Test
  fun repeatedStartFailuresDisableThePoolSoTheCostIsPaidOnce() {
    pool(workerMainClass = "ee.schimke.composeai.plugin.NoSuchWorkerMain").use { pool ->
      val reasons =
        (1..DesktopRenderWorkerPool.MAX_START_FAILURES + 1).map {
          assertUnusable(pool.render(argsFor(tempFolder.newFile("x$it.txt")), null)).reason
        }
      // Every attempt is unusable, so every capture still gets rendered by a fork; once the pool
      // has given up it says so rather than spawning a doomed JVM per capture.
      assertTrue(reasons.last(), reasons.last().contains("disabled after"))
    }
  }

  private fun assertOk(result: DesktopRenderWorkerPool.WorkerResult) {
    assertTrue("expected Ok, got $result", result is DesktopRenderWorkerPool.WorkerResult.Ok)
  }

  private fun assertUnusable(
    result: DesktopRenderWorkerPool.WorkerResult
  ): DesktopRenderWorkerPool.WorkerResult.Unusable {
    assertTrue(
      "expected Unusable, got $result",
      result is DesktopRenderWorkerPool.WorkerResult.Unusable,
    )
    return result as DesktopRenderWorkerPool.WorkerResult.Unusable
  }

  private fun argsFor(target: File) = listOf("ignored.ClassKt", target.absolutePath)

  private fun pool(
    mode: String = "ok",
    maxRendersPerWorker: Int = 100,
    workerMainClass: String = STUB_MAIN_CLASS,
  ) =
    DesktopRenderWorkerPool(
      classpath = System.getProperty("java.class.path").split(File.pathSeparator).map(::File),
      javaExecutable =
        File(System.getProperty("java.home"), "bin/java").let {
          if (it.canExecute()) it.absolutePath else "java"
        },
      jvmArgs = listOf("-Dstub.mode=$mode"),
      maxWorkers = 1,
      maxRendersPerWorker = maxRendersPerWorker,
      renderTimeoutSeconds = 60,
      workerMainClass = workerMainClass,
    )

  private companion object {
    const val STUB_MAIN_CLASS = "ee.schimke.composeai.plugin.DesktopRenderWorkerPoolStub"
  }
}
