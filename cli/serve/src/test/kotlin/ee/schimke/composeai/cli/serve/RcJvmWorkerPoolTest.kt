/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ee.schimke.composeai.cli.serve

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Covers [RcJvmWorkerPool] against [RcJvmWorkerPoolStub] — a worker that speaks the real frames
 * without Compose or Skiko, so the protocol, the warm-reuse property and every failure path are
 * asserted on any machine, including CI images with no native render stack.
 *
 * The distinction these tests are built around is the one the pool's whole failure posture rests
 * on: `Failed` means the *player* answered "I cannot draw this document" and the caller must
 * **not** retry the one-shot path (that would double the cost of every bad document), while
 * `Unusable` means the *pool* could not serve at all and falling back is correct.
 */
class RcJvmWorkerPoolTest {

  @Test
  fun aWarmWorkerIsReusedAcrossDocumentsAndCarriesTheWholeRequest() {
    pool().use { pool ->
      val first = pool.render(DOC, SPEC, "", RcJvmServerRenderer.Format.PNG)
      val second = pool.render(DOC, SPEC, "", RcJvmServerRenderer.Format.PNG)

      // The spec, format and document length all survived the wire.
      assertEquals("640x480@2.0:png:light::${DOC.size}#1", first.text())
      // `#2` is the point of the pool: the second document was drawn by the same process, so it
      // paid no JVM boot. A fresh worker would report `#1` again.
      assertEquals("640x480@2.0:png:light::${DOC.size}#2", second.text())
    }
  }

  @Test
  fun seedsAndFormatReachTheWorker() {
    pool().use { pool ->
      val result = pool.render(DOC, SPEC, "float bmFtZQ== 1.5", RcJvmServerRenderer.Format.SVG)
      assertEquals("640x480@2.0:svg:light:float bmFtZQ== 1.5:${DOC.size}#1", result.text())
    }
  }

  @Test
  fun theRequestedThemeReachesTheWorker() {
    // The `ColorTheme` branch is a per-request property, not a property of the worker: one warm
    // process serves light and dark requests in turn. A theme pinned at spawn — or dropped from the
    // frame, which is what happened before it was carried — renders every document in one mode
    // while the caller believes it asked for the other.
    pool().use { pool ->
      val dark =
        pool.render(
          DOC,
          SPEC,
          "",
          RcJvmServerRenderer.Format.PNG,
          RcJvmServerRenderer.RenderTheme.DARK,
        )
      val light =
        pool.render(
          DOC,
          SPEC,
          "",
          RcJvmServerRenderer.Format.PNG,
          RcJvmServerRenderer.RenderTheme.LIGHT,
        )

      assertEquals("640x480@2.0:png:dark::${DOC.size}#1", dark.text())
      assertEquals("640x480@2.0:png:light::${DOC.size}#2", light.text())
    }
  }

  @Test
  fun cmpJvmThemeFallsBackToTheBakedPreviewMode() {
    assertEquals(
      RcJvmServerRenderer.RenderTheme.DARK,
      ServeHttpServer.cmpJvmRenderTheme(null, 0x20),
    )
    assertEquals(
      RcJvmServerRenderer.RenderTheme.LIGHT,
      ServeHttpServer.cmpJvmRenderTheme(null, 0x10),
    )
    assertEquals(
      RcJvmServerRenderer.RenderTheme.LIGHT,
      ServeHttpServer.cmpJvmRenderTheme("light", 0x20),
      "an explicit request overrides a baked dark preview",
    )
    assertEquals(
      RcJvmServerRenderer.RenderTheme.DARK,
      ServeHttpServer.cmpJvmRenderTheme("DARK", 0x10),
      "the query remains case-insensitive",
    )
    assertEquals(
      RcJvmServerRenderer.RenderTheme.DARK,
      ServeHttpServer.cmpJvmRenderTheme(null, 0, darkFirst = true),
      "an unspecified preview follows the catalog's dark-first display policy",
    )
    assertEquals(
      RcJvmServerRenderer.RenderTheme.LIGHT,
      ServeHttpServer.cmpJvmRenderTheme(null, 0x10, darkFirst = true),
      "an explicitly light preview still wins over the catalog fallback",
    )
  }

