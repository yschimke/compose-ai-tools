package ee.schimke.composeai.daemon

import ee.schimke.composeai.renderer.xr.client.StreamFrame as XrStreamFrame
import ee.schimke.composeai.renderer.xr.client.XrRenderServerFactory
import ee.schimke.composeai.renderer.xr.client.XrRenderServerHandle
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end RPC tests for the `composestream/1` surface — `stream/start`, `stream/stop`,
 * `stream/visibility`, `streamFrame`. Mirrors the [InteractiveRpcIntegrationTest] harness structure
 * (piped streams, deterministic byte-aware FakeHost) so we cover the same regressions the live UI
 * hits in production.
 *
 * Covers:
 * 1. `stream/start` returns a `frameStreamId` plus a chosen codec; the first input pushes a
 *    `streamFrame` carrying the bytes inline (base64) with seq=1, codec=png, keyframe=true.
 * 2. Byte-identical follow-up frames downgrade to a heartbeat (`codec` and `payloadBase64` both
 *    null on the wire).
 * 3. `stream/stop` is idempotent and emits a `final=true` marker so the client can release decoder
 *    state.
 * 4. `stream/visibility` flipping to `visible:false` throttles emission; flipping back to
 *    `visible:true` marks the next emitted frame as a `keyframe`.
 * 5. Multi-stream: two concurrent `stream/start`s targeting the same preview both receive
 *    independently-sequenced frames per render.
 */
class StreamRpcIntegrationTest {

  private val json = Json { ignoreUnknownKeys = true }
  private val resourcesToClose = mutableListOf<AutoCloseable>()

  @After
  fun teardown() {
    resourcesToClose.reversed().forEach { runCatching { it.close() } }
  }

  @Test(timeout = 30_000)
  fun start_then_input_emits_streamFrame_with_payload_seq_and_keyframe() {
    val tmp = Files.createTempDirectory("stream-rpc-test").toFile()
    val pngFile = File(tmp, "preview-A.png").apply { writeBytes(testPngBytes(seed = 0)) }
    val host = StreamRpcFakeHost(pngFile)

    val (_, serverThread, out, received, exitLatch) = bringUpServer(host)
    resourcesToClose.add(AutoCloseable { runCatching { out.close() } })
    handshake(out, received)

    writeFrame(
      out,
      """{"jsonrpc":"2.0","id":10,"method":"stream/start","params":{"previewId":"preview-A"}}""",
    )
    val resp = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 10 }!!
    val streamId = resp["result"]!!.jsonObject["frameStreamId"]!!.jsonPrimitive.contentOrNull!!
    assertEquals(
      "default-negotiated codec must be png since the daemon's only encoder is PNG",
      "png",
      resp["result"]!!.jsonObject["codec"]?.jsonPrimitive?.contentOrNull,
    )

