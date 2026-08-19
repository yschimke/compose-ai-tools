package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A request that dies before its handler finishes must still release the session lease it took.
 *
 * This is not a tidiness concern. [ServeSessionRegistry.idleMillis] answers *busy* — `null`, not a
 * large number — while **any** session holds a lease, and that clock gates the theme optimizer's
 * quiet window and the `--exit-when-idle` watchdog. So a leaked lease does not cost one resident
 * daemon; it pins the whole server as busy for the life of the process, and every catalog on the
 * box quietly stops optimizing.
 *
 * **What this test does and does not establish.** It pins the observable contract — abandon a
 * request mid-render, and no lease is left behind — and it passes both before and after the release
 * in `withLeasedSession` was moved out of a `withContext`. That is a real finding rather than a
 * weak test: this server installs no request-timeout plugin, and the engine does not cancel a
 * handler coroutine when the client hangs up, so the skipped-`finally` hazard that
 * `withLeasedSessionOrNull` documents is **not reachable through this lane today**. The release was
 * still moved inline, because a `withContext` in a `finally` is skipped outright once the job is
 * cancelled and nothing about that lane guarantees it never will be — but no one should read this
 * test as evidence that a leak was observed and closed.
 */
class ServeLeaseCancellationTest {

  private val registry = ServeSessionRegistry(open = { null }, reaperIntervalMillis = 0)
  private var server: ServeHttpServer? = null

  @AfterTest
  fun tearDown() {
    server?.stop()
    registry.close()
  }

  /**
   * A host whose render blocks once [armed], so a request can be abandoned while its handler is
   * still inside it.
   *
   * Arming is deliberate rather than blocking from the start: the first request to a fresh Ktor
   * server spends long enough routing and JIT-ing that a client timeout short enough to be a
   * *mid-render* abort can fire before the handler reaches the render at all — which tests nothing.
   * The warm-up request below runs unarmed, so the abort that matters lands on a warm path.
   */
  private class BlockingHost(
    private val entered: CountDownLatch,
    private val release: CountDownLatch,
  ) : ServeHost {
    val armed = java.util.concurrent.atomic.AtomicBoolean(false)
    override val previews = listOf(ServePreview(PREVIEW_ID, PREVIEW_ID))
    override val label = "blocking"

    override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
      if (armed.get()) {
        entered.countDown()
        check(release.await(10, TimeUnit.SECONDS)) { "test render was never released" }
      }
      return RenderOutcome.Ok("png".encodeToByteArray())
    }

    override fun subscribeStream(
      previewId: String,
      overrides: PreviewOverrides,
      codec: StreamCodec?,
      maxFps: Int?,
      onUnavailable: ((String) -> Unit)?,
      onFrame: (StreamFrameParams) -> Unit,
    ): StreamHandle? = null

    override fun activeStreamCount(): Int = 0

    override fun close() = Unit
  }

  @Test
  fun `a request abandoned mid-render still releases its session lease`() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val host = BlockingHost(entered, release)
    registry.register(SESSION_ID, host = host, pinned = true)
    val http =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused",
          sessions = registry,
          defaultSessionId = SESSION_ID,
          isPublic = true,
        )
        .also { it.start() }
    server = http

    // A client that gives up long before the render will finish — a visitor navigating away, a
    // crawler abandoning a fetch, a dropped socket. The handler is inside `render` at this point,
    // blocking a thread, so cancellation cannot unwind it: the release `finally` runs only once
    // the render returns, by which time the job is already cancelled. That is the window the bug
    // lived in.
    val url = "http://127.0.0.1:${http.port}/render/$PREVIEW_ID.png?fontScale=1.5"
    // Warm the route, and prove it actually reaches `render` before anything is timed.
    OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use {
      assertEquals(200, it.code, "the render route must answer before the abort case")
    }
    assertEquals(emptyList(), awaitNoLeases(), "the warm-up must not leave a lease either")

    host.armed.set(true)
    val client =
      OkHttpClient.Builder()
        .callTimeout(500, TimeUnit.MILLISECONDS)
        .readTimeout(500, TimeUnit.MILLISECONDS)
        .build()
    val abort = runCatching { client.newCall(Request.Builder().url(url).build()).execute().close() }
    assertTrue(abort.isFailure, "the client must actually have given up: ${abort.getOrNull()}")
    assertTrue(entered.await(5, TimeUnit.SECONDS), "the handler must have taken the lease")

    release.countDown()

    // The release happens on the server's own thread as the handler unwinds, so poll rather than
    // reading the instant the latch drops.
    assertEquals(
      emptyList(),
      awaitNoLeases(),
      "an abandoned request must not leave a lease behind",
    )
    // ...and the whole-server idle clock is answerable again, which is the property that actually
    // matters — a null here is what silently switches theme optimization off.
    assertTrue(
      registry.idleMillis() != null,
      "a leaked lease pins the server as busy for the life of the process",
    )
  }

  private fun awaitNoLeases(): List<String> {
    repeat(200) {
      val held = registry.leasedSessions()
      if (held.isEmpty()) return held
      Thread.sleep(25)
    }
    return registry.leasedSessions()
  }

  private companion object {
    const val SESSION_ID = "default-mod"
    const val PREVIEW_ID = "com.example.Red"
  }
}
