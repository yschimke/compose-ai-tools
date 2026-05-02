package ee.schimke.composeai.daemon

import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for #474 — `renderNow.overrides.device` must be resolved into the wire payload's
 * `widthPx` / `heightPx` / `density` tokens by [JsonRpcServer.encodeRenderPayload]. Pre-fix the
 * production path forwarded `device=id:pixel_5` as an opaque string, leaving downstream
 * `RenderSpec` defaults in place — the documented harness `PreviewManifestRouter` is the only place
 * that resolved the catalog, and production daemons don't run it.
 *
 * Drives a full JSON-RPC `initialize` → `renderNow` round-trip against a [PayloadCapturingHost] and
 * asserts on the payload string the host actually receives. We can't assert on rendered pixels at
 * this layer (no Compose runtime), but a wrong payload here is the proximate cause of the bug — the
 * existing `OverrideIntegrationTest`s prove `widthPx=…` payloads do reach the renderer's spec.
 */
class DeviceOverrideEncodingTest {

  private val json = Json { ignoreUnknownKeys = true }

  @Test(timeout = 30_000)
  fun deviceOverrideAloneResolvesToCatalogDimensions() {
    val captured = renderAndCapturePayload(overrides = """{"device":"id:pixel_5"}""")
    // PROTOCOL.md § 5: `id:pixel_5` is widthDp=393, heightDp=851, density=2.75. So the resolved
    // wire payload must carry widthPx=1080 (393 * 2.75 = 1080.75 → 1080), heightPx=2340 (851 *
    // 2.75 = 2340.25 → 2340), density=2.75. The raw `device=id:pixel_5` token still rides along
    // for the wear-round-crop heuristic on the Android backend.
    assertContainsToken("widthPx=1080", captured)
    assertContainsToken("heightPx=2340", captured)
    assertContainsToken("density=2.75", captured)
    assertContainsToken("device=id:pixel_5", captured)
  }

  @Test(timeout = 30_000)
  fun explicitWidthPxBeatsDeviceDerivedDimensions() {
    val captured = renderAndCapturePayload(overrides = """{"device":"id:pixel_5","widthPx":600}""")
    // Explicit `widthPx` wins over the device's derived 1080; height/density still flow from the
    // Pixel 5 catalog so a single field doesn't force the caller to repeat the rest.
    assertContainsToken("widthPx=600", captured)
    assertContainsToken("heightPx=2340", captured)
    assertContainsToken("density=2.75", captured)
    assertContainsToken("device=id:pixel_5", captured)
  }

  @Test(timeout = 30_000)
  fun unknownDeviceIdFallsBackToCatalogDefault() {
    val captured = renderAndCapturePayload(overrides = """{"device":"id:nonexistent"}""")
    // `DeviceDimensions.resolve` never throws on unknown ids — it returns the catalog `DEFAULT`
    // (400×800 dp at density=2.625, the same xxhdpi-ish constant Android Studio uses when no
    // device is specified). The override still has *some* effect (vs. the bug where it had
    // none); callers who want strict behaviour go through MCP's `validateOverrides` against
    // `capabilities.knownDevices`. Check #470 for an integration test of that surface.
    assertContainsToken("widthPx=1050", captured)
    assertContainsToken("heightPx=2100", captured)
    assertContainsToken("density=2.625", captured)
  }

  @Test(timeout = 30_000)
  fun noDeviceOverrideLeavesDimensionsAlone() {
    val captured = renderAndCapturePayload(overrides = """{"uiMode":"dark"}""")
    // No device → no auto-emitted size tokens. The host's spec defaults take over downstream,
    // matching pre-fix behaviour for the no-device case (the bug only affected the device case).
    assertTrue(
      "widthPx must not appear when no device/widthPx override sent: '$captured'",
      "widthPx=" !in captured,
    )
    assertTrue(
      "heightPx must not appear when no device/heightPx override sent: '$captured'",
      "heightPx=" !in captured,
    )
    assertTrue(
      "density must not appear when no device/density override sent: '$captured'",
      "density=" !in captured,
    )
    assertContainsToken("uiMode=dark", captured)
  }

