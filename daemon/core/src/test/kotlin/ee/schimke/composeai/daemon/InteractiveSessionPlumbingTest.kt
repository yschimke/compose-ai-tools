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
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the v2 [InteractiveSession] dispatch plumbing in [JsonRpcServer] without exercising any
 * real Compose runtime. Uses [SessionAwareFakeHost] which implements
 * [RenderHost.acquireInteractiveSession] and records each [InteractiveSession.dispatch] /
 * [InteractiveSession.render] / [InteractiveSession.close] call into a visible counter.
 *
 * What this proves:
 * 1. `interactive/start` allocates one session per stream id (multi-target preserved from v1).
 * 2. Each `interactive/input` lands on the right session's `dispatch` (routing by streamId).
 * 3. Each `interactive/input` triggers a `render` and produces a `renderFinished`.
 * 4. `interactive/stop` closes the session and removes the registration; subsequent inputs against
 *    the stale stream do NOT re-allocate or call dispatch.
 * 5. Daemon shutdown closes any sessions still open at exit time.
 *
 * The actual "click changes pixels" assertion lives in the desktop-side integration test that lands
 * with PR 2; this test is the renderer-agnostic plumbing layer.
 */
class InteractiveSessionPlumbingTest {

  private val json = Json { ignoreUnknownKeys = true }
  private val resourcesToClose = mutableListOf<AutoCloseable>()

  @After
  fun teardown() {
    resourcesToClose.reversed().forEach { runCatching { it.close() } }
  }

  @Test(timeout = 30_000)
  fun start_allocates_session_inputs_route_through_dispatch_and_render() {
    val tmp = Files.createTempDirectory("interactive-session-plumbing").toFile()
    val pngFile = File(tmp, "preview-A.png").apply { writeBytes(testPngBytes(seed = 0)) }
    val host = SessionAwareFakeHost(pngFile)

    val (_, serverThread, clientToServerOut, received, exitLatch) = bringUpServer(host)
    resourcesToClose.add(AutoCloseable { runCatching { clientToServerOut.close() } })

    handshake(clientToServerOut, received)

    // 1. interactive/start — host should be asked to allocate a session.
    writeFrame(
      clientToServerOut,
      """{"jsonrpc":"2.0","id":10,"method":"interactive/start","params":{"previewId":"preview-A"}}""",
    )
    val startResp = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 10 }
    assertNotNull(startResp)
    val streamId =
      startResp!!["result"]!!.jsonObject["frameStreamId"]!!.jsonPrimitive.contentOrNull!!
    assertEquals(true, startResp["result"]!!.jsonObject["heldSession"]!!.jsonPrimitive.boolean)
    assertEquals(
      "host.acquireInteractiveSession should fire exactly once per start",
      1,
      host.acquireCalls.get(),
    )
    val session = host.lastSession()!!
    assertEquals("preview-A", session.previewId)

