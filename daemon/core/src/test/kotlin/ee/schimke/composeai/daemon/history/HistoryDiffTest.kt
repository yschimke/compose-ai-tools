package ee.schimke.composeai.daemon.history

import ee.schimke.composeai.daemon.ContentLengthFramer
import ee.schimke.composeai.daemon.JsonRpcServer
import ee.schimke.composeai.daemon.RenderHost
import ee.schimke.composeai.daemon.RenderRequest
import ee.schimke.composeai.daemon.RenderResult
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * H3 — `history/diff` METADATA-mode unit tests. Drives the [JsonRpcServer] over piped streams with
 * a [HistoryManager] backed by a temp [LocalFsHistorySource] so each test can synthesise the
 * entries it needs and assert on the wire shape.
 *
 * Covers the cases enumerated in HISTORY.md § "What this PR lands § H3":
 * - same hash → `pngHashChanged = false`, both metadata returned
 * - different hash, same previewId → `pngHashChanged = true`
 * - different previewIds → `HistoryDiffMismatch (-32011)`
 * - missing `from` / `to` → `HistoryEntryNotFound (-32010)`
 * - `mode = pixel` → `-32012` reserved-for-H5 error
 */
class HistoryDiffTest {

  private lateinit var historyDir: Path
  private val json = Json { ignoreUnknownKeys = true }

  @Before
  fun setUp() {
    historyDir = Files.createTempDirectory("history-diff-test")
  }

  @After
  fun tearDown() {
    historyDir.toFile().deleteRecursively()
  }

  @Test(timeout = 30_000)
  fun same_hash_pngHashChanged_false() {
    runWith { _, manager, send, receive ->
      // Two entries with identical pngHash for the same previewId. The dedup ladder skips
      // consecutive identical renders (tier 1), so we drive the A → B → A pattern: third render's
      // bytes match the first's, and since the most-recent entry on disk (B) has a DIFFERENT
      // hash, tier 1 doesn't fire and the third entry lands. We then diff first-A vs second-A.
      val ts1 = Instant.parse("2026-04-30T10:12:34Z")
      val ts2 = Instant.parse("2026-04-30T10:12:35Z")
      val ts3 = Instant.parse("2026-04-30T10:12:36Z")
      val entry1 =
        manager.recordRender(
          "preview-A",
          "same-bytes".toByteArray(),
          trigger = "renderNow",
          renderTookMs = 1,
          timestamp = ts1,
        )!!
      manager.recordRender(
        "preview-A",
        "different-bytes".toByteArray(),
        trigger = "renderNow",
        renderTookMs = 1,
        timestamp = ts2,
      )!!
      val entry2 =
        manager.recordRender(
          "preview-A",
          "same-bytes".toByteArray(),
          trigger = "renderNow",
          renderTookMs = 1,
          timestamp = ts3,
        )!!
      assertEquals(entry1.pngHash, entry2.pngHash)

      val resp = diffRoundTrip(send, receive, from = entry1.id, to = entry2.id)
      val result = resp["result"]!!.jsonObject
      assertEquals(false, result["pngHashChanged"]!!.jsonPrimitive.content.toBooleanStrict())
      assertEquals(entry1.id, result["fromMetadata"]!!.jsonObject["id"]!!.jsonPrimitive.content)
      assertEquals(entry2.id, result["toMetadata"]!!.jsonObject["id"]!!.jsonPrimitive.content)
      // Pixel-mode fields are null in METADATA mode by design.
      assertTrue(
        result["diffPx"] == null || result["diffPx"] == kotlinx.serialization.json.JsonNull
      )
      assertTrue(result["ssim"] == null || result["ssim"] == kotlinx.serialization.json.JsonNull)
      assertTrue(
        result["diffPngPath"] == null ||
          result["diffPngPath"] == kotlinx.serialization.json.JsonNull
      )
    }
  }