  @Test
  fun aDocumentThePlayerCannotDrawIsFailedNotUnusable() {
    pool(mode = "failed").use { pool ->
      val result = pool.render(DOC, SPEC, "", RcJvmServerRenderer.Format.PNG)
      // Failed, so `RcJvmServerRenderer` returns it as-is instead of re-rendering cold.
      val failed = assertIs<RcJvmWorkerPool.PoolResult.Failed>(result)
      assertContains(failed.reason, "cmp-jvm render failed")
    }
  }

  @Test
  fun aHangingWorkerIsKilledByTheWatchdogAndReportedAsUnusable() {
    pool(mode = "hang", renderTimeoutSeconds = 2).use { pool ->
      val result = pool.render(DOC, SPEC, "", RcJvmServerRenderer.Format.PNG)
      val unusable = assertIs<RcJvmWorkerPool.PoolResult.Unusable>(result)
      assertContains(unusable.reason, "timed out")
    }
  }

  @Test
  fun aWorkerThatDiesMidRequestIsUnusableSoTheCallerFallsBack() {
    pool(mode = "crash").use { pool ->
      val result = pool.render(DOC, SPEC, "", RcJvmServerRenderer.Format.PNG)
      assertIs<RcJvmWorkerPool.PoolResult.Unusable>(result)
    }
  }

  @Test
  fun aSidecarSpeakingAnotherProtocolVersionIsRefused() {
    pool(mode = "badVersion").use { pool ->
      val result = pool.render(DOC, SPEC, "", RcJvmServerRenderer.Format.PNG)
      val unusable = assertIs<RcJvmWorkerPool.PoolResult.Unusable>(result)
      assertContains(unusable.reason, "protocol")
    }
  }

  @Test
  fun strayStdoutFromAWorkerIsRefusedRatherThanReadAsAFrame() {
    // Why the real worker reroutes `System.out` to stderr before anything else runs: one stray
    // line is read as a frame header and the stream never resynchronises. Refusing beats
    // misreading — the caller falls back and still gets a picture.
    pool(mode = "chatty").use { pool ->
      val result = pool.render(DOC, SPEC, "", RcJvmServerRenderer.Format.PNG)
      assertIs<RcJvmWorkerPool.PoolResult.Unusable>(result)
    }
  }

  @Test
  fun aWorkerIsRetiredAfterItsRenderBudget() {
    pool(maxRendersPerWorker = 1).use { pool ->
      val first = pool.render(DOC, SPEC, "", RcJvmServerRenderer.Format.PNG)
      val second = pool.render(DOC, SPEC, "", RcJvmServerRenderer.Format.PNG)
      // Both report `#1`: the budget of one retired the first worker, so the second render was
      // served by a fresh process. This is what bounds a native leak.
      assertEquals("640x480@2.0:png:light::${DOC.size}#1", first.text())
      assertEquals("640x480@2.0:png:light::${DOC.size}#1", second.text())
    }
  }

  @Test
  fun anAgedOutWorkerIsRetiredEvenWithBudgetLeft() {
    var now = 0L
    pool(maxWorkerAgeMillis = 1_000, clock = { now }).use { pool ->
      val first = pool.render(DOC, SPEC, "", RcJvmServerRenderer.Format.PNG)
      now += 5_000
      val second = pool.render(DOC, SPEC, "", RcJvmServerRenderer.Format.PNG)
      assertEquals("640x480@2.0:png:light::${DOC.size}#1", first.text())
      assertEquals("640x480@2.0:png:light::${DOC.size}#1", second.text())
    }
  }