    // 2. interactive/input — dispatched into the session, then renders one frame.
    writeFrame(
      clientToServerOut,
      """
      {"jsonrpc":"2.0","method":"interactive/input","params":{
        "frameStreamId":"$streamId","kind":"click","pixelX":5,"pixelY":7
      }}
      """
        .trimIndent(),
    )
    val finished =
      pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "renderFinished" }
    assertNotNull("interactive/input must produce a renderFinished", finished)
    assertEquals("preview-A", finished!!["params"]!!.jsonObject["id"]?.jsonPrimitive?.contentOrNull)
    assertEquals("dispatch should run once per input", 1, session.dispatchCount.get())
    assertEquals("render should run once per input", 1, session.renderCount.get())
    val (px, py) = session.lastClick()!!
    assertEquals(5, px)
    assertEquals(7, py)

    // 3. Second input — the SAME session dispatches and renders again. Importantly, no second
    //    acquireInteractiveSession call is made — the session persists across inputs.
    writeFrame(
      clientToServerOut,
      """
      {"jsonrpc":"2.0","method":"interactive/input","params":{
        "frameStreamId":"$streamId","kind":"click","pixelX":11,"pixelY":13
      }}
      """
        .trimIndent(),
    )
    pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "renderFinished" }
    assertEquals(
      "host.acquireInteractiveSession must not re-fire for follow-up inputs",
      1,
      host.acquireCalls.get(),
    )
    assertEquals(2, session.dispatchCount.get())
    assertEquals(2, session.renderCount.get())

    // 4. interactive/stop — closes the session.
    writeFrame(
      clientToServerOut,
      """{"jsonrpc":"2.0","method":"interactive/stop","params":{"frameStreamId":"$streamId"}}""",
    )
    Thread.sleep(50)
    assertEquals("session.close should fire on stop", 1, session.closeCount.get())

    // 5. Stale input against the stopped stream — must not re-allocate or dispatch.
    writeFrame(
      clientToServerOut,
      """
      {"jsonrpc":"2.0","method":"interactive/input","params":{
        "frameStreamId":"$streamId","kind":"click","pixelX":1,"pixelY":1
      }}
      """
        .trimIndent(),
    )
    val stale =
      pollUntil(received, timeoutMs = 500) {
        it["method"]?.jsonPrimitive?.contentOrNull == "renderFinished"
      }
    assertNull("stale interactive/input must not produce a renderFinished", stale)
    assertEquals(
      "host.acquireInteractiveSession must not re-fire for a stale input",
      1,
      host.acquireCalls.get(),
    )
    assertEquals("dispatch must not fire after close", 2, session.dispatchCount.get())

    teardownServer(clientToServerOut, received, serverThread, exitLatch)
  }

  @Test(timeout = 30_000)
  fun start_with_live_frame_loop_renders_without_input() {
    val tmp = Files.createTempDirectory("interactive-session-live-loop").toFile()
    val pngFile = File(tmp, "preview-A.png").apply { writeBytes(testPngBytes(seed = 0)) }
    val host = SessionAwareFakeHost(pngFile)

    val (_, _, clientToServerOut, received, _) =
      bringUpServer(host, interactiveFrameIntervalMs = 25)
    resourcesToClose.add(AutoCloseable { runCatching { clientToServerOut.close() } })

    handshake(clientToServerOut, received)

    writeFrame(
      clientToServerOut,
      """{"jsonrpc":"2.0","id":10,"method":"interactive/start","params":{"previewId":"preview-A"}}""",
    )
    val startResp = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 10 }
    assertNotNull(startResp)
    val streamId =
      startResp!!["result"]!!.jsonObject["frameStreamId"]!!.jsonPrimitive.contentOrNull!!
    assertEquals(true, startResp["result"]!!.jsonObject["heldSession"]!!.jsonPrimitive.boolean)

    val finished =
      pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "renderFinished" }
    assertNotNull("live frame loop should render without waiting for input", finished)
    assertEquals("preview-A", finished!!["params"]!!.jsonObject["id"]?.jsonPrimitive?.contentOrNull)
    assertTrue(
      "session.render should be driven by the live frame loop",
      host.lastSession()!!.renderCount.get() >= 1,
    )

    writeFrame(
      clientToServerOut,
      """{"jsonrpc":"2.0","method":"interactive/stop","params":{"frameStreamId":"$streamId"}}""",
    )
  }

  @Test(timeout = 30_000)
  fun start_threads_inspection_mode_to_held_session_acquire() {
    val tmp = Files.createTempDirectory("interactive-session-inspection-mode").toFile()
    val pngFile = File(tmp, "preview-A.png").apply { writeBytes(testPngBytes(seed = 0)) }
    val host = SessionAwareFakeHost(pngFile)

    val (_, _, clientToServerOut, received, _) = bringUpServer(host)
    resourcesToClose.add(AutoCloseable { runCatching { clientToServerOut.close() } })

    handshake(clientToServerOut, received)

    writeFrame(
      clientToServerOut,
      """{"jsonrpc":"2.0","id":10,"method":"interactive/start","params":{"previewId":"preview-A","inspectionMode":true}}""",
    )

    val startResp = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 10 }
    assertNotNull(startResp)
    assertEquals(true, host.lastInspectionMode)
  }

  @Test(timeout = 30_000)
  fun supported_host_acquire_failure_fails_noisily() {
    val tmp = Files.createTempDirectory("interactive-session-failure").toFile()
    val pngFile = File(tmp, "preview-A.png").apply { writeBytes(testPngBytes(seed = 0)) }
    val host = FailingSessionHost(pngFile)

    val (_, serverThread, clientToServerOut, received, exitLatch) = bringUpServer(host)
    resourcesToClose.add(AutoCloseable { runCatching { clientToServerOut.close() } })

    handshake(clientToServerOut, received)

    writeFrame(
      clientToServerOut,
      """{"jsonrpc":"2.0","id":10,"method":"interactive/start","params":{"previewId":"preview-A"}}""",
    )
    val startResp = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 10 }
    assertNotNull(startResp)
    val error = startResp!!["error"]!!.jsonObject
    assertEquals(-32603, error["code"]!!.jsonPrimitive.int)
    assertTrue(error["message"]!!.jsonPrimitive.contentOrNull!!.contains("failed to acquire"))

    teardownServer(clientToServerOut, received, serverThread, exitLatch)
  }

  @Test(timeout = 30_000)
  fun host_unsupported_falls_back_to_v1_dispatch_path() {
    // A host whose acquireInteractiveSession throws UnsupportedOperationException (the
    // RenderHost default body) means "no v2 session". The daemon still accepts interactive/start
    // and routes inputs through the v1 stateless renderNow path — old-host backwards compatibility.
    val tmp = Files.createTempDirectory("interactive-session-fallback").toFile()
    val pngFile = File(tmp, "preview-A.png").apply { writeBytes(testPngBytes(seed = 0)) }
    val host = NoSessionFakeHost(pngFile)

    val (_, serverThread, clientToServerOut, received, exitLatch) = bringUpServer(host)
    resourcesToClose.add(AutoCloseable { runCatching { clientToServerOut.close() } })

    handshake(clientToServerOut, received)

    writeFrame(
      clientToServerOut,
      """{"jsonrpc":"2.0","id":10,"method":"interactive/start","params":{"previewId":"preview-A"}}""",
    )
    val startResp = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 10 }
    assertNotNull(
      "interactive/start must succeed even when host doesn't support sessions",
      startResp,
    )
    val streamId =
      startResp!!["result"]!!.jsonObject["frameStreamId"]!!.jsonPrimitive.contentOrNull!!
    assertEquals(false, startResp["result"]!!.jsonObject["heldSession"]!!.jsonPrimitive.boolean)

    writeFrame(
      clientToServerOut,
      """
      {"jsonrpc":"2.0","method":"interactive/input","params":{
        "frameStreamId":"$streamId","kind":"click","pixelX":1,"pixelY":1
      }}
      """
        .trimIndent(),
    )
    val finished =
      pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "renderFinished" }
    assertNotNull("v1 fallback must still emit renderFinished for inputs", finished)
    // Input should have routed through the v1 path: host.submit was called, no session dispatch.
    assertTrue("host.submit must fire on v1-fallback input", host.submitCalls.get() >= 1)

    teardownServer(clientToServerOut, received, serverThread, exitLatch)
  }

  @Test(timeout = 30_000)
  fun cleanShutdown_closes_all_open_sessions() {
    val tmp = Files.createTempDirectory("interactive-session-shutdown").toFile()
    val aPng = File(tmp, "preview-A.png").apply { writeBytes(testPngBytes(seed = 0)) }
    val bPng = File(tmp, "preview-B.png").apply { writeBytes(testPngBytes(seed = 5)) }
    val host = SessionAwareFakeHost(aPng, mapOf("preview-A" to aPng, "preview-B" to bPng))

    val (_, serverThread, clientToServerOut, received, exitLatch) = bringUpServer(host)
    resourcesToClose.add(AutoCloseable { runCatching { clientToServerOut.close() } })

    handshake(clientToServerOut, received)

    // Open two streams targeting different previews — both sessions should still be alive
    // when shutdown fires.
    writeFrame(
      clientToServerOut,
      """{"jsonrpc":"2.0","id":1,"method":"interactive/start","params":{"previewId":"preview-A"}}""",
    )
    pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 1 }
    writeFrame(
      clientToServerOut,
      """{"jsonrpc":"2.0","id":2,"method":"interactive/start","params":{"previewId":"preview-B"}}""",
    )
    pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 2 }
    val openSessions = host.allSessions()
    assertEquals(2, openSessions.size)
    assertTrue("no session should be closed yet", openSessions.all { it.closeCount.get() == 0 })

    teardownServer(clientToServerOut, received, serverThread, exitLatch)

    // After cleanShutdown, both sessions must have been close()'d so the host can free per-session
    // native resources (the load-bearing reason this assertion exists).
    assertTrue(
      "all open sessions must be close()'d on cleanShutdown",
      openSessions.all { it.closeCount.get() == 1 },
    )
  }

  // ----- harness scaffolding -----

  private data class ServerHarness(
    val server: JsonRpcServer,
    val thread: Thread,
    val clientToServerOut: PipedOutputStream,
    val received: LinkedBlockingQueue<JsonObject>,
    val exitLatch: CountDownLatch,
  )

  private fun bringUpServer(host: RenderHost, interactiveFrameIntervalMs: Long = 0): ServerHarness {
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
        interactiveFrameIntervalMs = interactiveFrameIntervalMs,
        onExit = { _ -> exitLatch.countDown() },
      )
    val thread =
      Thread({ server.run() }, "interactive-session-plumbing-server").apply { isDaemon = true }
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
        "interactive-session-plumbing-reader",
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
 * Test [RenderHost] that owns an [InteractiveSession] per allocation. Each session records every
 * dispatch / render / close call into visible counters so the test can assert on the protocol-side
 * routing without exercising real Compose. The session reads PNG bytes off disk (per-preview map or
 * default) so the daemon's `hashFrameBytes` dedup path still runs end-to-end.
 */
