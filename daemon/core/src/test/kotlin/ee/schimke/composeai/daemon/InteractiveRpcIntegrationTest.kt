package ee.schimke.composeai.daemon

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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the interactive (live-stream) RPC surface end-to-end against a [JsonRpcServer] driven
 * over piped streams, with a deterministic [BytesAwareFakeHost] that produces real PNG bytes on
 * disk so the daemon's hash-based dedup path actually fires.
 *
 * Covers — see docs/daemon/INTERACTIVE.md:
 * 1. `interactive/start` returns a `frameStreamId`.
 * 2. `interactive/input` (click) triggers a fresh `renderFinished` for the target preview.
 * 3. Frame dedup: an input that re-renders byte-identical bytes emits `unchanged: true`.
 * 4. `interactive/stop` invalidates the stream id, so a subsequent `interactive/input` against the
 *    stale id is dropped (no new render).
 * 5. Multi-target: two concurrent `interactive/start` calls coexist, inputs route by stream id, and
 *    `stop` on one stream leaves the other live.
 */
class InteractiveRpcIntegrationTest {

  private val json = Json { ignoreUnknownKeys = true }
  private val resourcesToClose = mutableListOf<AutoCloseable>()

  @After
  fun teardown() {
    resourcesToClose.reversed().forEach { runCatching { it.close() } }
  }

  @Test(timeout = 30_000)
  fun click_input_triggers_renderFinished_then_dedups_identical_frame() {
    val tmp = Files.createTempDirectory("interactive-rpc-test").toFile()
    val pngFile = File(tmp, "preview-A.png").apply { writeBytes(testPngBytes(seed = 0)) }

    // Per render, each call to host.submit returns the bytes the test currently has staged
    // on disk. We swap the bytes between calls to drive the dedup branches: identical bytes
    // → `unchanged: true`; different bytes → fresh frame.
    val host = BytesAwareFakeHost(pngFile)

    val (server, serverThread, clientToServerOut, received, exitLatch) = bringUpServer(host)
    resourcesToClose.add(AutoCloseable { runCatching { clientToServerOut.close() } })

    handshake(clientToServerOut, received)

    // 1. interactive/start — returns a frameStreamId.
    writeFrame(
      clientToServerOut,
      """{"jsonrpc":"2.0","id":10,"method":"interactive/start","params":{"previewId":"preview-A"}}""",
    )
    val startResp = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 10 }
    assertNotNull("interactive/start response should arrive", startResp)
    val streamId = startResp!!["result"]!!.jsonObject["frameStreamId"]!!.jsonPrimitive.contentOrNull
    assertNotNull("interactive/start must return a non-null frameStreamId", streamId)
    assertTrue("frameStreamId should be opaque-ish; got '$streamId'", streamId!!.isNotBlank())

