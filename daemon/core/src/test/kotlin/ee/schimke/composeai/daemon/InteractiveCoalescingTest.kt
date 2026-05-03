package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.InteractiveInputParams
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the v2 phase-3 coalescing path (INTERACTIVE.md § 9.6) — inputs arriving while a render
 * is already in flight for the same stream queue up and dispatch as a batch when the in-flight
 * render finishes, capping the per-stream render rate at the renderer's natural cadence without
 * dropping events.
 *
 * Driven by [SlowFakeHost] — a session whose `render` blocks for a configurable wall-clock duration
 * so the test can fire a burst of inputs faster than the renderer drains them. The load-bearing
 * assertion is that the burst produces fewer renders than inputs (specifically: the daemon emitted
 * at most 2 renderFinished notifications for 5 inputs — the first one and the batch-drain after).
 */
class InteractiveCoalescingTest {

  private val json = Json { ignoreUnknownKeys = true }
  private val resourcesToClose = mutableListOf<AutoCloseable>()

  @After
  fun teardown() {
    resourcesToClose.reversed().forEach { runCatching { it.close() } }
  }

  @Test(timeout = 30_000)
  fun burst_inputs_during_in_flight_render_coalesce_into_one_followup_render() {
    val tmp = Files.createTempDirectory("interactive-coalescing").toFile()
    val pngFile = File(tmp, "preview-A.png").apply { writeBytes(testPngBytes(seed = 0)) }
    val host = SlowFakeHost(pngFile, renderDelayMs = 200)

    val (_, serverThread, clientToServerOut, received, exitLatch) = bringUpServer(host)
    resourcesToClose.add(AutoCloseable { runCatching { clientToServerOut.close() } })

    handshake(clientToServerOut, received)

    // Start an interactive session.
    writeFrame(
      clientToServerOut,
      """{"jsonrpc":"2.0","id":10,"method":"interactive/start","params":{"previewId":"preview-A"}}""",
    )
    val startResp = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 10 }
    val streamId =
      startResp!!["result"]!!.jsonObject["frameStreamId"]!!.jsonPrimitive.contentOrNull!!
    val session = host.lastSession()!!

    // Fire 5 inputs back-to-back — faster than the 200ms render cadence. With coalescing, only
    // the first input triggers a render; inputs 2-5 queue up while render 1 is in flight, then
    // dispatch as a batch driving exactly one follow-up render. Without coalescing this burst
    // would produce 5 renders (one per input), each waiting 200ms — taking >1 s total.
    repeat(5) { i ->
      writeFrame(
        clientToServerOut,
        """
        {"jsonrpc":"2.0","method":"interactive/input","params":{
          "frameStreamId":"$streamId","kind":"click","pixelX":$i,"pixelY":$i
        }}
        """
          .trimIndent(),
      )
    }

    // Collect every renderFinished notification within a generous window. With coalescing we
    // expect ≤ 2 renders total (one for the first input, one for the batch-drain). 5 renders
    // would fail this — that's the load-bearing assertion.
    val finishedFrames = mutableListOf<JsonObject>()
    val deadline = System.currentTimeMillis() + 2_500
    while (System.currentTimeMillis() < deadline) {
      val msg =
        pollUntil(received, timeoutMs = (deadline - System.currentTimeMillis()).coerceAtLeast(50)) {
          it["method"]?.jsonPrimitive?.contentOrNull == "renderFinished"
        }
      if (msg == null) break
      finishedFrames.add(msg)
    }

    assertTrue(
      "expected at least one renderFinished after the burst; got ${finishedFrames.size}",
      finishedFrames.isNotEmpty(),
    )
    assertTrue(
      "expected ≤ 2 renderFinished notifications for 5 coalesced inputs (one per render-batch); " +
        "got ${finishedFrames.size}",
      finishedFrames.size <= 2,
    )

    // Render count should match the number of renderFinished frames — the session got driven
    // through `render` once per emitted frame.
    assertEquals(
      "session.render should fire once per emitted frame",
      finishedFrames.size,
      session.renderCount.get(),
    )
    // All 5 inputs should have been dispatched (none dropped). Coalescing batches dispatch, it
    // doesn't discard — that's the contract.
    assertEquals(
      "every input must have been dispatch()'d (none dropped during coalescing)",
      5,
      session.dispatchCount.get(),
    )