private class SessionAwareFakeHost(
  private val defaultPng: File,
  private val perPreview: Map<String, File> = emptyMap(),
) : RenderHost {

  val acquireCalls = AtomicInteger(0)
  @Volatile var lastInspectionMode: Boolean? = null
  private val sessions = ConcurrentHashMap<Long, RecordingSession>()
  private val nextSessionKey = AtomicLong(1)

  override fun start() {}

  override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult {
    require(request is RenderRequest.Render)
    return RenderResult(
      id = request.id,
      classLoaderHashCode = 0,
      classLoaderName = "session-aware-fake",
      pngPath = defaultPng.absolutePath,
      metrics = mapOf("tookMs" to 0L),
    )
  }

  override fun shutdown(timeoutMs: Long) {}

  override val supportsInteractive: Boolean
    get() = true

  override fun acquireInteractiveSession(
    previewId: String,
    classLoader: ClassLoader,
    inspectionMode: Boolean?,
  ): InteractiveSession {
    acquireCalls.incrementAndGet()
    lastInspectionMode = inspectionMode
    val pngFile = perPreview[previewId] ?: defaultPng
    val session = RecordingSession(previewId = previewId, pngFile = pngFile)
    sessions[nextSessionKey.getAndIncrement()] = session
    return session
  }

  fun lastSession(): RecordingSession? = sessions.entries.maxByOrNull { it.key }?.value

  fun allSessions(): List<RecordingSession> = sessions.entries.sortedBy { it.key }.map { it.value }
}

