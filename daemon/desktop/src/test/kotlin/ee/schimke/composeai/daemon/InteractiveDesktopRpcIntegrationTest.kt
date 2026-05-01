package ee.schimke.composeai.daemon

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v2 PR-2 desktop integration test — the honest "click reaches composition" assertion documented
 * in [INTERACTIVE.md § 9.9.2](../../../../../../docs/daemon/INTERACTIVE.md).
 *
 * Drives a stateful composable ([ClickToGreenSquare]) through the full JSON-RPC interactive
 * lifecycle:
 * 1. `initialize` + `initialized`
 * 2. `interactive/start` for the click-to-green preview — host allocates a [DesktopInteractiveSession]
 *    holding a warm scene with `LocalInspectionMode = false`.
 * 3. Pre-render via `renderNow` so we have a baseline PNG to compare against; assert the rendered
 *    surface is mostly red.
 * 4. `interactive/input` (kind: click) at coordinates inside the surface — the session dispatches
 *    `Press` + `Release` through `ImageComposeScene.sendPointerEvent`, the click flips the
 *    `remember`'d `mutableStateOf`, the re-render encodes a fresh PNG.
 * 5. Assert the new PNG is mostly green — proving the input round-tripped end-to-end and the
 *    held composition retained the state mutation across the input.
 * 6. `interactive/stop` releases the session.
 *
 * **Why a held scene matters.** A v1 one-shot render path (which composes from scratch on each
 * input) would reset the `remember { mutableStateOf(false) }` to `false` on every render and the
 * test would forever see red. The fact that the post-click render observes green is the
 * load-bearing proof of v2: the scene survives, the state mutation sticks, and the encoded PNG
 * reflects the new composition.
 *
 * **Why `LocalInspectionMode = false` matters.** `Modifier.clickable` no-ops when inspection mode
 * is true (Compose's inspection-mode contract). v2's [DesktopInteractiveSession] sets up its
 * scene with `runInspectionMode = false` precisely so click handlers fire. Flipping that to
 * `true` here would silently fail the assertion — the click would arrive but the modifier
 * wouldn't dispatch.
 *
 * The harness scaffolding (piped streams + Content-Length frame reader) mirrors
 * [JsonRpcDesktopIntegrationTest] and the FakeHost-driven plumbing test in `:daemon:core`'s
 * [ee.schimke.composeai.daemon.InteractiveSessionPlumbingTest].
 */
class InteractiveDesktopRpcIntegrationTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private val json = Json { ignoreUnknownKeys = true }

  @Test(timeout = 120_000)
  fun click_reaches_composition_red_becomes_green() {
    val outputDir = tempFolder.newFolder("renders")
    val engine = RenderEngine(outputDir = outputDir)
    val host = ClickToGreenRoutingHost(engine = engine)

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
        daemonVersion = "test-desktop-interactive",
        onExit = { _ -> exitLatch.countDown() },
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
      // 1. initialize + initialized.
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
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","method":"initialized","params":{}}""")

      // 2. interactive/start. Host allocates a held DesktopInteractiveSession with
      //    LocalInspectionMode = false — Modifier.clickable inside the fixture only fires when
      //    inspection mode is off.
      writeFrame(
        clientToServerOut,
        """
        {"jsonrpc":"2.0","id":2,"method":"interactive/start","params":{
                  "previewId":"click-to-green"
                }}
        """
          .trimIndent(),
      )
      val startResponse = pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 2 }
      assertNotNull("interactive/start response should arrive", startResponse)
      val streamId =
        startResponse!!["result"]!!.jsonObject["frameStreamId"]!!.jsonPrimitive.contentOrNull!!

      // 3. Pre-render via renderNow so we have a baseline PNG. The host's submit() override re-
      //    routes the JsonRpcServer's `previewId=click-to-green` payload into a parseable spec
      //    pointing at the same fixture; the renderFinished bytes encode the current composition
      //    — red, since the click hasn't fired yet.
      writeFrame(
        clientToServerOut,
        """
        {"jsonrpc":"2.0","id":3,"method":"renderNow","params":{
                  "previews":["click-to-green"],"tier":"fast"
                }}
        """
          .trimIndent(),
      )
      pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 3 }
      val baselineFinished =
        pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "renderFinished" }
      assertNotNull("baseline renderFinished must arrive", baselineFinished)
      val baselinePngPath =
        baselineFinished!!["params"]!!
          .jsonObject["pngPath"]
          ?.jsonPrimitive
          ?.contentOrNull!!
      val baselineFile = File(baselinePngPath)
      assertTrue("baseline PNG must exist on disk: $baselinePngPath", baselineFile.exists())
      assertMostlyColor(baselineFile, "baseline (pre-click)", expectedRgb = 0xEF5350)

      // 4. interactive/input — click roughly in the centre of the 64x64 surface. Density on the
      //    fixture is 1.0 so image-natural pixels equal scene px directly. The session dispatches
      //    Press + Release back-to-back at the same position (CLICK semantics).
      writeFrame(
        clientToServerOut,
        """
        {"jsonrpc":"2.0","method":"interactive/input","params":{
                  "frameStreamId":"$streamId","kind":"click",
                  "pixelX":32,"pixelY":32
                }}
        """
          .trimIndent(),
      )
      val postClickFinished =
        pollUntil(received) { it["method"]?.jsonPrimitive?.contentOrNull == "renderFinished" }
      assertNotNull("post-click renderFinished must arrive", postClickFinished)
      val postClickPngPath =
        postClickFinished!!["params"]!!
          .jsonObject["pngPath"]
          ?.jsonPrimitive
          ?.contentOrNull!!
      val postClickFile = File(postClickPngPath)
      assertTrue(
        "post-click PNG must exist on disk: $postClickPngPath",
        postClickFile.exists(),
      )
      // The load-bearing assertion: the click flipped the remember'd state and the held scene
      // re-rendered green.
      assertMostlyColor(postClickFile, "post-click", expectedRgb = 0x66BB6A)

      // 5. interactive/stop releases the held session.
      writeFrame(
        clientToServerOut,
        """
        {"jsonrpc":"2.0","method":"interactive/stop","params":{
                  "frameStreamId":"$streamId"
                }}
        """
          .trimIndent(),
      )
      // No response expected (notification); give the daemon a tick to release the session.
      Thread.sleep(50)

      // 6. shutdown + exit.
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","id":99,"method":"shutdown"}""")
      pollUntil(received) { it["id"]?.jsonPrimitive?.intOrNull == 99 }
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","method":"exit"}""")
      assertTrue(
        "server should invoke onExit() within 30s of exit notification",
        exitLatch.await(30, TimeUnit.SECONDS),
      )
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

  // ----- assertion helpers -----

  private fun assertMostlyColor(pngFile: File, label: String, expectedRgb: Int) {
    val bytes = pngFile.readBytes()
    val img = ByteArrayInputStream(bytes).use { ImageIO.read(it) }
    assertNotNull("$label PNG must decode via javax.imageio", img)
    // Tolerance 32: ImageComposeScene renders against a transparent backing surface, so the
    // tinted material colours come back several LSB darker than the literal hex (e.g. #66BB6A
    // observed as #5BA85F) once Skia has composited and the AWT decoder has gone through
    // ARGB → RGB unpremultiply. Wide tolerance to keep the assertion robust against rendering
    // pipeline drift; the load-bearing claim is "this is the green fixture, not the red one",
    // which a single-channel-of-32 tolerance still differentiates trivially.
    val matchPct = pixelMatchPct(img!!, expectedRgb, perChannelTolerance = 32)
    assertTrue(
      "$label: expected ≥ 90% of pixels close to #${expectedRgb.toString(16).padStart(6, '0')}; " +
        "got ${"%.2f".format(matchPct * 100)}%",
      matchPct >= 0.90,
    )
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

  // ----- piped-stream + framer scaffolding (mirrors JsonRpcDesktopIntegrationTest) -----

  private fun writeFrame(out: PipedOutputStream, json: String) {
    val payload = json.toByteArray(Charsets.UTF_8)
    out.write("Content-Length: ${payload.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
    out.write(payload)
    out.flush()
  }

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
 * Test [DesktopHost] subclass that:
 * - resolves the previewId `click-to-green` to a [RenderSpec] for [ClickToGreenSquare] via the v2
 *   [DesktopHost.resolveInteractiveSpec] hook so `interactive/start` allocates a real held scene,
 * - rewrites the inbound non-interactive `submit()` payload (which `JsonRpcServer.handleRenderNow`
 *   ships as `previewId=click-to-green` only — see the file KDoc on [JsonRpcDesktopIntegrationTest])
 *   to a parseable spec so the baseline `renderNow` produces a real PNG.
 *
 * Mirrors the `SpecRoutingHost` pattern from [JsonRpcDesktopIntegrationTest]; the resolveInteractiveSpec
 * override is the v2 addition. Both routes (interactive + non-interactive renderNow) target the same
 * fixture composable so the baseline-vs-post-click PNG comparison is apples-to-apples.
 */
private class ClickToGreenRoutingHost(engine: RenderEngine) : DesktopHost(engine = engine) {

  override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult {
    require(request !is RenderRequest.Shutdown) {
      "Use shutdown() to stop the host, not submit(Shutdown)."
    }
    val typed = request as RenderRequest.Render
    val routed =
      RenderRequest.Render(
        id = typed.id,
        payload =
          "className=ee.schimke.composeai.daemon.RedFixturePreviewsKt;" +
            "functionName=ClickToGreenSquare;" +
            "widthPx=64;heightPx=64;density=1.0;" +
            "showBackground=true;" +
            "outputBaseName=click-to-green-${typed.id}",
      )
    return super.submit(routed, timeoutMs)
  }

  override fun resolveInteractiveSpec(previewId: String): RenderSpec? =
    if (previewId == "click-to-green") {
      RenderSpec(
        className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
        functionName = "ClickToGreenSquare",
        widthPx = 64,
        heightPx = 64,
        density = 1.0f,
        showBackground = true,
        outputBaseName = "click-to-green-interactive",
      )
    } else null
}
