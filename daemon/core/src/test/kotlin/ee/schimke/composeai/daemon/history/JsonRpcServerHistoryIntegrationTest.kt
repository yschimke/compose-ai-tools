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
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end integration test for the history wire surface — see HISTORY.md § "Layer 2 — JSON-RPC
 * API". Drives a `JsonRpcServer` configured with a [HistoryManager] over piped streams and asserts:
 *
 * 1. After a `renderNow` succeeds, a `historyAdded` notification arrives carrying a parseable
 *    [HistoryEntry] whose `pngHash` matches the rendered bytes.
 * 2. `history/list` returns the same entry.
 * 3. `history/read` (default) returns the entry + a non-null `pngPath` referencing the bytes on
 *    disk.
 * 4. `history/read({inline: true})` returns base64 bytes that decode back to the original PNG.
 * 5. `history/list({previewId: "other"})` filter narrows correctly.
 * 6. `history/read({id: "missing"})` returns a `-32010 HistoryEntryNotFound` error.
 * 7. `history/diff` (metadata mode, H3) round-trips a real diff result.
 * 8. `history/prune` returns `-32601 MethodNotFound` (reserved, not implemented).
 */
class JsonRpcServerHistoryIntegrationTest {

  private lateinit var historyDir: Path
  private lateinit var rendersDir: Path

  private val json = Json { ignoreUnknownKeys = true }

  @Before
  fun setUp() {
    historyDir = Files.createTempDirectory("history-int-test")
    rendersDir = Files.createTempDirectory("history-int-test-renders")
  }

  @After
  fun tearDown() {
    historyDir.toFile().deleteRecursively()
    rendersDir.toFile().deleteRecursively()
  }

  @Test(timeout = 30_000)
  fun history_full_lifecycle() {
    val host = RealPngHost(rendersDir)
    val historyManager =
      HistoryManager.forLocalFs(
        historyDir = historyDir,
        module = ":t",
        gitProvenance = null, // No git; entries land with git=null/worktree=null. Fine for H1.
      )

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
        historyManager = historyManager,
        onExit = { _ -> exitLatch.countDown() },
      )
    val serverThread =
      Thread({ server.run() }, "json-rpc-server-history-test").apply { isDaemon = true }
    serverThread.start()