    // 2. First input — render fires, renderFinished arrives WITHOUT `unchanged`. The first
    //    interactive frame after start always paints (we wipe the prior hash on start).
    writeFrame(
      clientToServerOut,
      """
      {"jsonrpc":"2.0","method":"interactive/input","params":{
        "frameStreamId":"$streamId","kind":"click","pixelX":10,"pixelY":20
      }}
      """
        .trimIndent(),
    )
    val firstFinished =
      pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "renderFinished" }
    assertNotNull("first interactive renderFinished should arrive", firstFinished)
    val firstParams = firstFinished!!["params"]!!.jsonObject
    assertEquals("preview-A", firstParams["id"]?.jsonPrimitive?.contentOrNull)
    assertNull(
      "first interactive frame must NOT carry unchanged=true",
      firstParams["unchanged"]?.jsonPrimitive?.boolean,
    )

    // 3. Second input — identical PNG bytes on disk → daemon dedups.
    writeFrame(
      clientToServerOut,
      """
      {"jsonrpc":"2.0","method":"interactive/input","params":{
        "frameStreamId":"$streamId","kind":"click","pixelX":11,"pixelY":21
      }}
      """
        .trimIndent(),
    )
    val secondFinished =
      pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "renderFinished" }
    assertNotNull("second interactive renderFinished should arrive", secondFinished)
    val secondParams = secondFinished!!["params"]!!.jsonObject
    assertEquals(
      "byte-identical follow-up must signal unchanged=true",
      true,
      secondParams["unchanged"]?.jsonPrimitive?.boolean,
    )

    // 4. Swap bytes; next input should produce a fresh (NOT unchanged) frame.
    pngFile.writeBytes(testPngBytes(seed = 1))
    writeFrame(
      clientToServerOut,
      """
      {"jsonrpc":"2.0","method":"interactive/input","params":{
        "frameStreamId":"$streamId","kind":"click","pixelX":12,"pixelY":22
      }}
      """
        .trimIndent(),
    )
    val thirdFinished =
      pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "renderFinished" }
    assertNotNull("third interactive renderFinished should arrive", thirdFinished)
    val thirdParams = thirdFinished!!["params"]!!.jsonObject
    assertNull(
      "fresh bytes must NOT signal unchanged=true",
      thirdParams["unchanged"]?.jsonPrimitive?.boolean,
    )

    // 5. interactive/stop — subsequent input against the stale stream id is dropped (no
    //    new renderFinished within the wait window). We assert the absence by waiting a
    //    short window AND counting renderFinished notifications: if a fourth one shows
    //    up the test fails.
    writeFrame(
      clientToServerOut,
      """{"jsonrpc":"2.0","method":"interactive/stop","params":{"frameStreamId":"$streamId"}}""",
    )
    // Tiny settle so the stop notification is processed before the stale input.
    Thread.sleep(50)
    writeFrame(
      clientToServerOut,
      """
      {"jsonrpc":"2.0","method":"interactive/input","params":{
        "frameStreamId":"$streamId","kind":"click","pixelX":13,"pixelY":23
      }}
      """
        .trimIndent(),
    )
    // Drain anything queued — should not see a new renderFinished. We wait long enough for
    // a render to ride through (the FakeHost completes synchronously) and assert the queue
    // contains nothing matching.
    val staleFollowup =
      pollUntil(received, timeoutMs = 1000) {
        it["method"]?.jsonPrimitive?.contentOrNull == "renderFinished"
      }
    assertNull("stale interactive/input must not produce a renderFinished", staleFollowup)

    teardownServer(clientToServerOut, received, serverThread, exitLatch)
  }

  @Test(timeout = 30_000)
  fun start_with_blank_previewId_rejects_with_invalid_params() {
    val tmp = Files.createTempDirectory("interactive-rpc-blank-test").toFile()
    val pngFile = File(tmp, "preview-A.png").apply { writeBytes(testPngBytes(seed = 0)) }
    val host = BytesAwareFakeHost(pngFile)

    val (_, serverThread, clientToServerOut, received, exitLatch) = bringUpServer(host)
    resourcesToClose.add(AutoCloseable { runCatching { clientToServerOut.close() } })

    handshake(clientToServerOut, received)

    writeFrame(
      clientToServerOut,
      """{"jsonrpc":"2.0","id":42,"method":"interactive/start","params":{"previewId":""}}""",
    )
    val resp = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 42 }
    assertNotNull(resp)
    val err = resp!!["error"]?.jsonObject
    assertNotNull("blank previewId should produce an error response", err)
    assertEquals(
      "blank previewId should yield ERR_INVALID_PARAMS",
      -32602,
      err!!["code"]?.jsonPrimitive?.intOrNull,
    )

    teardownServer(clientToServerOut, received, serverThread, exitLatch)
  }

  /**
   * Multi-target invariant — INTERACTIVE.md § 8. Two `interactive/start` calls coexist as
   * independent streams; each input routes to its stream's preview; a `stop` on one stream leaves
   * the other untouched.
   *
   * The panel UI today only exercises one stream at a time, but the daemon protocol supports
   * multi-target so a future programmatic client (side-by-side comparison view, CI agent driving
   * multiple previews) can drive concurrent streams without a wire change.
   */
  @Test(timeout = 30_000)
  fun multiple_concurrent_streams_route_inputs_independently() {
    val tmp = Files.createTempDirectory("interactive-rpc-multi-test").toFile()
    val aPng = File(tmp, "preview-A.png").apply { writeBytes(testPngBytes(seed = 0)) }
    val bPng = File(tmp, "preview-B.png").apply { writeBytes(testPngBytes(seed = 5)) }
    val host = BytesAwareFakeHost(aPng, mapOf("preview-A" to aPng, "preview-B" to bPng))

    val (_, serverThread, clientToServerOut, received, exitLatch) = bringUpServer(host)
    resourcesToClose.add(AutoCloseable { runCatching { clientToServerOut.close() } })

    handshake(clientToServerOut, received)

    // Start on A, then on B — both streams must coexist (multi-target).
    writeFrame(
      clientToServerOut,
      """{"jsonrpc":"2.0","id":1,"method":"interactive/start","params":{"previewId":"preview-A"}}""",
    )
    val a = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 1 }!!
    val aStream = a["result"]!!.jsonObject["frameStreamId"]!!.jsonPrimitive.contentOrNull!!
    writeFrame(
      clientToServerOut,
      """{"jsonrpc":"2.0","id":2,"method":"interactive/start","params":{"previewId":"preview-B"}}""",
    )
    val b = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 2 }!!
    val bStream = b["result"]!!.jsonObject["frameStreamId"]!!.jsonPrimitive.contentOrNull!!
    assertFalse("each start must yield a unique stream id", aStream == bStream)

    // Input on stream A → renders preview-A.
    writeFrame(
      clientToServerOut,
      """
      {"jsonrpc":"2.0","method":"interactive/input","params":{
        "frameStreamId":"$aStream","kind":"click","pixelX":1,"pixelY":1
      }}
      """
        .trimIndent(),
    )
    val firstFinished =
      pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "renderFinished" }
    assertNotNull("input on stream A must render", firstFinished)
    assertEquals(
      "stream A's input must render preview-A",
      "preview-A",
      firstFinished!!["params"]!!.jsonObject["id"]?.jsonPrimitive?.contentOrNull,
    )

    // Input on stream B → renders preview-B (proves both streams are alive).
    writeFrame(
      clientToServerOut,
      """
      {"jsonrpc":"2.0","method":"interactive/input","params":{
        "frameStreamId":"$bStream","kind":"click","pixelX":1,"pixelY":1
      }}
      """
        .trimIndent(),
    )
    val secondFinished =
      pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "renderFinished" }
    assertNotNull("input on stream B must render", secondFinished)
    assertEquals(
      "stream B's input must render preview-B",
      "preview-B",
      secondFinished!!["params"]!!.jsonObject["id"]?.jsonPrimitive?.contentOrNull,
    )

    // Stop stream A → stream B is untouched.
    writeFrame(
      clientToServerOut,
      """{"jsonrpc":"2.0","method":"interactive/stop","params":{"frameStreamId":"$aStream"}}""",
    )
    Thread.sleep(50) // settle the stop notification before the next input

    // Stale input against stopped stream A → dropped.
    writeFrame(
      clientToServerOut,
      """
      {"jsonrpc":"2.0","method":"interactive/input","params":{
        "frameStreamId":"$aStream","kind":"click","pixelX":2,"pixelY":2
      }}
      """
        .trimIndent(),
    )
    val staleAFollowup =
      pollUntil(received, timeoutMs = 500) {
        it["method"]?.jsonPrimitive?.contentOrNull == "renderFinished"
      }
    assertNull("input against stopped stream A must not render", staleAFollowup)

    // Fresh input on stream B → still renders preview-B (untouched by A's stop).
    writeFrame(
      clientToServerOut,
      """
      {"jsonrpc":"2.0","method":"interactive/input","params":{
        "frameStreamId":"$bStream","kind":"click","pixelX":3,"pixelY":3
      }}
      """
        .trimIndent(),
    )
    val bAfterAStopped =
      pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "renderFinished" }
    assertNotNull("stream B must keep rendering after stream A is stopped", bAfterAStopped)
    assertEquals(
      "preview-B",
      bAfterAStopped!!["params"]!!.jsonObject["id"]?.jsonPrimitive?.contentOrNull,
    )

    teardownServer(clientToServerOut, received, serverThread, exitLatch)
  }

  // ----- helpers -----

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
        interactiveFrameIntervalMs = 0,
        onExit = { _ -> exitLatch.countDown() },
      )
    val thread = Thread({ server.run() }, "interactive-rpc-server").apply { isDaemon = true }
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
        "interactive-rpc-reader",
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
    assertNotNull("initialize response should arrive", init)
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

  /**
   * Synthesises deterministic PNG-shaped bytes. We intentionally do NOT emit a real PNG — the
   * daemon's dedup hashes raw bytes, not decoded pixels, so any byte-stable payload is sufficient.
   * Different [seed]s produce different bytes; identical [seed]s produce identical bytes.
   */
  private fun testPngBytes(seed: Int): ByteArray {
    // 8-byte PNG signature + 16 bytes payload keyed by seed. Doesn't need to be a valid
    // PNG for the dedup path; the daemon does not decode the bytes.
    val sig = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    val payload = ByteArray(16) { i -> ((i + seed * 13) and 0xFF).toByte() }
    return sig + payload
  }
}

