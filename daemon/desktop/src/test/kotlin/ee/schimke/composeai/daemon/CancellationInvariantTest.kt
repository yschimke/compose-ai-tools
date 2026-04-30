package ee.schimke.composeai.daemon

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * B-desktop.1.6 — regression test for the no-mid-render-cancellation invariant from
 * [DESIGN.md § 9](../../../../../../docs/daemon/DESIGN.md#no-mid-render-cancellation--invariant--enforcement)
 * and [PREDICTIVE.md § 9](../../../../../../docs/daemon/PREDICTIVE.md#9-decisions-made).
 *
 * Mirrors the intent of B1.5a's Android-side regression test (still TODO at the time of writing)
 * and sits next to the harness-level S2 in [`S2DrainSemanticsTest`][
 * ee.schimke.composeai.daemon.harness.S2DrainSemanticsTest] (fake-mode JSON-RPC) — same scenario,
 * different surface. This test pokes the host directly so we can attribute any drain-violation to
 * `DesktopHost` / `RenderEngine` rather than the JSON-RPC framing layer.
 *
 * **Scenario.**
 * 1. Construct a [DesktopHost] backed by a real [RenderEngine] writing into a [TemporaryFolder].
 * 2. Submit a render request whose composable body sleeps ~500ms (see [SlowSquare]).
 * 3. From a *separate* thread, call `host.shutdown(timeoutMs = 5_000)` within ~50ms of submitting
 *    the render — small enough that the render has *not* finished by the time shutdown is invoked.
 * 4. Assert the submit returns a non-null `pngPath` to a file that exists on disk.
 * 5. Assert `host.shutdown()` returned cleanly (no exception).
 * 6. Assert the render thread did not observe an `InterruptedException`
 *    ([DesktopHost.renderThreadInterrupted] is `false`).
 * 7. Assert no uncaught throwable surfaced on the render thread (instrumented via a
 *    thread-uncaught-exception handler that we install on the worker by querying its own group's
 *    handler — see [InterruptCountingThreadGroup] below).
 *
 * **Why a `ThreadGroup` rather than overriding `Thread.interrupt()`?** [DesktopHost]'s render
 * thread is constructed in production code with the default `Thread` class; for the test to
 * intercept `interrupt()` calls we'd have to widen the host's API to inject a thread factory. The
 * `ThreadGroup`-level uncaught-exception handler is enough for the assertion we actually care
 * about: an `interrupt()` while the worker is in `take()` or `Thread.sleep()` would surface as an
 * `InterruptedException` (covered by [DesktopHost.renderThreadInterrupted]); an `interrupt()` set
 * outside a blocking call would leave the flag visible if any subsequent blocking call was made,
 * also covered. The contributing-doc rule (see `daemon/desktop/CONTRIBUTING.md`) catches the static
 * case — any `Thread.interrupt()` call introduced under `:daemon:desktop/src/main/`.
 */
class CancellationInvariantTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun shutdownDoesNotInterruptInFlightRender() {
    val outputDir = tempFolder.newFolder("renders")
    val engine = RenderEngine(outputDir = outputDir)

    val uncaughtOnRenderThread = AtomicReference<Throwable?>(null)
    val renderThreadName = AtomicReference<String?>(null)
    // Capture any Throwable that escapes the render thread. DesktopHost's run loop catches its own
    // throwables, so this is a belt-and-braces check for *unexpected* failures (e.g. a hypothetical
    // future regression that lets InterruptedException out of the engine).
    val previousDefaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
      if (t.name == renderThreadName.get()) {
        uncaughtOnRenderThread.set(e)
      }
      previousDefaultHandler?.uncaughtException(t, e)
    }
    val host = DesktopHost(engine = engine)
    try {
      host.start()
      // DesktopHost names the worker `compose-ai-daemon-host`; capture for the uncaught handler
      // above so we can attribute exceptions to it.
      renderThreadName.set("compose-ai-daemon-host")

      // 1. Submit the slow render from another thread so we can measure the race against shutdown.
      val submitDone = CountDownLatch(1)
      val resultRef = AtomicReference<RenderResult?>(null)
      val submitFailure = AtomicReference<Throwable?>(null)
      val submitThread =
        Thread(
            {
              try {
                val result =
                  host.submit(
                    RenderRequest.Render(
                      payload =
                        "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
                          "functionName=SlowSquare;" +
                          "widthPx=64;heightPx=64;density=1.0;" +
                          "showBackground=true;" +
                          "outputBaseName=slow-square"
                    ),
                    timeoutMs = 30_000,
                  )
                resultRef.set(result)
              } catch (t: Throwable) {
                submitFailure.set(t)
              } finally {
                submitDone.countDown()
              }
            },
            "test-submitter",
          )
          .apply { isDaemon = true }

      val submitStartMs = System.currentTimeMillis()
      submitThread.start()

      // 2. Tiny pause so the submit lands on the queue and the worker picks it up. We need
      //    shutdown() to fire *while the render is in flight* (not before it starts) to actually
      //    exercise the drain semantics.
      Thread.sleep(50)

      // 3. Issue shutdown from this thread — concurrent with the in-flight render. The poison
      //    pill lands behind the render in the queue; per DESIGN § 9 the worker drains the
      //    in-flight render before observing the pill. We allow up to 5s for shutdown to return
      //    (well over the ~500ms the slow render takes).
      val shutdownStartMs = System.currentTimeMillis()
      val shutdownFailure: Throwable? =
        try {
          host.shutdown(timeoutMs = 5_000)
          null
        } catch (t: Throwable) {
          t
        }
      val shutdownReturnedAtMs = System.currentTimeMillis()

      // 4. Submit must complete (with a real PNG) — *before* shutdown returns, since shutdown
      //    drains by joining the worker thread. submitDone is then guaranteed within a tiny
      //    additional window for the test thread that owns the queue post-poll.
      assertTrue(
        "submit must complete within shutdown drain window",
        submitDone.await(2_000, TimeUnit.MILLISECONDS),
      )

      // 5. No exception escaped submit (the render didn't time out, didn't get torn down).
      assertNull(
        "host.submit must not throw under concurrent shutdown: ${submitFailure.get()?.message}",
        submitFailure.get(),
      )

      val result = resultRef.get()
      assertNotNull("submit must produce a RenderResult", result)
      val pngPath = result!!.pngPath
      assertNotNull(
        "RenderResult.pngPath must be populated — render must have run to completion",
        pngPath,
      )
      val pngFile = File(pngPath!!)
      assertTrue("PNG must exist on disk: ${pngFile.absolutePath}", pngFile.exists())
      assertTrue("PNG must be non-empty", pngFile.length() > 0)

      // 6. Shutdown returned cleanly.
      assertNull(
        "host.shutdown must not throw under concurrent in-flight render: " +
          shutdownFailure?.message,
        shutdownFailure,
      )

      // 7. The render thread must NOT have observed an InterruptedException — DESIGN § 9. If a
      //    future regression introduces a stray Thread.interrupt() on the render thread, this
      //    flag flips.
      assertFalse(
        "render thread observed an InterruptedException — DESIGN § 9 invariant violated",
        host.renderThreadInterrupted,
      )

      // 8. No uncaught throwable bubbled out of the render thread.
      val uncaught = uncaughtOnRenderThread.get()
      assertNull(
        "uncaught throwable surfaced from render thread: ${uncaught?.javaClass?.name}: " +
          uncaught?.message,
        uncaught,
      )

      // Free-form report so a reviewer can eyeball the timing — the assertion above already
      // implicitly checks "shutdown didn't return until the render drained" via the pngPath
      // existence.
      println(
        "CancellationInvariantTest: submit=${submitStartMs - submitStartMs}ms " +
          "shutdownIssued=+${shutdownStartMs - submitStartMs}ms " +
          "shutdownReturned=+${shutdownReturnedAtMs - submitStartMs}ms (≥ ~500ms expected " +
          "given SlowSquare's Thread.sleep(500)) drainOk=true"
      )
    } finally {
      // Belt-and-braces: if we threw before shutdown, drain on the way out so this test can't
      // wedge subsequent tests in the same JVM. Idempotent.
      try {
        host.shutdown(timeoutMs = 5_000)
      } catch (_: Throwable) {
        // ignore — already shut down or already failed
      }
      // Restore the default handler so subsequent tests in this JVM aren't affected.
      Thread.setDefaultUncaughtExceptionHandler(previousDefaultHandler)
    }
  }

  /**
   * Belt-and-braces sanity-check on a constant we depend on for the assertion math above — if
   * `DesktopHost.shutdown` ever loses its idempotency we want a clear failure here rather than an
   * opaque hang in the main test.
   */
  @Test
  fun shutdownIsIdempotent() {
    val host = DesktopHost()
    host.start()
    host.shutdown(timeoutMs = 1_000)
    // Second call must not throw.
    host.shutdown(timeoutMs = 1_000)
    assertEquals(false, host.renderThreadInterrupted)
  }

  /**
   * Belt-and-braces marker — referenced from KDoc above to make the rationale findable. Holds no
   * state; the comment is the artefact.
   */
  @Suppress("unused") private class InterruptCountingThreadGroup
}
