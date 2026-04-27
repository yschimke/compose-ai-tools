package ee.schimke.composeai.daemon

import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end test for [JsonRpcServer] over piped streams. Drives the full happy-path lifecycle:
 *
 * initialize → initialized → renderNow → renderStarted → renderFinished → shutdown → exit
 *
 * Uses a [FakeRenderHost] that bypasses any backend (no Robolectric sandbox, no desktop Compose
 * runtime) so the test is deterministic and fast (sub-second). The real-host smoke test for the
 * Robolectric backend lives in `:renderer-android-daemon`'s `DaemonHostTest`, which is separately
 * gated because Robolectric cold-boot is non-deterministic and incompatible with Gradle's default
 * same-JVM test ordering (multiple `JUnitCore.runClasses` invocations in one JVM intermittently
 * hang on the second sandbox bootstrap).
 *
 * **Why in-process rather than spawning a real subprocess?** B1.5's DoD asked for a
 * `ProcessBuilder`-spawned daemon JVM. We deferred that for two reasons:
 *
 * 1. The descriptor produced by Stream A's `composePreviewDaemonStart` task lives in
 *    `samples/android/build/...` and isn't available to a unit test classpath without a Gradle
 *    dependency on the consumer module — that would be a circular dep (`:renderer-android-daemon`
 *    consumes `:samples:android`).
 * 2. The real value the DoD wanted to prove — request → response → notification round-trip with a
 *    working host — is fully exercised here, including the no-mid-render-cancellation invariant (we
 *    drain the in-flight queue before resolving `shutdown`). A subprocess wrapper would only add
 *    `ProcessBuilder` plumbing on top.
 *
 * A subprocess smoke test belongs in Stream C's `daemonClient.ts` integration test (C1.3 DoD),
 * which spawns the real launcher descriptor end-to-end. We track a "Stream B subprocess smoke test"
 * follow-up under B1.5a (the no-mid-render-cancellation enforcement task) since both want the same
 * ProcessBuilder harness.
 */
class JsonRpcServerIntegrationTest {

  private val json = Json { ignoreUnknownKeys = true }

  @Test(timeout = 30_000)
  fun full_lifecycle_renders_one_preview_and_emits_finished_notification() {
    val clientToServerOut = PipedOutputStream()
    val clientToServerIn = PipedInputStream(clientToServerOut, 64 * 1024)
    val serverToClientOut = PipedOutputStream()
    val serverToClientIn = PipedInputStream(serverToClientOut, 64 * 1024)

    val host = FakeRenderHost()
    val exitCode = AtomicInteger(-1)
    val exitLatch = CountDownLatch(1)
    val server =
      JsonRpcServer(
        input = clientToServerIn,
        output = serverToClientOut,
        host = host,
        daemonVersion = "test",
        onExit = { code ->
          exitCode.set(code)
          exitLatch.countDown()
        },
      )
    val serverThread = Thread({ server.run() }, "json-rpc-server-test").apply { isDaemon = true }
    serverThread.start()

    val reader = ContentLengthFramer(serverToClientIn)
    val received = LinkedBlockingQueue<JsonObject>()
    val readerThread =
      Thread(
          {
            try {
              while (true) {
                val frame = reader.readFrame() ?: break
                val obj = json.parseToJsonElement(frame.toString(Charsets.UTF_8)).jsonObject
                received.put(obj)
              }
            } catch (_: Throwable) {
              // EOF / pipe close — fine, test asserts on what we got.
            }
          },
          "json-rpc-server-test-reader",
        )
        .apply { isDaemon = true }
    readerThread.start()

    try {
      // 1. initialize
      writeFrame(
        clientToServerOut,
        """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":1,
                  "clientVersion":"test",
                  "workspaceRoot":"/tmp",
                  "moduleId":":test",
                  "moduleProjectDir":"/tmp",
                  "capabilities":{"visibility":true,"metrics":false}
                }}
        """
          .trimIndent(),
      )
      val initResponse = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 1 }
      assertNotNull("initialize response should arrive", initResponse)
      val initResult = initResponse!!["result"]!!.jsonObject
      assertEquals(1, initResult["protocolVersion"]?.jsonPrimitive?.intOrNull)
      assertEquals("test", initResult["daemonVersion"]?.jsonPrimitive?.contentOrNull)
      assertNotNull("pid should be present", initResult["pid"])

      // 2. initialized notification
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","method":"initialized","params":{}}""")