    val received = LinkedBlockingQueue<JsonObject>()
    val reader = ContentLengthFramer(serverToClientIn)
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
        "json-rpc-server-history-reader",
      )
      .apply { isDaemon = true }
      .start()

    try {
      // 1. initialize → initialized.
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
              "protocolVersion":1,"clientVersion":"test","workspaceRoot":"/tmp",
              "moduleId":":test","moduleProjectDir":"/tmp",
              "capabilities":{"visibility":true,"metrics":false}}}""",
      )
      assertNotNull(pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 1 })
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","method":"initialized","params":{}}""")

      // 2. renderNow.
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":2,"method":"renderNow","params":{
              "previews":["preview-A"],"tier":"fast"}}""",
      )
      assertNotNull(pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 2 })

      // 3. historyAdded notification arrives.
      val historyAdded =
        pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "historyAdded" }
      assertNotNull("historyAdded notification must arrive within 1s", historyAdded)
      val entryJson = historyAdded!!["params"]!!.jsonObject["entry"]!!.jsonObject
      val entryId = entryJson["id"]!!.jsonPrimitive.content
      assertEquals("preview-A", entryJson["previewId"]!!.jsonPrimitive.content)
      assertEquals("daemon", entryJson["producer"]!!.jsonPrimitive.content)
      assertEquals("renderNow", entryJson["trigger"]!!.jsonPrimitive.content)
      assertEquals("fs", entryJson["source"]!!.jsonObject["kind"]!!.jsonPrimitive.content)

      // 4. Disk has PNG + sidecar + index entry.
      val previewDir = historyDir.resolve("preview-A")
      assertTrue(Files.exists(previewDir.resolve("$entryId.png")))
      assertTrue(Files.exists(previewDir.resolve("$entryId.json")))
      val indexLines = Files.readAllLines(historyDir.resolve(LocalFsHistorySource.INDEX_FILENAME))
      assertEquals(1, indexLines.filter { it.isNotBlank() }.size)

      // 5. history/list returns the entry.
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":3,"method":"history/list","params":{}}""",
      )
      val listResp = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 3 }
      val listResult = listResp!!["result"]!!.jsonObject
      assertEquals(1, listResult["totalCount"]!!.jsonPrimitive.intOrNull)
      assertEquals(1, listResult["entries"]!!.jsonArray.size)
      assertEquals(
        entryId,
        listResult["entries"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content,
      )

      // 6. history/read default — pngBytes null.
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":4,"method":"history/read","params":{"id":"$entryId"}}""",
      )
      val readResp = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 4 }
      val readResult = readResp!!["result"]!!.jsonObject
      val pngPath = readResult["pngPath"]!!.jsonPrimitive.content
      assertTrue("pngPath must point at an existing file", Files.exists(Path.of(pngPath)))
      assertTrue(
        "default read must NOT inline pngBytes",
        readResult["pngBytes"] == null ||
          readResult["pngBytes"] == kotlinx.serialization.json.JsonNull,
      )

      // 7. history/read inline=true — base64 decodes to original PNG bytes.
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":5,"method":"history/read","params":{"id":"$entryId","inline":true}}""",
      )
      val readInlineResp = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 5 }
      val readInlineResult = readInlineResp!!["result"]!!.jsonObject
      val base64 = readInlineResult["pngBytes"]!!.jsonPrimitive.content
      val decoded = Base64.getDecoder().decode(base64)
      assertTrue(decoded.contentEquals(host.lastPngBytes))

      // 8. history/list({previewId: "preview-B"}) returns empty.
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":6,"method":"history/list","params":{"previewId":"preview-B"}}""",
      )
      val emptyResp = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 6 }
      val emptyList = emptyResp!!["result"]!!.jsonObject
      assertEquals(0, emptyList["totalCount"]!!.jsonPrimitive.intOrNull)
      assertEquals(0, emptyList["entries"]!!.jsonArray.size)

      // 9. history/read for missing id → -32010.
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":7,"method":"history/read","params":{"id":"does-not-exist"}}""",
      )
      val missing = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 7 }
      val errCode = missing!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.intOrNull
      assertEquals(JsonRpcServer.ERR_HISTORY_ENTRY_NOT_FOUND, errCode)

      // 10. history/diff for missing id → -32010.
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":8,"method":"history/diff","params":{"from":"missing","to":"$entryId"}}""",
      )
      val diffMissing = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 8 }
      assertEquals(
        JsonRpcServer.ERR_HISTORY_ENTRY_NOT_FOUND,
        diffMissing!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.intOrNull,
      )

      // 11. history/diff(from=entryId, to=entryId) — same entry → pngHashChanged=false.
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":9,"method":"history/diff","params":{"from":"$entryId","to":"$entryId"}}""",
      )
      val diffSelf = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 9 }
      val diffSelfResult = diffSelf!!["result"]!!.jsonObject
      assertEquals(
        false,
        diffSelfResult["pngHashChanged"]!!.jsonPrimitive.content.toBooleanStrict(),
      )
      assertNotNull(diffSelfResult["fromMetadata"])
      assertNotNull(diffSelfResult["toMetadata"])

      // 12. history/diff with mode=pixel → -32012 (reserved for H5).
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":10,"method":"history/diff","params":{"from":"$entryId","to":"$entryId","mode":"pixel"}}""",
      )
      val diffPixel = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 10 }
      assertEquals(
        JsonRpcServer.ERR_HISTORY_PIXEL_NOT_IMPLEMENTED,
        diffPixel!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.intOrNull,
      )

      // 13. history/prune (H4) — dryRun with a tight policy returns the would-remove set
      //     without mutating disk + does NOT emit a `historyPruned` notification.
      val sidecarBefore = Files.exists(historyDir.resolve("preview-A").resolve("$entryId.png"))
      assertTrue("PNG must exist before dry-run prune", sidecarBefore)
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":11,"method":"history/prune","params":{
              "maxEntriesPerPreview":0,"maxAgeDays":0,"maxTotalSizeBytes":1,
              "dryRun":true}}""",
      )
      val pruneDry = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 11 }
      assertNotNull("dry-run prune must respond", pruneDry)
      val pruneDryResult = pruneDry!!["result"]!!.jsonObject
      // Policy: only "total size" knob active (limit=1 byte) + "never drop most recent per
      // preview" floor — so removed list is empty (one entry, one preview, must survive).
      assertEquals(0, pruneDryResult["removedEntries"]!!.jsonArray.size)
      assertEquals(0, pruneDryResult["freedBytes"]!!.jsonPrimitive.intOrNull)
      assertTrue(
        "dry-run must NOT delete the PNG",
        Files.exists(historyDir.resolve("preview-A").resolve("$entryId.png")),
      )

      // 14. history/prune (H4) — manual prune with all-survive policy returns empty + no notif.
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":12,"method":"history/prune","params":{}}""",
      )
      val prune = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 12 }
      assertNotNull("manual prune must respond", prune)
      val pruneResult = prune!!["result"]!!.jsonObject
      assertEquals(0, pruneResult["removedEntries"]!!.jsonArray.size)
      assertEquals(0, pruneResult["freedBytes"]!!.jsonPrimitive.intOrNull)

      // 15. history/prune (H4) — render a second preview, then prune with maxEntriesPerPreview=1
      //     and force a removal on the first preview. Asserts the historyPruned notification
      //     arrives with reason=manual.
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":13,"method":"renderNow","params":{
              "previews":["preview-A"],"tier":"fast"}}""",
      )
      assertNotNull(pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 13 })
      // Wait for the second historyAdded notification to ensure the entry is on disk.
      val secondHistoryAdded =
        pollUntil(received) {
          it["method"]?.jsonPrimitive?.contentOrNull == "historyAdded" &&
            it["params"]?.jsonObject?.get("entry")?.jsonObject?.get("id")?.jsonPrimitive?.content !=
              entryId
        }
      assertNotNull("second historyAdded must arrive", secondHistoryAdded)

      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":14,"method":"history/prune","params":{
              "maxEntriesPerPreview":1,"maxAgeDays":0,"maxTotalSizeBytes":0}}""",
      )
      // Notification fires before the response (listener runs synchronously inside pruneNow,
      // which runs before sendResponse). Poll historyPruned first so we don't accidentally
      // discard it while waiting for the response.
      val pruneNotif =
        pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "historyPruned" }
      assertNotNull(
        "historyPruned notification must arrive after non-empty manual prune",
        pruneNotif,
      )
      val pruneNotifParams = pruneNotif!!["params"]!!.jsonObject
      assertEquals("manual", pruneNotifParams["reason"]!!.jsonPrimitive.content)
      assertEquals(1, pruneNotifParams["removedIds"]!!.jsonArray.size)
      val prune2 = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 14 }
      val prune2Result = prune2!!["result"]!!.jsonObject
      // One preview "preview-A" with 2 entries; cap=1 → 1 removed (the older one).
      assertEquals(1, prune2Result["removedEntries"]!!.jsonArray.size)

      // Tear down cleanly.
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","id":99,"method":"shutdown"}""")
      assertNotNull(pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 99 })
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","method":"exit"}""")
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

  @Test(timeout = 30_000)
  fun initialize_history_prune_options_override_manager_defaults() {
    val host = RealPngHost(rendersDir)
    val historyManager =
      HistoryManager.forLocalFs(
        historyDir = historyDir,
        module = ":t",
        gitProvenance = null,
        pruneConfig =
          HistoryPruneConfig(
            maxEntriesPerPreview = 10,
            maxAgeDays = 11,
            maxTotalSizeBytes = 12L,
            autoPruneIntervalMs = 13L,
          ),
      )

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
        idleTimeoutMs = 100L,
        historyManager = historyManager,
        onExit = { _ -> exitLatch.countDown() },
      )
    val serverThread =
      Thread({ server.run() }, "json-rpc-server-history-prune-options-test").apply {
        isDaemon = true
      }
    serverThread.start()

    val received = LinkedBlockingQueue<JsonObject>()
    val reader = ContentLengthFramer(serverToClientIn)
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
        "json-rpc-server-history-prune-options-reader",
      )
      .apply { isDaemon = true }
      .start()

    try {
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
              "protocolVersion":1,"clientVersion":"test","workspaceRoot":"/tmp",
              "moduleId":":test","moduleProjectDir":"/tmp",
              "capabilities":{"visibility":true,"metrics":false},
              "options":{"historyPrune":{
                "maxEntriesPerPreview":0,
                "maxAgeDays":1,
                "maxTotalSizeBytes":2,
                "autoIntervalMs":3
              }}}}""",
      )
      assertNotNull(pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 1 })

      assertEquals(
        HistoryPruneConfig(
          maxEntriesPerPreview = 0,
          maxAgeDays = 1,
          maxTotalSizeBytes = 2L,
          autoPruneIntervalMs = 3L,
        ),
        historyManager.pruneConfig,
      )
    } finally {
      try {
        clientToServerOut.close()
      } catch (_: Throwable) {}
      try {
        serverToClientIn.close()
      } catch (_: Throwable) {}
      assertTrue("server should exit after input closes", exitLatch.await(5, TimeUnit.SECONDS))
      serverThread.join(5_000)
    }
  }

  /**
   * Test [RenderHost] that writes a deterministic PNG-shaped byte sequence to disk and returns its
   * path. We don't need a real PNG decoder — H1's history pipeline only sha256s the bytes, so any
   * deterministic blob works.
   */
  private class RealPngHost(private val rendersDir: Path) : RenderHost {
    @Volatile
    var lastPngBytes: ByteArray = ByteArray(0)
      private set

    private val queue = LinkedBlockingQueue<RenderRequest>()
    private val results =
      java.util.concurrent.ConcurrentHashMap<Long, LinkedBlockingQueue<RenderResult>>()

    @Volatile private var stopped = false
    private val worker =
      Thread(
          {
            while (!stopped) {
              val req =
                try {
                  queue.poll(100, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                  Thread.currentThread().interrupt()
                  return@Thread
                } ?: continue
              when (req) {
                is RenderRequest.Render -> {
                  val pngFile = rendersDir.resolve("render-${req.id}.png")
                  val payload = "synthetic-render-${req.id}".toByteArray()
                  Files.write(pngFile, payload)
                  lastPngBytes = payload
                  val cl = Thread.currentThread().contextClassLoader
                  results
                    .computeIfAbsent(req.id) { LinkedBlockingQueue() }
                    .put(
                      RenderResult(
                        id = req.id,
                        classLoaderHashCode = System.identityHashCode(cl),
                        classLoaderName = cl?.javaClass?.name ?: "<null>",
                        pngPath = pngFile.toAbsolutePath().toString(),
                        metrics = mapOf("tookMs" to 1L),
                      )
                    )
                }
                RenderRequest.Shutdown -> return@Thread
              }
            }
          },
          "history-int-test-host",
        )
        .apply { isDaemon = true }

    override fun start() {
      worker.start()
    }

    override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult {
      require(request is RenderRequest.Render)
      queue.put(request)
      val q = results.computeIfAbsent(request.id) { LinkedBlockingQueue() }
      return q.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: error("RealPngHost.submit timed out")
    }

    override fun shutdown(timeoutMs: Long) {
      stopped = true
      queue.put(RenderRequest.Shutdown)
      worker.join(timeoutMs)
    }
  }

  private fun writeFrame(out: PipedOutputStream, jsonText: String) {
    val payload = jsonText.toByteArray(Charsets.UTF_8)
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
    }
    return null
  }
}