/**
 * Test-only [RenderHost] that returns a real on-disk PNG path on every render so the daemon's
 * `hashFrameBytes` path actually runs. Driven by a single [defaultPng] file when the manifest is
 * unset, or by a per-previewId map otherwise. The test mutates the file's bytes between renders to
 * drive the dedup-hit / dedup-miss branches.
 */
private class BytesAwareFakeHost(
  private val defaultPng: File,
  private val perPreview: Map<String, File> = emptyMap(),
) : RenderHost {

  private val queue = LinkedBlockingQueue<RenderRequest>()

  @Volatile private var stopped = false
  val interruptCount = AtomicInteger(0)

  private val results = ConcurrentHashMap<Long, LinkedBlockingQueue<RenderResult>>()

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
                val previewId =
                  if (req.payload.startsWith("previewId=")) req.payload.removePrefix("previewId=")
                  else ""
                val pngFile = perPreview[previewId] ?: defaultPng
                val result =
                  RenderResult(
                    id = req.id,
                    classLoaderHashCode = 0,
                    classLoaderName = "fake",
                    pngPath = pngFile.absolutePath,
                    metrics = mapOf("tookMs" to 1L),
                  )
                results.computeIfAbsent(req.id) { LinkedBlockingQueue() }.put(result)
              }
              RenderRequest.Shutdown -> return@Thread
            }
          }
        },
        "bytes-aware-fake-host",
      )
      .apply { isDaemon = true }

  override fun start() {
    worker.start()
  }

  override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult {
    require(request is RenderRequest.Render)
    queue.put(request)
    val q = results.computeIfAbsent(request.id) { LinkedBlockingQueue() }
    return q.poll(timeoutMs, TimeUnit.MILLISECONDS)
      ?: error("BytesAwareFakeHost.submit($request) timed out")
  }

  override fun shutdown(timeoutMs: Long) {
    stopped = true
    queue.put(RenderRequest.Shutdown)
    worker.join(timeoutMs)
  }
}
