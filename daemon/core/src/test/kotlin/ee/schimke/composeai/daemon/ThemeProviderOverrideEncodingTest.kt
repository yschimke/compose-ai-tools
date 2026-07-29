package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Base64
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the wire-side leg of the app-declared theme axis (`@ThemeCatalog` /
 * `@WearThemeCatalog`).
 *
 * [JsonRpcServer.encodeRenderPayload] serializes the render-affecting overrides that have no typed
 * wire token of their own into a single base64 `overrides=<bag>` token. `themeProvider` was missing
 * from that bag, so a one-shot `renderNow.overrides.themeProvider = <providerFqn>` was dropped on
 * the wire: the renderer read `spec.overrides?.themeProvider == null` in
 * `InvokeWithOptionalWrapper` and fell back to the preview's declared `@PreviewWrapper`. On the
 * preview server that surfaced as a Theme picker whose chips redrew byte-identical (unthemed)
 * pixels — every declared theme rendered the same. The live `stream/start` path was unaffected: it
 * carries the FQN separately as `InteractiveCommand.Start.themeProviderFqn`.
 *
 * Drives a full JSON-RPC `initialize` → `renderNow` round-trip against a payload-capturing host and
 * asserts the encoded payload carries the FQN inside the `overrides=<base64>` token. Sibling to
 * [PermissionsOverrideEncodingTest] — same plumbing, different override field.
 */
class ThemeProviderOverrideEncodingTest {

  private val json = Json { ignoreUnknownKeys = true }

  @Test(timeout = 30_000)
  fun themeProviderOverrideIsEncodedIntoTheExtensionBag() {
    val captured =
      renderAndCapturePayload(
        overrides = """{"themeProvider":"com.example.designcatalogwearm3.WearTealThemeCatalog"}"""
      )
    val bag = decodeExtensionBag(captured)
    assertEquals("com.example.designcatalogwearm3.WearTealThemeCatalog", bag.themeProvider)
  }

  @Test(timeout = 30_000)
  fun themeProviderRidesAlongsideTheOtherBagFields() {
    // The theme selection travels in the same single bag as the planner-driven fields rather than
    // sprouting a token of its own.
    val captured =
      renderAndCapturePayload(
        overrides =
          """{
                "themeProvider":"com.example.ThemeCatalog",
                "material3Theme":{"sourceColor":"#FF3366FF"}
              }"""
      )
    val bag = decodeExtensionBag(captured)
    assertEquals("com.example.ThemeCatalog", bag.themeProvider)
    assertNotNull("material3Theme must round-trip", bag.material3Theme)
  }

  @Test(timeout = 30_000)
  fun blankThemeProviderDoesNotForceTheBag() {
    // A blank FQN is "no theme selected" — it must not emit a bag on its own, matching the
    // no-extension-field case in [PermissionsOverrideEncodingTest].
    val captured = renderAndCapturePayload(overrides = """{"themeProvider":"","uiMode":"dark"}""")
    assertTrue(
      "overrides= bag must be omitted for a blank themeProvider: '$captured'",
      "overrides=" !in captured,
    )
  }

  private fun decodeExtensionBag(payload: String): PreviewOverrides {
    val token =
      payload.split(';').firstOrNull { it.trim().startsWith("overrides=") }
        ?: error("payload must carry an overrides= token: '$payload'")
    val b64 = token.substringAfter('=').trim()
    val raw = String(Base64.getUrlDecoder().decode(b64), Charsets.UTF_8)
    return json.decodeFromString(PreviewOverrides.serializer(), raw)
  }

  /**
   * Spins up a [JsonRpcServer] backed by a payload-capturing host with a single-preview index, runs
   * `initialize` → `renderNow` with the supplied overrides JSON, and returns the
   * `RenderRequest.payload` string the host received. Mirrors [PermissionsOverrideEncodingTest]'s
   * helper verbatim — kept file-local for the same reason.
   */
  private fun renderAndCapturePayload(overrides: String): String {
    val sourceKt = java.nio.file.Files.createTempFile("theme-provider-override-test", ".kt")
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

    val host = PayloadCapturingThemeProviderHost()
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
      Thread({ server.run() }, "theme-provider-override-encoding-test").apply { isDaemon = true }
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
        "theme-provider-override-encoding-test-reader",
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
 * synchronously with a stub success result. File-local copy of the shape
 * [PermissionsOverrideEncodingTest] uses, so the two tests take no cross-file dependency.
 */
private class PayloadCapturingThemeProviderHost : RenderHost {
  val lastPayload: AtomicReference<String?> = AtomicReference(null)
  private val queue = LinkedBlockingQueue<RenderRequest>()
  private val results = LinkedBlockingQueue<RenderResult>()

  @Volatile private var stopped = false

  private val worker: Thread =
    Thread(
        {
          while (!stopped) {
            when (val req = queue.poll(50, TimeUnit.MILLISECONDS)) {
              null -> continue
              is RenderRequest.Render -> {
                lastPayload.set(req.payload)
                results.put(
                  RenderResult(
                    id = req.id,
                    classLoaderHashCode = 0,
                    classLoaderName = "theme-provider-override-encoding-test",
                  )
                )
              }
              RenderRequest.Shutdown -> return@Thread
            }
          }
        },
        "payload-capturing-theme-provider-host",
      )
      .apply { isDaemon = true }

  override fun start() {
    worker.start()
  }

  override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult {
    require(request is RenderRequest.Render)
    queue.put(request)
    return results.poll(timeoutMs, TimeUnit.MILLISECONDS)
      ?: error("PayloadCapturingThemeProviderHost.submit timed out")
  }

  override fun shutdown(timeoutMs: Long) {
    stopped = true
    queue.put(RenderRequest.Shutdown)
    worker.join(timeoutMs)
  }
}
