package ee.schimke.composeai.daemon

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The post-input frame burst — the second half of the wear-m3-catalog#32 fix.
 *
 * A held session's frame loop runs at the idle cadence (250ms in production) so a resting preview
 * is cheap. Press feedback is shorter than that gap end to end, so a tap's ripple used to live and
 * die between two idle frames and a live click looked inert. Every input now runs the loop at the
 * burst cadence for a short window, which is what puts those frames on the wire.
 *
 * The test pins both halves of that: an input produces many more frames than the idle cadence could
 * have, and the burst *ends* — the loop returns to idle rather than pinning a render thread at
 * 60fps forever.
 */
class InteractiveInputBurstTest {

  private val json = Json { ignoreUnknownKeys = true }
  private val resourcesToClose = mutableListOf<AutoCloseable>()

  @After
  fun teardown() {
    resourcesToClose.reversed().forEach { runCatching { it.close() } }
  }

  @Test(timeout = 60_000)
  fun one_input_bursts_frames_and_then_falls_back_to_the_idle_cadence() {
    val tmp = Files.createTempDirectory("interactive-burst").toFile()
    val pngFile = File(tmp, "preview-A.png").apply { writeBytes(testPngBytes()) }
    val host = CountingFakeHost(pngFile)

    // Idle cadence far longer than the whole test, so every frame after the bootstrap one is
    // attributable to the burst rather than to the periodic loop.
    val (_, serverThread, clientToServerOut, received, exitLatch) =
      bringUpServer(host, idleMs = 30_000L, burstIntervalMs = 20L, burstMs = 400L)
    resourcesToClose.add(AutoCloseable { runCatching { clientToServerOut.close() } })

    handshake(clientToServerOut, received)

    writeFrame(
      clientToServerOut,
      """{"jsonrpc":"2.0","id":10,"method":"interactive/start","params":{"previewId":"preview-A"}}""",
    )
    val startResp = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 10 }
    val streamId =
      startResp!!["result"]!!.jsonObject["frameStreamId"]!!.jsonPrimitive.contentOrNull!!
    val session = host.lastSession()!!

    // The bootstrap render + the loop's own first pass land before the click; take that as the
    // baseline so the assertion is about frames the input caused.
    Thread.sleep(200)
    val beforeClick = session.renderCount.get()

    writeFrame(
      clientToServerOut,
      """
      {"jsonrpc":"2.0","method":"interactive/input","params":{
        "frameStreamId":"$streamId","kind":"click","pixelX":10,"pixelY":10
      }}
      """
        .trimIndent(),
    )

    // 400ms of burst at 20ms is ~20 frames; assert well under that so a slow CI box still passes,
    // but well over the ≤1 the idle cadence could have produced in the same window.
    val burstDeadline = System.currentTimeMillis() + 10_000
    while (
      session.renderCount.get() - beforeClick < 5 && System.currentTimeMillis() < burstDeadline
    ) {
      Thread.sleep(20)
    }
    val burstFrames = session.renderCount.get() - beforeClick
    assertTrue(
      "an input should burst frames while its animation settles; got $burstFrames render(s) " +
        "(idle cadence alone could produce at most 1 in this window)",
      burstFrames >= 5,
    )

    // The burst is a window, not a mode: once it lapses the loop is back on the idle cadence, so
    // the render count stops moving.
    Thread.sleep(1_000)
    val afterBurst = session.renderCount.get()
    Thread.sleep(1_000)
    assertTrue(
      "the burst must lapse back to the idle cadence; renders kept arriving " +
        "($afterBurst → ${session.renderCount.get()})",
      session.renderCount.get() == afterBurst,
    )

    teardownServer(clientToServerOut, received, serverThread, exitLatch)
  }

  // ----- harness scaffolding (mirrors InteractiveCoalescingTest's; kept self-contained) -----

  private data class ServerHarness(
    val server: JsonRpcServer,
    val thread: Thread,
    val clientToServerOut: PipedOutputStream,
    val received: LinkedBlockingQueue<JsonObject>,
    val exitLatch: CountDownLatch,
  )

  private fun bringUpServer(
    host: RenderHost,
    idleMs: Long,
    burstIntervalMs: Long,
    burstMs: Long,
  ): ServerHarness {
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
        interactiveFrameIntervalMs = idleMs,
        interactiveBurstIntervalMs = burstIntervalMs,
        interactiveBurstMs = burstMs,
      )
    val thread = Thread({ server.run() }, "interactive-burst-server").apply { isDaemon = true }
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
        "interactive-burst-reader",
      )
      .apply { isDaemon = true }
      .start()
    return ServerHarness(server, thread, clientToServerOut, received, exitLatch)
  }

  private fun handshake(out: PipedOutputStream, received: LinkedBlockingQueue<JsonObject>) {
    writeFrame(
      out,
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
        "protocolVersion":2,"clientVersion":"test","workspaceRoot":"/tmp",
        "moduleId":":test","moduleProjectDir":"/tmp",
        "capabilities":{"visibility":true,"metrics":false}}}""",
    )
    assertNotNull(pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 1 })
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

  private fun testPngBytes(): ByteArray {
    val sig = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    return sig + ByteArray(16) { i -> (i * 13 and 0xFF).toByte() }
  }
}

/** Test [RenderHost] whose sessions just count what they were asked to do. */
private class CountingFakeHost(private val pngFile: File) : RenderHost {

  private val sessions = ConcurrentHashMap<Long, CountingSession>()
  private val nextSessionKey = java.util.concurrent.atomic.AtomicLong(1)

  override fun start() {}

  override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult {
    require(request is RenderRequest.Render)
    return RenderResult(
      id = request.id,
      classLoaderHashCode = 0,
      classLoaderName = "counting-fake",
      pngPath = pngFile.absolutePath,
      metrics = mapOf("tookMs" to 0L),
    )
  }

  override fun shutdown(timeoutMs: Long) {}

  override fun acquireInteractiveSession(
    previewId: String,
    classLoader: ClassLoader,
    inspectionMode: Boolean?,
    onSessionClosed: (() -> Unit)?,
    overrides: ee.schimke.composeai.daemon.protocol.PreviewOverrides?,
  ): InteractiveSession {
    val session = CountingSession(previewId, pngFile)
    sessions[nextSessionKey.getAndIncrement()] = session
    return session
  }

  fun lastSession(): CountingSession? = sessions.entries.maxByOrNull { it.key }?.value
}

private class CountingSession(override val previewId: String, private val pngFile: File) :
  InteractiveSession {

  val dispatchCount = AtomicInteger(0)
  val renderCount = AtomicInteger(0)

  override fun dispatch(input: InteractiveInputParams) {
    dispatchCount.incrementAndGet()
  }

  override fun render(requestId: Long, advanceTimeMs: Long?): RenderResult {
    renderCount.incrementAndGet()
    return RenderResult(
      id = requestId,
      classLoaderHashCode = 0,
      classLoaderName = "counting-session",
      pngPath = pngFile.absolutePath,
      metrics = mapOf("tookMs" to 0L),
    )
  }

  override fun close() {}
}