  @Test(timeout = 30_000)
  fun different_hash_pngHashChanged_true() {
    runWith { _, manager, send, receive ->
      val ts1 = Instant.parse("2026-04-30T10:12:34Z")
      val ts2 = Instant.parse("2026-04-30T10:12:35Z")
      val entry1 =
        manager.recordRender(
          "preview-A",
          "first-bytes".toByteArray(),
          trigger = "renderNow",
          renderTookMs = 1,
          timestamp = ts1,
        )!!
      val entry2 =
        manager.recordRender(
          "preview-A",
          "second-bytes".toByteArray(),
          trigger = "renderNow",
          renderTookMs = 1,
          timestamp = ts2,
        )!!
      assertTrue(entry1.pngHash != entry2.pngHash)

      val resp = diffRoundTrip(send, receive, from = entry1.id, to = entry2.id)
      val result = resp["result"]!!.jsonObject
      assertEquals(true, result["pngHashChanged"]!!.jsonPrimitive.content.toBooleanStrict())
    }
  }

  @Test(timeout = 30_000)
  fun different_previewIds_diffMismatch() {
    runWith { _, manager, send, receive ->
      val a =
        manager.recordRender(
          "preview-A",
          "a-bytes".toByteArray(),
          trigger = "renderNow",
          renderTookMs = 1,
        )!!
      val b =
        manager.recordRender(
          "preview-B",
          "b-bytes".toByteArray(),
          trigger = "renderNow",
          renderTookMs = 1,
        )!!
      val resp = diffRoundTrip(send, receive, from = a.id, to = b.id)
      val errCode = resp["error"]!!.jsonObject["code"]!!.jsonPrimitive.intOrNull
      assertEquals(JsonRpcServer.ERR_HISTORY_DIFF_MISMATCH, errCode)
    }
  }

  @Test(timeout = 30_000)
  fun missing_from_entryNotFound() {
    runWith { _, manager, send, receive ->
      val a =
        manager.recordRender(
          "preview-A",
          "a-bytes".toByteArray(),
          trigger = "renderNow",
          renderTookMs = 1,
        )!!
      val resp = diffRoundTrip(send, receive, from = "does-not-exist", to = a.id)
      val errCode = resp["error"]!!.jsonObject["code"]!!.jsonPrimitive.intOrNull
      assertEquals(JsonRpcServer.ERR_HISTORY_ENTRY_NOT_FOUND, errCode)
    }
  }

  @Test(timeout = 30_000)
  fun missing_to_entryNotFound() {
    runWith { _, manager, send, receive ->
      val a =
        manager.recordRender(
          "preview-A",
          "a-bytes".toByteArray(),
          trigger = "renderNow",
          renderTookMs = 1,
        )!!
      val resp = diffRoundTrip(send, receive, from = a.id, to = "does-not-exist")
      val errCode = resp["error"]!!.jsonObject["code"]!!.jsonPrimitive.intOrNull
      assertEquals(JsonRpcServer.ERR_HISTORY_ENTRY_NOT_FOUND, errCode)
    }
  }

  @Test(timeout = 30_000)
  fun pixel_mode_not_yet_implemented() {
    runWith { _, manager, send, receive ->
      val a =
        manager.recordRender(
          "preview-A",
          "a-bytes".toByteArray(),
          trigger = "renderNow",
          renderTookMs = 1,
        )!!
      val resp = diffRoundTrip(send, receive, from = a.id, to = a.id, mode = "pixel")
      val errCode = resp["error"]!!.jsonObject["code"]!!.jsonPrimitive.intOrNull
      assertEquals(JsonRpcServer.ERR_HISTORY_PIXEL_NOT_IMPLEMENTED, errCode)
    }
  }

  // -------------------------------------------------------------------------
  // Test infrastructure — minimal JSON-RPC client over piped streams.
  // -------------------------------------------------------------------------