    teardownServer(clientToServerOut, received, serverThread, exitLatch)
  }

  // ----- harness scaffolding (mirrors InteractiveSessionPlumbingTest's; kept self-contained
  // so this file doesn't depend on the other test's private helpers) -----

  private data class ServerHarness(
    val server: JsonRpcServer,
    val thread: Thread,
    val clientToServerOut: PipedOutputStream,
    val received: LinkedBlockingQueue<JsonObject>,
    val exitLatch: CountDownLatch,
  )

  private fun bringUpServer(host: RenderHost): ServerHarness {
    val clientToServerOut = PipedOutputStream()
    val clientToServerIn = PipedInputStream(clientToServerOut, 64 * 1024)
    val serverToClientOut = PipedOutputStream()
    val serverToClientIn = PipedInputStream(serverToClientOut, 64 * 1024)
    val exitLatch = CountDownLatch(1)
    val server =
      JsonRpcServer(
        input = clientToServerIn,
        output = serverToClientOut,
        host = host,
        daemonVersion = "test",
        onExit = { _ -> exitLatch.countDown() },
      )
    val thread = Thread({ server.run() }, "interactive-coalescing-server").apply { isDaemon = true }
    thread.start()
    val reader = ContentLengthFramer(serverToClientIn)
    val received = LinkedBlockingQueue<JsonObject>()
    Thread(
        {
          try {
            while (true) {
              val frame = reader.readFrame() ?: break
              val obj = json.parseToJsonElement(frame.toString(Charsets.UTF_8)).jsonObject
              received.put(obj)
            }
          } catch (_: Throwable) {}
        },
        "interactive-coalescing-reader",
      )
      .apply { isDaemon = true }
      .start()
    return ServerHarness(server, thread, clientToServerOut, received, exitLatch)
  }

  private fun handshake(out: PipedOutputStream, received: LinkedBlockingQueue<JsonObject>) {
    writeFrame(
      out,
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
        "protocolVersion":1,"clientVersion":"test","workspaceRoot":"/tmp",
        "moduleId":":test","moduleProjectDir":"/tmp",
        "capabilities":{"visibility":true,"metrics":false}}}""",
    )
    val init = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 1 }
    assertNotNull(init)
    writeFrame(out, """{"jsonrpc":"2.0","method":"initialized","params":{}}""")
  }

  private fun teardownServer(
    out: PipedOutputStream,
    received: LinkedBlockingQueue<JsonObject>,
    thread: Thread,
    exitLatch: CountDownLatch,
  ) {
    writeFrame(out, """{"jsonrpc":"2.0","id":99,"method":"shutdown"}""")
    pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 99 }
    writeFrame(out, """{"jsonrpc":"2.0","method":"exit"}""")
    assertTrue("server should exit cleanly", exitLatch.await(5, TimeUnit.SECONDS))
    thread.join(5_000)
  }

  private fun writeFrame(out: PipedOutputStream, json: String) {
    val payload = json.toByteArray(Charsets.UTF_8)
    out.write("Content-Length: ${payload.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
    out.write(payload)
    out.flush()
  }

  private fun pollUntil(
    queue: LinkedBlockingQueue<JsonObject>,
    timeoutMs: Long = 5_000,
    matcher: (JsonObject) -> Boolean,
  ): JsonObject? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
      val msg = queue.poll(remaining, TimeUnit.MILLISECONDS) ?: return null
      if (matcher(msg)) return msg
    }
    return null
  }

  private fun testPngBytes(seed: Int): ByteArray {
    val sig = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    val payload = ByteArray(16) { i -> ((i + seed * 13) and 0xFF).toByte() }
    return sig + payload
  }
}

/**
 * Test [RenderHost] whose [InteractiveSession] sleeps for [renderDelayMs] inside `render` so the
 * coalescing test can fire inputs faster than the renderer drains them. Records dispatch and render
 * counters so the test can assert on the coalescing arithmetic.
 */
private class SlowFakeHost(private val pngFile: File, private val renderDelayMs: Long) :
  RenderHost {

  private val sessions = ConcurrentHashMap<Long, SlowSession>()
  private val nextSessionKey = java.util.concurrent.atomic.AtomicLong(1)

  override fun start() {}

  override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult {
    require(request is RenderRequest.Render)
    return RenderResult(
      id = request.id,
      classLoaderHashCode = 0,
      classLoaderName = "slow-fake",
      pngPath = pngFile.absolutePath,
      metrics = mapOf("tookMs" to renderDelayMs),
    )
  }

  override fun shutdown(timeoutMs: Long) {}

  override fun acquireInteractiveSession(
    previewId: String,
    classLoader: ClassLoader,
    inspectionMode: Boolean?,
  ): InteractiveSession {
    val session = SlowSession(previewId, pngFile, renderDelayMs)
    sessions[nextSessionKey.getAndIncrement()] = session
    return session
  }

  fun lastSession(): SlowSession? = sessions.entries.maxByOrNull { it.key }?.value
}

private class SlowSession(
  override val previewId: String,
  private val pngFile: File,
  private val renderDelayMs: Long,
) : InteractiveSession {

  val dispatchCount = AtomicInteger(0)
  val renderCount = AtomicInteger(0)

  override fun dispatch(input: InteractiveInputParams) {
    require(input.kind == InteractiveInputKind.CLICK) // test only sends clicks
    dispatchCount.incrementAndGet()
  }

  override fun render(requestId: Long, advanceTimeMs: Long?): RenderResult {
    Thread.sleep(renderDelayMs) // simulate a slow render so subsequent inputs queue up
    renderCount.incrementAndGet()
    return RenderResult(
      id = requestId,
      classLoaderHashCode = 0,
      classLoaderName = "slow-session",
      pngPath = pngFile.absolutePath,
      metrics = mapOf("tookMs" to renderDelayMs),
    )
  }

  override fun close() {}
}