    // Drive a frame via interactive/input — the streaming layer rides on top of the same held
    // session, so a single input produces one renderFinished + one streamFrame.
    writeFrame(
      out,
      """{"jsonrpc":"2.0","method":"interactive/input","params":{
              "frameStreamId":"$streamId","kind":"click","pixelX":1,"pixelY":1}}""",
    )
    val frame = pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "streamFrame" }
    assertNotNull("first input must produce a streamFrame", frame)
    val params = frame!!["params"]!!.jsonObject
    assertEquals(streamId, params["frameStreamId"]?.jsonPrimitive?.contentOrNull)
    assertEquals(1L, params["seq"]?.jsonPrimitive?.longOrNull)
    assertEquals("png", params["codec"]?.jsonPrimitive?.contentOrNull)
    assertEquals(true, params["keyframe"]?.jsonPrimitive?.boolean)
    assertNotNull(
      "first frame must carry inline payload (no on-disk path reuse)",
      params["payloadBase64"]?.jsonPrimitive?.contentOrNull,
    )

    teardownServer(out, received, serverThread, exitLatch)
  }

  @Test(timeout = 30_000)
  fun byte_identical_followup_emits_unchanged_heartbeat() {
    val tmp = Files.createTempDirectory("stream-rpc-test").toFile()
    val pngFile = File(tmp, "preview-A.png").apply { writeBytes(testPngBytes(seed = 0)) }
    val host = StreamRpcFakeHost(pngFile)

    val (_, serverThread, out, received, exitLatch) = bringUpServer(host)
    resourcesToClose.add(AutoCloseable { runCatching { out.close() } })
    handshake(out, received)

    writeFrame(
      out,
      """{"jsonrpc":"2.0","id":1,"method":"stream/start","params":{"previewId":"preview-A"}}""",
    )
    val streamId =
      pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 1 }!!["result"]!!
        .jsonObject["frameStreamId"]!!
        .jsonPrimitive
        .contentOrNull!!

    // First input — fresh frame.
    writeFrame(
      out,
      """{"jsonrpc":"2.0","method":"interactive/input","params":{
              "frameStreamId":"$streamId","kind":"click","pixelX":1,"pixelY":1}}""",
    )
    val first =
      pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "streamFrame" }!!
    assertNotNull(first["params"]!!.jsonObject["payloadBase64"]?.jsonPrimitive?.contentOrNull)

    // Same bytes on disk → heartbeat.
    writeFrame(
      out,
      """{"jsonrpc":"2.0","method":"interactive/input","params":{
              "frameStreamId":"$streamId","kind":"click","pixelX":2,"pixelY":2}}""",
    )
    val second =
      pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "streamFrame" }!!
    val secondParams = second["params"]!!.jsonObject
    assertEquals(2L, secondParams["seq"]?.jsonPrimitive?.longOrNull)
    assertNull(
      "byte-identical followup must downgrade to heartbeat (codec=null)",
      secondParams["codec"]?.jsonPrimitive?.contentOrNull,
    )
    assertNull(
      "byte-identical followup must drop the payload",
      secondParams["payloadBase64"]?.jsonPrimitive?.contentOrNull,
    )

    teardownServer(out, received, serverThread, exitLatch)
  }

  @Test(timeout = 30_000)
  fun stop_emits_final_marker_and_drops_followups() {
    val tmp = Files.createTempDirectory("stream-rpc-test").toFile()
    val pngFile = File(tmp, "preview-A.png").apply { writeBytes(testPngBytes(seed = 0)) }
    val host = StreamRpcFakeHost(pngFile)

    val (_, serverThread, out, received, exitLatch) = bringUpServer(host)
    resourcesToClose.add(AutoCloseable { runCatching { out.close() } })
    handshake(out, received)

    writeFrame(
      out,
      """{"jsonrpc":"2.0","id":1,"method":"stream/start","params":{"previewId":"preview-A"}}""",
    )
    val streamId =
      pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 1 }!!["result"]!!
        .jsonObject["frameStreamId"]!!
        .jsonPrimitive
        .contentOrNull!!

    // Drive one frame to make the registry non-trivial before stop.
    writeFrame(
      out,
      """{"jsonrpc":"2.0","method":"interactive/input","params":{
              "frameStreamId":"$streamId","kind":"click","pixelX":1,"pixelY":1}}""",
    )
    pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "streamFrame" }

    writeFrame(
      out,
      """{"jsonrpc":"2.0","method":"stream/stop","params":{"frameStreamId":"$streamId"}}""",
    )
    val finalFrame =
      pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "streamFrame" }!!
    val finalParams = finalFrame["params"]!!.jsonObject
    assertEquals(true, finalParams["final"]?.jsonPrimitive?.boolean)
    assertEquals(streamId, finalParams["frameStreamId"]?.jsonPrimitive?.contentOrNull)

    // Settle so the stop is processed before the stale input arrives.
    Thread.sleep(50)

    writeFrame(
      out,
      """{"jsonrpc":"2.0","method":"interactive/input","params":{
              "frameStreamId":"$streamId","kind":"click","pixelX":2,"pixelY":2}}""",
    )
    val stale =
      pollUntil(received, timeoutMs = 800) {
        it["method"]?.jsonPrimitive?.contentOrNull == "streamFrame"
      }
    assertNull("stale input against stopped stream must not produce a streamFrame", stale)

    teardownServer(out, received, serverThread, exitLatch)
  }

  @Test(timeout = 30_000)
  fun visibility_returning_true_marks_next_frame_keyframe() {
    val tmp = Files.createTempDirectory("stream-rpc-test").toFile()
    val pngFile = File(tmp, "preview-A.png").apply { writeBytes(testPngBytes(seed = 0)) }
    val host = StreamRpcFakeHost(pngFile)

    val (_, serverThread, out, received, exitLatch) = bringUpServer(host)
    resourcesToClose.add(AutoCloseable { runCatching { out.close() } })
    handshake(out, received)

    writeFrame(
      out,
      """{"jsonrpc":"2.0","id":1,"method":"stream/start","params":{"previewId":"preview-A"}}""",
    )
    val streamId =
      pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 1 }!!["result"]!!
        .jsonObject["frameStreamId"]!!
        .jsonPrimitive
        .contentOrNull!!

    // First frame: keyframe=true (start anchor).
    writeFrame(
      out,
      """{"jsonrpc":"2.0","method":"interactive/input","params":{
              "frameStreamId":"$streamId","kind":"click","pixelX":1,"pixelY":1}}""",
    )
    val first =
      pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "streamFrame" }!!
    assertEquals(true, first["params"]!!.jsonObject["keyframe"]?.jsonPrimitive?.boolean)

    // Toggle visibility off → on, mutate bytes, then drive another input. The next emitted frame
    // must be flagged keyframe=true (scroll-back-into-view anchor).
    writeFrame(
      out,
      """{"jsonrpc":"2.0","method":"stream/visibility","params":{"frameStreamId":"$streamId","visible":false}}""",
    )
    writeFrame(
      out,
      """{"jsonrpc":"2.0","method":"stream/visibility","params":{"frameStreamId":"$streamId","visible":true}}""",
    )
    Thread.sleep(50)
    pngFile.writeBytes(testPngBytes(seed = 7))

    writeFrame(
      out,
      """{"jsonrpc":"2.0","method":"interactive/input","params":{
              "frameStreamId":"$streamId","kind":"click","pixelX":2,"pixelY":2}}""",
    )
    val onScrollBack =
      pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "streamFrame" }!!
    val params = onScrollBack["params"]!!.jsonObject
    assertEquals(true, params["keyframe"]?.jsonPrimitive?.boolean)
    assertNotNull(
      "scroll-back-into-view frame must carry payload bytes",
      params["payloadBase64"]?.jsonPrimitive?.contentOrNull,
    )

    teardownServer(out, received, serverThread, exitLatch)
  }

  @Test(timeout = 30_000)
  fun multi_stream_same_preview_emits_per_stream_frames() {
    val tmp = Files.createTempDirectory("stream-rpc-test").toFile()
    val pngFile = File(tmp, "preview-A.png").apply { writeBytes(testPngBytes(seed = 0)) }
    val host = StreamRpcFakeHost(pngFile)

    val (_, serverThread, out, received, exitLatch) = bringUpServer(host)
    resourcesToClose.add(AutoCloseable { runCatching { out.close() } })
    handshake(out, received)

    writeFrame(
      out,
      """{"jsonrpc":"2.0","id":1,"method":"stream/start","params":{"previewId":"preview-A"}}""",
    )
    val s1 =
      pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 1 }!!["result"]!!
        .jsonObject["frameStreamId"]!!
        .jsonPrimitive
        .contentOrNull!!
    writeFrame(
      out,
      """{"jsonrpc":"2.0","id":2,"method":"stream/start","params":{"previewId":"preview-A"}}""",
    )
    val s2 =
      pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 2 }!!["result"]!!
        .jsonObject["frameStreamId"]!!
        .jsonPrimitive
        .contentOrNull!!
    assertFalse(s1 == s2)

    writeFrame(
      out,
      """{"jsonrpc":"2.0","method":"interactive/input","params":{
              "frameStreamId":"$s1","kind":"click","pixelX":1,"pixelY":1}}""",
    )

    // Collect two streamFrame notifications (one per stream) for this single render.
    val collected = mutableMapOf<String, JsonObject>()
    val deadline = System.currentTimeMillis() + 5_000
    while (collected.size < 2 && System.currentTimeMillis() < deadline) {
      val msg =
        received.poll(
          (deadline - System.currentTimeMillis()).coerceAtLeast(0),
          TimeUnit.MILLISECONDS,
        ) ?: break
      if (msg["method"]?.jsonPrimitive?.contentOrNull != "streamFrame") continue
      val params = msg["params"]!!.jsonObject
      val sid = params["frameStreamId"]?.jsonPrimitive?.contentOrNull ?: continue
      if (sid in collected) continue
      collected[sid] = params
    }
    assertEquals("both streams must emit one frame each", 2, collected.size)
    assertNotNull(collected[s1])
    assertNotNull(collected[s2])
    // Each first frame is a keyframe with payload.
    assertEquals(true, collected[s1]!!["keyframe"]?.jsonPrimitive?.boolean)
    assertEquals(true, collected[s2]!!["keyframe"]?.jsonPrimitive?.boolean)
    assertNotNull(collected[s1]!!["payloadBase64"]?.jsonPrimitive?.contentOrNull)
    assertNotNull(collected[s2]!!["payloadBase64"]?.jsonPrimitive?.contentOrNull)

    teardownServer(out, received, serverThread, exitLatch)
  }

  /**
   * Regression for PR #847 reviewer P2 — `handleStreamStart` always populates `interactiveTargets`
   * (so the v1 fallback path can route inputs), but `handleStreamStop` previously only cleared it
   * inside `if (session != null)`. On the v1-fallback path that left a stale target entry behind,
   * so a stale `interactive/input` after `stream/stop` would still trigger a fresh render. We
   * assert that no `renderFinished` (and no `streamFrame`) arrives for the stale input — the
   * routing entry must be gone.
   */
  @Test(timeout = 30_000)
  fun stop_drops_routing_entry_on_v1_fallback_path() {
    val tmp = Files.createTempDirectory("stream-rpc-test").toFile()
    val pngFile = File(tmp, "preview-A.png").apply { writeBytes(testPngBytes(seed = 0)) }
    // StreamRpcFakeHost doesn't override acquireInteractiveSession → the daemon falls
    // back to the v1 stateless path. That's the case the bug hit.
    val host = StreamRpcFakeHost(pngFile)

    val (_, serverThread, out, received, exitLatch) = bringUpServer(host)
    resourcesToClose.add(AutoCloseable { runCatching { out.close() } })
    handshake(out, received)

    writeFrame(
      out,
      """{"jsonrpc":"2.0","id":1,"method":"stream/start","params":{"previewId":"preview-A"}}""",
    )
    val startResp = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 1 }!!
    val streamId = startResp["result"]!!.jsonObject["frameStreamId"]!!.jsonPrimitive.contentOrNull!!
    assertEquals(
      "this regression test depends on the v1 fallback being exercised",
      false,
      startResp["result"]!!.jsonObject["heldSession"]?.jsonPrimitive?.boolean,
    )

    writeFrame(
      out,
      """{"jsonrpc":"2.0","method":"stream/stop","params":{"frameStreamId":"$streamId"}}""",
    )
    // Settle so the stop is processed before the stale input.
    Thread.sleep(50)

    writeFrame(
      out,
      """{"jsonrpc":"2.0","method":"interactive/input","params":{
              "frameStreamId":"$streamId","kind":"click","pixelX":1,"pixelY":1}}""",
    )

    // Without the fix, the v1-fallback path would mint a fresh hostId and emit
    // `renderFinished` for the stale input. With the fix, `interactiveTargets` was
    // cleared in handleStreamStop, so handleInteractiveInput drops the input on
    // the floor.
    val staleRender =
      pollUntil(received, timeoutMs = 800) {
        it["method"]?.jsonPrimitive?.contentOrNull == "renderFinished"
      }
    assertNull(
      "stale input after stream/stop on v1 fallback path must not trigger a fresh render",
      staleRender,
    )

    teardownServer(out, received, serverThread, exitLatch)
  }

  @Test(timeout = 30_000)
  fun start_with_blank_previewId_yields_invalid_params() {
    val tmp = Files.createTempDirectory("stream-rpc-test").toFile()
    val pngFile = File(tmp, "preview-A.png").apply { writeBytes(testPngBytes(seed = 0)) }
    val host = StreamRpcFakeHost(pngFile)

    val (_, serverThread, out, received, exitLatch) = bringUpServer(host)
    resourcesToClose.add(AutoCloseable { runCatching { out.close() } })
    handshake(out, received)

    writeFrame(
      out,
      """{"jsonrpc":"2.0","id":42,"method":"stream/start","params":{"previewId":""}}""",
    )
    val resp = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 42 }!!
    val err = resp["error"]?.jsonObject
    assertNotNull("blank previewId should yield an error response", err)
    assertEquals(-32602, err!!["code"]?.jsonPrimitive?.intOrNull)

    teardownServer(out, received, serverThread, exitLatch)
  }

  @Test(timeout = 30_000)
  fun start_with_non_positive_maxFps_yields_invalid_params() {
    val tmp = Files.createTempDirectory("stream-rpc-test").toFile()
    val pngFile = File(tmp, "preview-A.png").apply { writeBytes(testPngBytes(seed = 0)) }
    val host = StreamRpcFakeHost(pngFile)

    val (_, serverThread, out, received, exitLatch) = bringUpServer(host)
    resourcesToClose.add(AutoCloseable { runCatching { out.close() } })
    handshake(out, received)

    writeFrame(
      out,
      """{"jsonrpc":"2.0","id":7,"method":"stream/start","params":{"previewId":"preview-A","maxFps":0}}""",
    )
    val resp = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 7 }!!
    val err = resp["error"]?.jsonObject
    assertNotNull(err)
    assertEquals(-32602, err!!["code"]?.jsonPrimitive?.intOrNull)

    teardownServer(out, received, serverThread, exitLatch)
  }

  // ----- helpers (deliberately copy-paste from InteractiveRpcIntegrationTest so this file
  //       can stand alone — the helper code is small and shared shapes drift more often than
  //       you'd think when one half evolves.) -----

  private data class ServerHarness(
    val server: JsonRpcServer,
    val thread: Thread,
    val clientToServerOut: PipedOutputStream,
    val received: LinkedBlockingQueue<JsonObject>,
    val exitLatch: CountDownLatch,
  )

  private fun bringUpServer(
    host: RenderHost,
    xrServerFactory: XrRenderServerFactory? = null,
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
        interactiveFrameIntervalMs = 0,
        onExit = { _ -> exitLatch.countDown() },
        xrServerFactory = xrServerFactory,
      )
    val thread = Thread({ server.run() }, "stream-rpc-server").apply { isDaemon = true }
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
        "stream-rpc-reader",
      )
      .apply { isDaemon = true }
      .start()

    return ServerHarness(server, thread, clientToServerOut, received, exitLatch)
  }

  private fun handshake(out: PipedOutputStream, received: LinkedBlockingQueue<JsonObject>) {
    writeFrame(
      out,
      """{"jsonrpc":"2.0","id":1000,"method":"initialize","params":{
            "protocolVersion":2,"clientVersion":"test","workspaceRoot":"/tmp",
            "moduleId":":test","moduleProjectDir":"/tmp",
            "capabilities":{"visibility":true,"metrics":false}}}""",
    )
    val init = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 1000 }
    assertNotNull("initialize response should arrive", init)
    writeFrame(out, """{"jsonrpc":"2.0","method":"initialized","params":{}}""")
  }

  private fun teardownServer(
    out: PipedOutputStream,
    received: LinkedBlockingQueue<JsonObject>,
    thread: Thread,
    exitLatch: CountDownLatch,
  ) {
    writeFrame(out, """{"jsonrpc":"2.0","id":9999,"method":"shutdown"}""")
    pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 9999 }
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

  // ---- XR render service (xr/start, xr/updatePanels, xr/stop) -------------------------------

  @Test(timeout = 30_000)
  fun xrStart_then_updatePanels_emit_streamFrames_and_stop_closes() {
    val tmp = Files.createTempDirectory("xr-rpc-test").toFile()
    val pngFile = File(tmp, "preview-A.png").apply { writeBytes(testPngBytes(seed = 0)) }
    val factory = FakeXrFactory()
    val (_, serverThread, out, received, exitLatch) =
      bringUpServer(StreamRpcFakeHost(pngFile), factory)
    resourcesToClose.add(AutoCloseable { runCatching { out.close() } })
    handshake(out, received)

    writeFrame(
      out,
      """{"jsonrpc":"2.0","id":20,"method":"xr/start","params":{
            "previewId":"p","sceneDir":".",
            "scene":{"version":1,"units":"dp","camera":{"kind":"orbit"},"panels":[]}}}""",
    )
    val resp = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 20 }
    assertNotNull("xr/start must respond", resp)
    val streamId = resp!!["result"]!!.jsonObject["frameStreamId"]!!.jsonPrimitive.contentOrNull!!

    val f1 = pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "streamFrame" }
    assertNotNull("xr/start must push the first frame", f1)
    assertEquals(
      streamId,
      f1!!["params"]!!.jsonObject["frameStreamId"]?.jsonPrimitive?.contentOrNull,
    )
    assertEquals(1L, f1["params"]!!.jsonObject["seq"]?.jsonPrimitive?.longOrNull)
    assertNotNull(f1["params"]!!.jsonObject["payloadBase64"]?.jsonPrimitive?.contentOrNull)

    writeFrame(
      out,
      """{"jsonrpc":"2.0","method":"xr/updatePanels","params":{
            "frameStreamId":"$streamId","panels":[{"id":"top"}]}}""",
    )
    val f2 =
      pollUntil(received) {
        it["method"]?.jsonPrimitive?.contentOrNull == "streamFrame" &&
          it["params"]!!.jsonObject["seq"]?.jsonPrimitive?.longOrNull == 2L
      }
    assertNotNull("xr/updatePanels must push a fresh frame (seq=2)", f2)

    writeFrame(
      out,
      """{"jsonrpc":"2.0","method":"xr/stop","params":{"frameStreamId":"$streamId"}}""",
    )
    teardownServer(out, received, serverThread, exitLatch)
    assertTrue("the native session must be closed", factory.created.single().closed)
  }

  @Test(timeout = 30_000)
  fun xrStart_replies_methodNotFound_when_xr_unavailable() {
    val tmp = Files.createTempDirectory("xr-rpc-test").toFile()
    val pngFile = File(tmp, "preview-A.png").apply { writeBytes(testPngBytes(seed = 0)) }
    // No xrServerFactory → capability off, methods rejected.
    val (_, serverThread, out, received, exitLatch) = bringUpServer(StreamRpcFakeHost(pngFile))
    resourcesToClose.add(AutoCloseable { runCatching { out.close() } })
    handshake(out, received)

    writeFrame(
      out,
      """{"jsonrpc":"2.0","id":21,"method":"xr/start","params":{
            "previewId":"p","scene":{"version":1,"units":"dp","camera":{"kind":"orbit"},"panels":[]}}}""",
    )
    val resp = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 21 }
    assertNotNull("xr/start must respond", resp)
    assertNotNull("xr/start must error when XR is unavailable", resp!!["error"])
    teardownServer(out, received, serverThread, exitLatch)
  }

  private fun testPngBytes(seed: Int): ByteArray {
    val sig = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    val payload = ByteArray(16) { i -> ((i + seed * 13) and 0xFF).toByte() }
    return sig + payload
  }
}

/** Fake native XR render server — returns monotonically-sequenced frames, tracks close. */
private class FakeXrHandle : XrRenderServerHandle {
  private val seq = AtomicInteger(0)
  @Volatile var closed = false
  override val capabilities = buildJsonObject {}

  private fun frame() = XrStreamFrame(seq.incrementAndGet().toLong(), 64, 48, "png", "ZGF0YQ==")

  override fun render(
    scene: kotlinx.serialization.json.JsonElement,
    sceneDir: String?,
    environment: String?,
  ) = frame()

  override fun updatePanels(panels: JsonArray) = frame()

  override fun close() {
    closed = true
  }
}

private class FakeXrFactory : XrRenderServerFactory {
  val created = mutableListOf<FakeXrHandle>()

  override fun start(width: Int, height: Int): XrRenderServerHandle =
    FakeXrHandle().also { created.add(it) }
}

/** Mirror of the test-only host in InteractiveRpcIntegrationTest. */
private class StreamRpcFakeHost(
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
      ?: error("StreamRpcFakeHost.submit($request) timed out")
  }

  override fun shutdown(timeoutMs: Long) {
    stopped = true
    queue.put(RenderRequest.Shutdown)
    worker.join(timeoutMs)
  }
}