  private fun runWith(
    block:
      (
        JsonRpcServer,
        HistoryManager,
        send: (String) -> Unit,
        receive: LinkedBlockingQueue<JsonObject>,
      ) -> Unit
  ) {
    val manager =
      HistoryManager.forLocalFs(historyDir = historyDir, module = ":t", gitProvenance = null)

    val clientToServerOut = PipedOutputStream()
    val clientToServerIn = PipedInputStream(clientToServerOut, 64 * 1024)
    val serverToClientOut = PipedOutputStream()
    val serverToClientIn = PipedInputStream(serverToClientOut, 64 * 1024)
    val exitLatch = CountDownLatch(1)
    val server =
      JsonRpcServer(
        input = clientToServerIn,
        output = serverToClientOut,
        host = StubHost(),
        daemonVersion = "test",
        historyManager = manager,
        onExit = { _ -> exitLatch.countDown() },
      )
    val serverThread =
      Thread({ server.run() }, "history-diff-test-server").apply { isDaemon = true }
    serverThread.start()

    val received = LinkedBlockingQueue<JsonObject>()
    val reader = ContentLengthFramer(serverToClientIn)
    Thread(
        {
          try {
            while (true) {
              val frame = reader.readFrame() ?: break
              received.put(json.parseToJsonElement(frame.toString(Charsets.UTF_8)).jsonObject)
            }
          } catch (_: Throwable) {}
        },
        "history-diff-test-reader",
      )
      .apply { isDaemon = true }
      .start()

    val send: (String) -> Unit = { jsonText ->
      val payload = jsonText.toByteArray(Charsets.UTF_8)
      clientToServerOut.write(
        "Content-Length: ${payload.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
      )
      clientToServerOut.write(payload)
      clientToServerOut.flush()
    }
    try {
      // initialize → initialized.
      send(
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
              "protocolVersion":1,"clientVersion":"test","workspaceRoot":"/tmp",
              "moduleId":":test","moduleProjectDir":"/tmp",
              "capabilities":{"visibility":true,"metrics":false}}}"""
      )
      assertNotNull(pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 1 })
      send("""{"jsonrpc":"2.0","method":"initialized","params":{}}""")

      block(server, manager, send, received)

      // Tear down.
      send("""{"jsonrpc":"2.0","id":99,"method":"shutdown"}""")
      assertNotNull(pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 99 })
      send("""{"jsonrpc":"2.0","method":"exit"}""")
      assertTrue(exitLatch.await(5, TimeUnit.SECONDS))
    } finally {
      try {
        clientToServerOut.close()
      } catch (_: Throwable) {}
      try {
        serverToClientIn.close()
      } catch (_: Throwable) {}
      serverThread.join(5_000)
    }
  }

  private var nextId = 100L

  private fun diffRoundTrip(
    send: (String) -> Unit,
    received: LinkedBlockingQueue<JsonObject>,
    from: String,
    to: String,
    mode: String = "metadata",
  ): JsonObject {
    val id = nextId++
    send(
      """{"jsonrpc":"2.0","id":$id,"method":"history/diff","params":{"from":"$from","to":"$to","mode":"$mode"}}"""
    )
    val resp = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull?.toLong() == id }
    assertNotNull("history/diff response did not arrive", resp)
    return resp!!
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
    }
    return null
  }

  /**
   * Stub host that doesn't render — we drive `recordRender` directly via [HistoryManager] in the
   * tests above so the wire path doesn't need real renders.
   */
  private class StubHost : RenderHost {
    override fun start() {}

    override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult =
      error("StubHost.submit: not used in HistoryDiffTest — tests drive HistoryManager directly")

    override fun shutdown(timeoutMs: Long) {}
  }

  // Exhaustively reference the symbol so the test stays compile-coupled to the params/result
  // types even if the wire is hand-written above.
  @Suppress("unused")
  private fun referenceSymbols() {
    val _ignored: ee.schimke.composeai.daemon.protocol.HistoryDiffParams =
      ee.schimke.composeai.daemon.protocol.HistoryDiffParams(from = "a", to = "b")
    val _ignored2: ee.schimke.composeai.daemon.protocol.HistoryDiffResult =
      ee.schimke.composeai.daemon.protocol.HistoryDiffResult(
        pngHashChanged = false,
        fromMetadata = kotlinx.serialization.json.JsonObject(emptyMap()),
        toMetadata = kotlinx.serialization.json.JsonObject(emptyMap()),
      )
  }
}