  @Test(timeout = 30_000)
  fun inspectionModeOverrideThreadsThroughPayload() {
    val captured = renderAndCapturePayload(overrides = """{"inspectionMode":false}""")

    assertContainsToken("inspectionMode=false", captured)
  }

  private fun assertContainsToken(token: String, payload: String) {
    val tokens = payload.split(';')
    assertTrue(
      "expected token '$token' in payload tokens=$tokens (raw='$payload')",
      tokens.any { it.trim() == token },
    )
  }

  /**
   * Spins up a [JsonRpcServer] backed by a [PayloadCapturingHost] with a single-preview index, runs
   * `initialize` → `renderNow` with the supplied overrides JSON, and returns the
   * `RenderRequest.payload` string the host received.
   */
  private fun renderAndCapturePayload(overrides: String): String {
    val sourceKt = java.nio.file.Files.createTempFile("device-override-test", ".kt")
    java.nio.file.Files.writeString(sourceKt, "@Preview fun A() {}\n")
    val previewDto =
      PreviewInfoDto(
        id = "preview-A",
        className = "com.example.AKt",
        methodName = "A",
        sourceFile = sourceKt.toAbsolutePath().toString(),
      )
    val index = PreviewIndex.fromMap(path = sourceKt, byId = mapOf("preview-A" to previewDto))

    val clientToServerOut = PipedOutputStream()
    val clientToServerIn = PipedInputStream(clientToServerOut, 64 * 1024)
    val serverToClientOut = PipedOutputStream()
    val serverToClientIn = PipedInputStream(serverToClientOut, 64 * 1024)

    val host = PayloadCapturingHost()
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
    val serverThread =
      Thread({ server.run() }, "device-override-encoding-test").apply { isDaemon = true }
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
        "device-override-encoding-test-reader",
      )
      .apply { isDaemon = true }
      .start()

    try {
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
              "protocolVersion":1,"clientVersion":"test","workspaceRoot":"/tmp",
              "moduleId":":t","moduleProjectDir":"/tmp",
              "capabilities":{"visibility":true,"metrics":false}}}""",
      )
      assertNotNull(pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 1 })
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","method":"initialized","params":{}}""")
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":2,"method":"renderNow","params":{
              "previews":["preview-A"],"tier":"fast","overrides":$overrides}}""",
      )
      val finished =
        pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "renderFinished" }
      assertNotNull("renderFinished must arrive within timeout", finished)

      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","id":99,"method":"shutdown"}""")
      assertNotNull(pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 99 })
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","method":"exit"}""")
      assertTrue(exitLatch.await(5, TimeUnit.SECONDS))
      return host.lastPayload.get() ?: error("host never received a render request")
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
 * Captures the [RenderRequest.Render.payload] string the daemon submits, then completes the render
 * synchronously with a stub success result. Smaller than `JsonRpcServerIntegrationTest`'s
 * `FakeRenderHost` because we don't need to spy on metrics or interrupt counts here.
 */
private class PayloadCapturingHost : RenderHost {
  val lastPayload: AtomicReference<String?> = AtomicReference(null)
  private val queue = LinkedBlockingQueue<RenderRequest>()
  private val results = LinkedBlockingQueue<RenderResult>()
  @Volatile private var stopped = false
  private val worker =
    Thread(
        {
          while (!stopped) {
            val req = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
            when (req) {
              is RenderRequest.Render -> {
                lastPayload.set(req.payload)
                results.put(
                  RenderResult(id = req.id, classLoaderHashCode = 0, classLoaderName = "fake")
                )
              }
              RenderRequest.Shutdown -> return@Thread
            }
          }
        },
        "payload-capturing-host",
      )
      .apply { isDaemon = true }

  override fun start() {
    worker.start()
  }

  override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult {
    require(request is RenderRequest.Render)
    queue.put(request)
    return results.poll(timeoutMs, TimeUnit.MILLISECONDS)
      ?: error("PayloadCapturingHost.submit timed out")
  }

  override fun shutdown(timeoutMs: Long) {
    stopped = true
    queue.put(RenderRequest.Shutdown)
    worker.join(timeoutMs)
  }
}
