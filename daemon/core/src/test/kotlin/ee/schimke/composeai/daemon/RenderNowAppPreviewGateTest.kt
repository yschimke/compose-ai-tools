package ee.schimke.composeai.daemon

import java.io.PipedInputStream
import java.io.PipedOutputStream
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App-level synthetic previews (`kind=ACTIVITY` / `kind=APP_TOUR`) launch real activities and drive
 * multi-step navigation — only the Gradle Robolectric renderer implements that. The daemon must
 * reject them at `renderNow` rather than fall through to the composable-method reflection path,
 * where the "method" (the activity class's own simple name) doesn't exist and the render dies with
 * `NoSuchMethodException` → `renderFailed`. That failure mode broke the consumer-sample e2e matrix
 * (integration.yml's daemon round-trip) the moment discovery started emitting `ACTIVITY` previews
 * for app modules: the round-trip driver fails hard on any `renderFailed` but only warns on
 * rejections.
 */
class RenderNowAppPreviewGateTest {

  private val json = Json { ignoreUnknownKeys = true }

  @Test(timeout = 30_000)
  fun activityAndTourPreviewsAreRejectedNotFailed() {
    val sourceKt = java.nio.file.Files.createTempFile("app-preview-gate-test", ".kt")
    java.nio.file.Files.writeString(sourceKt, "@Preview fun A() {}\n")
    val composable =
      PreviewInfoDto(
        id = "preview-A",
        className = "com.example.AKt",
        methodName = "A",
        sourceFile = sourceKt.toAbsolutePath().toString(),
      )
    val activity =
      PreviewInfoDto(
        id = "preview-activity",
        className = "com.example.MainActivity",
        methodName = "MainActivity",
        sourceFile = sourceKt.toAbsolutePath().toString(),
        params = PreviewParamsDto(kind = "ACTIVITY"),
      )
    val tour =
      PreviewInfoDto(
        id = "preview-tour",
        className = "com.example.MainActivity",
        methodName = "MainActivity",
        sourceFile = sourceKt.toAbsolutePath().toString(),
        params = PreviewParamsDto(kind = "APP_TOUR"),
      )
    val index =
      PreviewIndex.fromMap(
        path = sourceKt,
        byId =
          mapOf("preview-A" to composable, "preview-activity" to activity, "preview-tour" to tour),
      )

    val clientToServerOut = PipedOutputStream()
    val clientToServerIn = PipedInputStream(clientToServerOut, 64 * 1024)
    val serverToClientOut = PipedOutputStream()
    val serverToClientIn = PipedInputStream(serverToClientOut, 64 * 1024)

    val host = GateStubHost()
    val exitLatch = CountDownLatch(1)
    val server =
      JsonRpcServer(
        input = clientToServerIn,
        output = serverToClientOut,
        host = host,
        daemonVersion = "test",
        previewIndex = index,
        onExit = { _ -> exitLatch.countDown() },
      )
    val serverThread = Thread({ server.run() }, "app-preview-gate-test").apply { isDaemon = true }
    serverThread.start()

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
        "app-preview-gate-test-reader",
      )
      .apply { isDaemon = true }
      .start()

    try {
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
              "protocolVersion":2,"clientVersion":"test","workspaceRoot":"/tmp",
              "moduleId":":t","moduleProjectDir":"/tmp",
              "capabilities":{"visibility":true,"metrics":false}}}""",
      )
      assertNotNull(pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 1 })
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","method":"initialized","params":{}}""")
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":2,"method":"renderNow","params":{
              "previews":["preview-A","preview-activity","preview-tour"],"tier":"fast"}}""",
      )
      val response = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 2 }
      assertNotNull("renderNow response must arrive within timeout", response)
      val result = response!!["result"]!!.jsonObject
      val queued = result["queued"]!!.jsonArray.map { it.jsonPrimitive.contentOrNull }
      assertEquals(listOf("preview-A"), queued)
      val rejected = result["rejected"]!!.jsonArray.map { it.jsonObject }
      assertEquals(
        setOf("preview-activity", "preview-tour"),
        rejected.mapNotNull { it["id"]?.jsonPrimitive?.contentOrNull }.toSet(),
      )
      rejected.forEach {
        val reason = it["reason"]?.jsonPrimitive?.contentOrNull.orEmpty()
        assertTrue(
          "rejection reason must explain the kind gate: '$reason'",
          "not renderable by the daemon" in reason,
        )
      }

      // The composable preview still renders — the gate must not swallow the rest of the batch.
      val finished =
        pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "renderFinished" }
      assertNotNull("renderFinished must arrive for the plain composable preview", finished)
      // And nothing failed: the gated previews were never submitted to the host, so the only
      // terminal render event in the stream is preview-A's success above.
      assertTrue(received.none { it["method"]?.jsonPrimitive?.contentOrNull == "renderFailed" })

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
      try {
        java.nio.file.Files.deleteIfExists(sourceKt)
      } catch (_: Throwable) {}
      serverThread.join(5_000)
    }
  }

  private fun writeFrame(out: PipedOutputStream, jsonStr: String) {
    val payload = jsonStr.toByteArray(Charsets.UTF_8)
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

/**
 * Completes every submitted render synchronously with a stub success result. Same shape as
 * `DeviceOverrideEncodingTest`'s `PayloadCapturingHost` minus the payload spying — this test only
 * cares which previews reach the host at all.
 */
private class GateStubHost : RenderHost {
  private val queue = LinkedBlockingQueue<RenderRequest>()
  private val results = LinkedBlockingQueue<RenderResult>()
  @Volatile private var stopped = false
  private val worker =
    Thread(
        {
          while (!stopped) {
            val req = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
            when (req) {
              is RenderRequest.Render ->
                results.put(
                  RenderResult(id = req.id, classLoaderHashCode = 0, classLoaderName = "fake")
                )
              RenderRequest.Shutdown -> return@Thread
            }
          }
        },
        "gate-stub-host",
      )
      .apply { isDaemon = true }

  override fun start() {
    worker.start()
  }

  override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult {
    require(request is RenderRequest.Render)
    queue.put(request)
    return results.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: error("GateStubHost.submit timed out")
  }

  override fun shutdown(timeoutMs: Long) {
    stopped = true
    queue.put(RenderRequest.Shutdown)
    worker.join(timeoutMs)
  }
}