      // 3. renderNow for one preview
      writeFrame(
        clientToServerOut,
        """
        {"jsonrpc":"2.0","id":2,"method":"renderNow","params":{
                  "previews":["preview-A"],
                  "tier":"fast"
                }}
        """
          .trimIndent(),
      )
      val renderResponse = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 2 }
      assertNotNull("renderNow response should arrive", renderResponse)
      val renderResult = renderResponse!!["result"]!!.jsonObject
      val queued = renderResult["queued"].toString()
      assertTrue("queued should contain preview-A: $queued", queued.contains("preview-A"))

      // 4. renderStarted + renderFinished notifications.
      val started =
        pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "renderStarted" }
      assertNotNull("renderStarted notification should arrive", started)
      assertEquals(
        "preview-A",
        started!!["params"]!!.jsonObject["id"]?.jsonPrimitive?.contentOrNull,
      )

      val finished =
        pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "renderFinished" }
      assertNotNull("renderFinished notification should arrive", finished)
      val finishedParams = finished!!["params"]!!.jsonObject
      assertEquals("preview-A", finishedParams["id"]?.jsonPrimitive?.contentOrNull)
      val pngPath = finishedParams["pngPath"]?.jsonPrimitive?.contentOrNull
      assertNotNull("pngPath should be present (placeholder until B1.4)", pngPath)
      assertTrue(
        "pngPath should be the B1.4-stub placeholder, was $pngPath",
        pngPath!!.contains("daemon-stub-"),
      )

      // 5. shutdown — must drain in-flight (already drained here) and
      //    respond with null result. Per DESIGN.md § 9 enforcement, no
      //    Thread.interrupt() on the render thread.
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","id":3,"method":"shutdown"}""")
      val shutdownResponse = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 3 }
      assertNotNull("shutdown response should arrive", shutdownResponse)
      assertNull(
        "shutdown result must be null per PROTOCOL.md § 3",
        shutdownResponse!!["result"]?.let {
          if (it is JsonPrimitive && it.contentOrNull == null) null else it
        },
      )

      // 6. exit — server should call onExit(0).
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","method":"exit"}""")
      assertTrue(
        "server should invoke onExit() within 10s of exit notification",
        exitLatch.await(10, TimeUnit.SECONDS),
      )
      assertEquals(0, exitCode.get())

      // No render thread interruption observed by the fake host — proves
      // the no-mid-render-cancellation invariant from DESIGN.md § 9.
      assertEquals(
        "JsonRpcServer must never interrupt the render thread",
        0,
        host.interruptCount.get(),
      )
    } finally {
      try {
        clientToServerOut.close()
      } catch (_: Throwable) {}
      try {
        serverToClientIn.close()
      } catch (_: Throwable) {}
      serverThread.join(10_000)
    }
  }

  /** Helper: writes one Content-Length-framed JSON message. */
  private fun writeFrame(out: PipedOutputStream, json: String) {
    val payload = json.toByteArray(Charsets.UTF_8)
    out.write("Content-Length: ${payload.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
    out.write(payload)
    out.flush()
  }

  private fun pollUntil(
    queue: LinkedBlockingQueue<JsonObject>,
    timeoutMs: Long = 10_000,
    matcher: (JsonObject) -> Boolean,
  ): JsonObject? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
      val msg = queue.poll(remaining, TimeUnit.MILLISECONDS) ?: return null
      if (matcher(msg)) return msg
      // Otherwise drop (e.g. an interleaved notification we don't care about).
    }
    return null
  }
}

/**
 * In-test [RenderHost] implementation that mimics a real backend's submit/shutdown contract without
 * bootstrapping anything heavy (Robolectric sandbox, Compose desktop runtime, …). Renderer-agnostic
 * by design: it lives alongside [JsonRpcServer] in `:renderer-daemon-core`, away from any specific
 * render backend.
 *
 * Renders complete instantly on a single dedicated worker thread, mirroring the real backends'
 * "single render thread" guarantee. The [interruptCount] counter spies on `Thread.interrupt()`
 * calls — the no-mid-render-cancellation invariant requires this to stay at zero.
 */
private class FakeRenderHost : RenderHost {

  val interruptCount = java.util.concurrent.atomic.AtomicInteger(0)
  private val queue = LinkedBlockingQueue<RenderRequest>()
  @Volatile private var stopped = false
  private val worker =
    Thread(
        {
          while (!stopped) {
            val req =
              try {
                queue.poll(100, TimeUnit.MILLISECONDS)
              } catch (_: InterruptedException) {
                interruptCount.incrementAndGet()
                Thread.currentThread().interrupt()
                return@Thread
              } ?: continue
            when (req) {
              is RenderRequest.Render -> {
                val result =
                  RenderResult(id = req.id, classLoaderHashCode = 0, classLoaderName = "fake")
                results.computeIfAbsent(req.id) { LinkedBlockingQueue() }.put(result)
              }
              RenderRequest.Shutdown -> return@Thread
            }
          }
        },
        "fake-render-host",
      )
      .apply { isDaemon = true }

  private val results =
    java.util.concurrent.ConcurrentHashMap<Long, LinkedBlockingQueue<RenderResult>>()

  override fun start() {
    worker.start()
  }

  override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult {
    require(request is RenderRequest.Render)
    queue.put(request)
    val q = results.computeIfAbsent(request.id) { LinkedBlockingQueue() }
    return q.poll(timeoutMs, TimeUnit.MILLISECONDS)
      ?: error("FakeRenderHost.submit($request) timed out")
  }

  override fun shutdown(timeoutMs: Long) {
    stopped = true
    queue.put(RenderRequest.Shutdown)
    worker.join(timeoutMs)
  }
}