  @Test
  fun closingThePoolReleasesAWorkerThatIsMidRender() {
    // A checked-out worker is absent from the idle set, so a shutdown that only drains `idle`
    // leaves its child JVM alive and its caller blocked on a pipe read forever — and stopping the
    // watchdog removes the scheduled kill that was the only other way out. Destroying every live
    // worker, before the watchdog stops, is what makes shutdown terminate.
    val pool = pool(mode = "hang", renderTimeoutSeconds = 600)
    val outcome = java.util.concurrent.ArrayBlockingQueue<RcJvmWorkerPool.PoolResult>(1)
    val renderThread = Thread {
      outcome.add(pool.render(DOC, SPEC, "", RcJvmServerRenderer.Format.PNG))
    }
    renderThread.start()

    // Wait until the worker is genuinely mid-render rather than still booting, so the test covers
    // the checked-out case instead of accidentally closing an empty pool.
    val spawned = System.currentTimeMillis()
    while (
      renderThread.state == Thread.State.NEW && System.currentTimeMillis() - spawned < 30_000
    ) {
      Thread.sleep(20)
    }
    Thread.sleep(2_000)

    pool.close()

    val result =
      outcome.poll(60, java.util.concurrent.TimeUnit.SECONDS)
        ?: error("closing the pool left a mid-render caller blocked — it never returned")
    assertIs<RcJvmWorkerPool.PoolResult.Unusable>(result)
    renderThread.join(10_000)
    assertTrue(!renderThread.isAlive, "render thread did not finish after shutdown")
  }

  @Test
  fun repeatedStartFailuresDisableThePoolSoTheCostIsPaidOnce() {
    pool(workerMainClass = "ee.schimke.composeai.cli.serve.NoSuchWorkerMain").use { pool ->
      val reasons =
        (1..RcJvmWorkerPool.MAX_START_FAILURES + 1).map {
          assertIs<RcJvmWorkerPool.PoolResult.Unusable>(
              pool.render(DOC, SPEC, "", RcJvmServerRenderer.Format.PNG)
            )
            .reason
        }
      // Every attempt is unusable (so every render still gets a picture from the one-shot path),
      // and once the pool has given up it says so rather than spawning a doomed JVM per render.
      assertTrue(reasons.all { it.isNotBlank() })
      assertContains(reasons.last(), "disabled after")
    }
  }

  @Test
  fun concurrentRendersAreServedWithoutCrossingStreams() {
    pool(maxWorkers = 3).use { pool ->
      val results = java.util.concurrent.ConcurrentHashMap<Int, String>()
      val threads =
        (1..12).map { i ->
          Thread {
            // A document of a distinct length per caller, so a crossed stream is detectable.
            val doc = ByteArray(i) { it.toByte() }
            results[i] = pool.render(doc, SPEC, "", RcJvmServerRenderer.Format.PNG).text()
          }
        }
      threads.forEach { it.start() }
      threads.forEach { it.join(60_000) }

      assertEquals(12, results.size)
      // Each caller must get the answer to *its own* document. A shared or interleaved stream
      // would surface here as a mismatched length.
      (1..12).forEach { i -> assertContains(results.getValue(i), ":$i#") }
    }
  }

  private fun RcJvmWorkerPool.PoolResult.text(): String =
    String(assertIs<RcJvmWorkerPool.PoolResult.Ok>(this).bytes, Charsets.UTF_8)

  private fun pool(
    mode: String = "ok",
    maxWorkers: Int = 1,
    maxRendersPerWorker: Int = 100,
    maxWorkerAgeMillis: Long = 10 * 60_000L,
    renderTimeoutSeconds: Long = 60,
    clock: () -> Long = System::currentTimeMillis,
    workerMainClass: String = STUB_MAIN_CLASS,
  ) =
    RcJvmWorkerPool(
      classpath = testClasspath(),
      javaBin = javaBin(),
      extraJvmArgs = listOf("-Dstub.mode=$mode"),
      maxWorkers = maxWorkers,
      maxRendersPerWorker = maxRendersPerWorker,
      maxWorkerAgeMillis = maxWorkerAgeMillis,
      renderTimeoutSeconds = renderTimeoutSeconds,
      clock = clock,
      workerMainClass = workerMainClass,
    )

  /** This JVM's own classpath, which carries the stub — no staged sidecar needed. */
  private fun testClasspath(): List<File> =
    System.getProperty("java.class.path").split(File.pathSeparator).map(::File)

  private fun javaBin(): String {
    val candidate = File(System.getProperty("java.home"), "bin/java")
    return if (candidate.canExecute()) candidate.absolutePath else "java"
  }

  private companion object {
    const val STUB_MAIN_CLASS = "ee.schimke.composeai.cli.serve.RcJvmWorkerPoolStub"
    val SPEC = RcJvmRenderSpec(widthPx = 640, heightPx = 480, density = 2f)
    val DOC = ByteArray(37) { it.toByte() }
  }
}
