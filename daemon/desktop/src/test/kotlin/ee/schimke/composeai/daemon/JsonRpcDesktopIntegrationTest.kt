package ee.schimke.composeai.daemon

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end test for [JsonRpcServer] over piped streams against a real
 * [DesktopHost] + [RenderEngine] (B-desktop.1.5 DoD).
 *
 * Mirrors `:daemon:core`'s [JsonRpcServerIntegrationTest][ee.schimke.composeai.daemon
 * .JsonRpcServerIntegrationTest], but the host is the real desktop backend rather than the in-test
 * `FakeRenderHost`. Drives the full happy-path lifecycle:
 *
 * `initialize → initialized → renderNow → renderStarted → renderFinished → shutdown → exit`
 *
 * **In-process, not subprocess.** B-desktop.1.5's DoD asked for a `ProcessBuilder`-spawned daemon
 * JVM. We deferred — same reasoning as B1.5's `JsonRpcServerIntegrationTest`:
 *
 * 1. Subprocess plumbing (ProcessBuilder + descriptor wiring) duplicates work that
 *    `:daemon:harness`'s `HarnessClient` already implements; D-harness.v1.5 will hit this code path
 *    against a real spawned daemon as part of its `-Pharness.host=real` flip.
 * 2. The wire-layer round-trip + real-render assertion the DoD wants to prove are fully exercised
 *    in-process. A subprocess would only add `ProcessBuilder` plumbing on top.
 *
 * **JsonRpcServer ignores previewId when constructing RenderRequest.** v1's renderer-agnostic
 * surface (`:daemon:core`) doesn't carry a typed preview-id field on `RenderRequest`;
 * `JsonRpcServer.handleRenderNow` keeps the previewId in its own `hostIdToPreviewId` map (used to
 * label `renderFinished.id` on the way out) but submits `RenderRequest.Render(id=hostId)` with an
 * empty payload. That means the host has no way to learn which preview to render from the request
 * alone. Widening the renderer-agnostic surface is documented as a follow-up — for v1, this test
 * routes inbound submissions to a fixed test fixture via a wrapping [SpecRoutingHost] that
 * substitutes a parseable spec payload. A future "JsonRpcServer + manifest router" integration
 * (B2.2 once the daemon owns its own `previews.json`) replaces both this shim and the inbound
 * payload's emptiness.
 *
 * **Real Compose render in a unit test.** Skiko native init runs once per JVM and is wired through
 * `:renderer-desktop`'s transitive `compose.desktop.currentOs`; this works on the developer
 * machines / CI runners we care about for daemon-harness-desktop. If a future runner environment
 * has trouble with Skiko native bootstrap inside a JUnit JVM, the fall-back is to use a no-op
 * preview fixture and assert only the wire round-trip — flag explicitly which path was taken.
 */
class JsonRpcDesktopIntegrationTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private val json = Json { ignoreUnknownKeys = true }

  @Test(timeout = 120_000)
  fun full_lifecycle_renders_one_real_preview_and_emits_finished_notification() {
    val outputDir = tempFolder.newFolder("renders")
    val engine = RenderEngine(outputDir = outputDir)
    val host = SpecRoutingHost(engine = engine)

    val clientToServerOut = PipedOutputStream()
    val clientToServerIn = PipedInputStream(clientToServerOut, 64 * 1024)
    val serverToClientOut = PipedOutputStream()
    val serverToClientIn = PipedInputStream(serverToClientOut, 64 * 1024)

    val exitCode = AtomicInteger(-1)
    val exitLatch = CountDownLatch(1)
    val server =
      JsonRpcServer(
        input = clientToServerIn,
        output = serverToClientOut,
        host = host,
        daemonVersion = "test-desktop",
        onExit = { code ->
          exitCode.set(code)
          exitLatch.countDown()
        },
      )
    val serverThread = Thread({ server.run() }, "json-rpc-desktop-test").apply { isDaemon = true }
    serverThread.start()

    val received = LinkedBlockingQueue<JsonObject>()
    val readerThread =
      Thread(
          {
            try {
              while (true) {
                val frame = readContentLengthFrame(serverToClientIn) ?: break
                val obj = json.parseToJsonElement(frame.toString(Charsets.UTF_8)).jsonObject
                received.put(obj)
              }
            } catch (_: Throwable) {
              // EOF / pipe close — fine, test asserts on what we got.
            }
          },
          "json-rpc-desktop-test-reader",
        )
        .apply { isDaemon = true }
    readerThread.start()

    try {
      // 1. initialize
      writeFrame(
        clientToServerOut,
        """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":1,
                  "clientVersion":"test",
                  "workspaceRoot":"/tmp",
                  "moduleId":":test",
                  "moduleProjectDir":"/tmp",
                  "capabilities":{"visibility":true,"metrics":false}
                }}
        """
          .trimIndent(),
      )
      val initResponse = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 1 }
      assertNotNull("initialize response should arrive", initResponse)
      assertEquals(
        "test-desktop",
        initResponse!!["result"]!!.jsonObject["daemonVersion"]?.jsonPrimitive?.contentOrNull,
      )

      // 2. initialized notification
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","method":"initialized","params":{}}""")

      // 3. renderNow for one preview. The wrapping SpecRoutingHost translates the previewId into
      //    a real RenderEngine render against our test fixture.
      writeFrame(
        clientToServerOut,
        """
        {"jsonrpc":"2.0","id":2,"method":"renderNow","params":{
                  "previews":["red-square"],
                  "tier":"fast"
                }}
        """
          .trimIndent(),
      )
      val renderResponse = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 2 }
      assertNotNull("renderNow response should arrive", renderResponse)
      assertTrue(
        renderResponse!!["result"]!!.jsonObject["queued"].toString().contains("red-square")
      )

      // 4. renderStarted + renderFinished notifications.
      val started =
        pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "renderStarted" }
      assertNotNull("renderStarted notification should arrive", started)
      assertEquals(
        "red-square",
        started!!["params"]!!.jsonObject["id"]?.jsonPrimitive?.contentOrNull,
      )

      val finished =
        pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "renderFinished" }
      assertNotNull("renderFinished notification should arrive", finished)
      val finishedParams = finished!!["params"]!!.jsonObject
      assertEquals("red-square", finishedParams["id"]?.jsonPrimitive?.contentOrNull)
      val pngPath = finishedParams["pngPath"]?.jsonPrimitive?.contentOrNull
      assertNotNull("pngPath must be populated by the real render body", pngPath)
      assertTrue(
        "pngPath should NOT be the stub placeholder once B-desktop.1.5 wires the real engine, " +
          "got '$pngPath'",
        !pngPath!!.contains("daemon-stub-"),
      )
      val pngFile = File(pngPath)
      assertTrue("rendered PNG must exist on disk: $pngPath", pngFile.exists())
      assertTrue("rendered PNG must be non-empty", pngFile.length() > 0)

      // 5. shutdown — drain in-flight (already drained here) and respond with null result.
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","id":3,"method":"shutdown"}""")
      val shutdownResponse = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 3 }
      assertNotNull("shutdown response should arrive", shutdownResponse)
      assertNull(
        "shutdown result must be null per PROTOCOL.md § 3",
        shutdownResponse!!["result"]?.let {
          if (it is JsonPrimitive && it.contentOrNull == null) null else it
        },
      )

      // 6. exit — server should call onExit(0).
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","method":"exit"}""")
      assertTrue(
        "server should invoke onExit() within 30s of exit notification",
        exitLatch.await(30, TimeUnit.SECONDS),
      )
      assertEquals(0, exitCode.get())
    } finally {
      try {
        clientToServerOut.close()
      } catch (_: Throwable) {}
      try {
        serverToClientIn.close()
      } catch (_: Throwable) {}
      serverThread.join(15_000)
    }
  }

  @Test(timeout = 120_000)
  fun interactive_click_over_json_rpc_reaches_real_desktop_composition() {
    val outputDir = tempFolder.newFolder("interactive-renders")
    val engine = RenderEngine(outputDir = outputDir)
    val host =
      SpecRoutingHost(
        engine = engine,
        specs =
          mapOf(
            "click-toggle-square" to
              RenderSpec(
                className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
                functionName = "ClickToggleSquare",
                widthPx = 64,
                heightPx = 64,
                density = 1.0f,
                outputBaseName = "click-toggle-square",
              )
          ),
      )

    val clientToServerOut = PipedOutputStream()
    val clientToServerIn = PipedInputStream(clientToServerOut, 64 * 1024)
    val serverToClientOut = PipedOutputStream()
    val serverToClientIn = PipedInputStream(serverToClientOut, 64 * 1024)

    val exitCode = AtomicInteger(-1)
    val exitLatch = CountDownLatch(1)
    val server =
      JsonRpcServer(
        input = clientToServerIn,
        output = serverToClientOut,
        host = host,
        daemonVersion = "test-desktop",
        interactiveFrameIntervalMs = 0L,
        onExit = { code ->
          exitCode.set(code)
          exitLatch.countDown()
        },
      )
    val serverThread =
      Thread({ server.run() }, "json-rpc-desktop-interactive-test").apply { isDaemon = true }
    serverThread.start()

    val received = LinkedBlockingQueue<JsonObject>()
    val readerThread =
      Thread(
          {
            try {
              while (true) {
                val frame = readContentLengthFrame(serverToClientIn) ?: break
                val obj = json.parseToJsonElement(frame.toString(Charsets.UTF_8)).jsonObject
                received.put(obj)
              }
            } catch (_: Throwable) {
              // EOF / pipe close — fine, test asserts on what we got.
            }
          },
          "json-rpc-desktop-interactive-test-reader",
        )
        .apply { isDaemon = true }
    readerThread.start()

    try {
      initialize(clientToServerOut, received)
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","method":"initialized","params":{}}""")

      writeFrame(
        clientToServerOut,
        """
        {"jsonrpc":"2.0","id":2,"method":"renderNow","params":{
                  "previews":["click-toggle-square"],
                  "tier":"fast"
                }}
        """
          .trimIndent(),
      )
      assertNotNull(
        "renderNow response should arrive",
        pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 2 },
      )
      val bootstrapFinished =
        pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "renderFinished" }
      assertNotNull("bootstrap renderFinished should arrive", bootstrapFinished)
      val bootstrapPng =
        bootstrapFinished!!["params"]!!.jsonObject["pngPath"]?.jsonPrimitive?.contentOrNull
      assertNotNull("bootstrap pngPath must be populated", bootstrapPng)
      assertMostlyColor(
        file = File(bootstrapPng!!),
        expectedRgb = 0xEF5350,
        label = "bootstrap render",
      )

      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":3,"method":"interactive/start","params":{"previewId":"click-toggle-square"}}""",
      )
      val startResp = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 3 }
      assertNotNull("interactive/start response should arrive", startResp)
      val streamId =
        startResp!!["result"]!!.jsonObject["frameStreamId"]!!.jsonPrimitive.contentOrNull
      assertTrue("frameStreamId should be non-blank", !streamId.isNullOrBlank())

      writeFrame(
        clientToServerOut,
        """
        {"jsonrpc":"2.0","method":"interactive/input","params":{
          "frameStreamId":"$streamId","kind":"click","pixelX":32,"pixelY":32
        }}
        """
          .trimIndent(),
      )
      val postClickFinished =
        pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "renderFinished" }
      assertNotNull("post-click renderFinished should arrive", postClickFinished)
      val postClickParams = postClickFinished!!["params"]!!.jsonObject
      assertEquals("click-toggle-square", postClickParams["id"]?.jsonPrimitive?.contentOrNull)
      val postClickPng = postClickParams["pngPath"]?.jsonPrimitive?.contentOrNull
      assertNotNull("post-click pngPath must be populated", postClickPng)
      assertMostlyColor(
        file = File(postClickPng!!),
        expectedRgb = 0x66BB6A,
        label = "post-click render",
      )

      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","method":"interactive/stop","params":{"frameStreamId":"$streamId"}}""",
      )

      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","id":4,"method":"shutdown"}""")
      assertNotNull(
        "shutdown response should arrive",
        pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 4 },
      )
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","method":"exit"}""")
      assertTrue(
        "server should invoke onExit() within 30s of exit notification",
        exitLatch.await(30, TimeUnit.SECONDS),
      )
      assertEquals(0, exitCode.get())
    } finally {
      try {
        clientToServerOut.close()
      } catch (_: Throwable) {}
      try {
        serverToClientIn.close()
      } catch (_: Throwable) {}
      serverThread.join(15_000)
    }
  }

  private fun initialize(out: PipedOutputStream, received: LinkedBlockingQueue<JsonObject>) {
    writeFrame(
      out,
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                "protocolVersion":1,
                "clientVersion":"test",
                "workspaceRoot":"/tmp",
                "moduleId":":test",
                "moduleProjectDir":"/tmp",
                "capabilities":{"visibility":true,"metrics":false}
              }}
      """
        .trimIndent(),
    )
    val initResponse = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 1 }
    assertNotNull("initialize response should arrive", initResponse)
    assertEquals(
      "test-desktop",
      initResponse!!["result"]!!.jsonObject["daemonVersion"]?.jsonPrimitive?.contentOrNull,
    )
  }

  private fun assertMostlyColor(file: File, expectedRgb: Int, label: String) {
    val image = readPng(file)
    val match = pixelMatchPct(image, expectedRgb = expectedRgb, perChannelTolerance = 8)
    assertTrue(
      "$label should be mostly ${expectedRgb.toString(16)}; got ${"%.2f".format(match * 100)}%",
      match >= 0.95,
    )
  }

  private fun readPng(file: File): java.awt.image.BufferedImage {
    assertTrue("rendered PNG must exist on disk: ${file.absolutePath}", file.exists())
    assertTrue("rendered PNG must be non-empty", file.length() > 0)
    return ImageIO.read(file) ?: error("PNG must decode via javax.imageio: ${file.absolutePath}")
  }

  private fun pixelMatchPct(
    img: java.awt.image.BufferedImage,
    expectedRgb: Int,
    perChannelTolerance: Int,
  ): Double {
    val expR = (expectedRgb shr 16) and 0xFF
    val expG = (expectedRgb shr 8) and 0xFF
    val expB = expectedRgb and 0xFF
    var matches = 0L
    for (y in 0 until img.height) {
      for (x in 0 until img.width) {
        val rgb = img.getRGB(x, y)
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        if (
          abs(r - expR) <= perChannelTolerance &&
            abs(g - expG) <= perChannelTolerance &&
            abs(b - expB) <= perChannelTolerance
        ) {
          matches++
        }
      }
    }
    val total = img.width.toLong() * img.height.toLong()
    return matches.toDouble() / total.toDouble()
  }

  private fun writeFrame(out: PipedOutputStream, json: String) {
    val payload = json.toByteArray(Charsets.UTF_8)
    out.write("Content-Length: ${payload.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
    out.write(payload)
    out.flush()
  }

  /**
   * Hand-rolled `Content-Length:` frame reader — `:daemon:core`'s `ContentLengthFramer` is
   * `internal` to that module, so we duplicate the minimal happy-path body here. Returns null on
   * clean EOF; throws [IOException] on a malformed header (the only failure mode this test cares
   * about).
   */
  private fun readContentLengthFrame(input: InputStream): ByteArray? {
    var contentLength = -1
    var sawAny = false
    while (true) {
      val line =
        readHeaderLine(input) ?: return if (sawAny) throw IOException("EOF in headers") else null
      sawAny = true
      if (line.isEmpty()) break
      val colon = line.indexOf(':')
      if (colon <= 0) throw IOException("malformed header: '$line'")
      val name = line.substring(0, colon).trim()
      val value = line.substring(colon + 1).trim()
      if (name.equals("Content-Length", ignoreCase = true)) {
        contentLength = value.toIntOrNull() ?: throw IOException("non-int Content-Length: '$value'")
      }
    }
    if (contentLength < 0) throw IOException("missing Content-Length")
    val payload = ByteArray(contentLength)
    var off = 0
    while (off < contentLength) {
      val n = input.read(payload, off, contentLength - off)
      if (n < 0) throw IOException("EOF mid-payload")
      off += n
    }
    return payload
  }

  private fun readHeaderLine(input: InputStream): String? {
    val buf = ByteArrayOutputStream(64)
    while (true) {
      val b = input.read()
      if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.US_ASCII.name())
      if (b == '\n'.code) {
        val bytes = buf.toByteArray()
        val end =
          if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1
          else bytes.size
        return String(bytes, 0, end, Charsets.US_ASCII)
      }
      buf.write(b)
    }
  }

  private fun pollUntil(
    queue: LinkedBlockingQueue<JsonObject>,
    timeoutMs: Long = 60_000,
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
 * Wrapping [DesktopHost] that substitutes a known test-fixture spec for any inbound submission.
 * `JsonRpcServer` doesn't propagate the previewId to the host's `RenderRequest.payload` (see file
 * KDoc), so for v1 every render it submits arrives with `payload = ""`. This shim short-circuits
 * the normal payload-parsing path: it inspects the test's bookkeeping (one render expected, always
 * the red-square fixture) and re-packs the request with a parseable spec.
 *
 * Models what a future "JsonRpcServer + manifest router" integration will do — look the previewId
 * up in `previews.json` to recover the className/functionName + dimensions, then forward.
 *
 * TODO(B2.2): once `IncrementalDiscovery` lands, the daemon owns its own `previews.json` and this
 *   routing logic moves into a `PreviewManifestRouter` between server and host (or into
 *   `JsonRpcServer.handleRenderNow` directly). Until then, every test that wires `JsonRpcServer` to
 *   a real `DesktopHost` needs a routing shim like this.
 */
private class SpecRoutingHost(
  engine: RenderEngine,
  private val specs: Map<String, RenderSpec> =
    mapOf(
      "red-square" to
        RenderSpec(
          className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
          functionName = "RedSquare",
          widthPx = 64,
          heightPx = 64,
          density = 1.0f,
          showBackground = true,
          outputBaseName = "red-square",
        )
    ),
) : DesktopHost(engine = engine, previewSpecResolver = { previewId -> specs[previewId] }) {

  override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult {
    require(request !is RenderRequest.Shutdown) {
      "Use shutdown() to stop the host, not submit(Shutdown)."
    }
    val typed = request as RenderRequest.Render
    val previewId = previewIdFromPayload(typed.payload) ?: "red-square"
    val spec =
      specs[previewId]
        ?: error("SpecRoutingHost: no spec for previewId='$previewId'; known=${specs.keys}")
    val routed =
      RenderRequest.Render(
        id = typed.id,
        payload =
          "className=${spec.className};" +
            "functionName=${spec.functionName};" +
            "widthPx=${spec.widthPx};heightPx=${spec.heightPx};density=${spec.density};" +
            "showBackground=${spec.showBackground};" +
            "outputBaseName=${spec.outputBaseName}-${typed.id}",
      )
    return super.submit(routed, timeoutMs)
  }

  private fun previewIdFromPayload(payload: String): String? =
    payload
      .split(';')
      .mapNotNull { token ->
        val eq = token.indexOf('=')
        if (eq <= 0) null else token.substring(0, eq).trim() to token.substring(eq + 1).trim()
      }
      .firstOrNull { (key, value) -> key == "previewId" && value.isNotBlank() }
      ?.second
}