private class RecordingSession(override val previewId: String, private val pngFile: File) :
  InteractiveSession {

  val dispatchCount = AtomicInteger(0)
  val renderCount = AtomicInteger(0)
  val closeCount = AtomicInteger(0)

  @Volatile private var lastClickX: Int? = null
  @Volatile private var lastClickY: Int? = null

  fun lastClick(): Pair<Int, Int>? = lastClickX?.let { x -> lastClickY?.let { y -> x to y } }

  override fun dispatch(input: InteractiveInputParams) {
    dispatchCount.incrementAndGet()
    lastClickX = input.pixelX
    lastClickY = input.pixelY
  }

  override fun render(requestId: Long): RenderResult {
    renderCount.incrementAndGet()
    return RenderResult(
      id = requestId,
      classLoaderHashCode = 0,
      classLoaderName = "recording-session",
      pngPath = pngFile.absolutePath,
      metrics = mapOf("tookMs" to 0L),
    )
  }

  override fun close() {
    closeCount.incrementAndGet()
  }
}

/**
 * Test [RenderHost] that does NOT support interactive sessions — `acquireInteractiveSession`
 * inherits the default no-op throw. Used to verify the v1 fallback path still works.
 */
private class NoSessionFakeHost(private val defaultPng: File) : RenderHost {

  val submitCalls = AtomicInteger(0)

  override fun start() {}

  override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult {
    require(request is RenderRequest.Render)
    submitCalls.incrementAndGet()
    return RenderResult(
      id = request.id,
      classLoaderHashCode = 0,
      classLoaderName = "no-session-fake",
      pngPath = defaultPng.absolutePath,
      metrics = mapOf("tookMs" to 0L),
    )
  }

  override fun shutdown(timeoutMs: Long) {}
}

private class FailingSessionHost(private val defaultPng: File) : RenderHost {
  override val supportsInteractive: Boolean
    get() = true

  override fun start() {}

  override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult {
    require(request is RenderRequest.Render)
    return RenderResult(
      id = request.id,
      classLoaderHashCode = 0,
      classLoaderName = "failing-session-fake",
      pngPath = defaultPng.absolutePath,
      metrics = mapOf("tookMs" to 0L),
    )
  }

  override fun shutdown(timeoutMs: Long) {}

  override fun acquireInteractiveSession(
    previewId: String,
    classLoader: ClassLoader,
    inspectionMode: Boolean?,
  ): InteractiveSession {
    throw IllegalStateException("boom")
  }
}
