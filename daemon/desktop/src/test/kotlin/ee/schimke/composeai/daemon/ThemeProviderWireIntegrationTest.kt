package ee.schimke.composeai.daemon

import java.io.ByteArrayInputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end regression test for the app-declared theme axis over the **real JSON-RPC wire**.
 *
 * [OverrideIntegrationTest.themeProviderOverrideWrapsPreviewInDeclaredTheme] already proves the
 * renderer honours `spec.overrides.themeProvider` — but it hand-builds the `overrides=<base64>`
 * token, so it never exercised `JsonRpcServer.encodeRenderPayload`. That encoder enumerates the
 * fields it packs into the bag, and `themeProvider` was missing from the list: a
 * `renderNow.overrides.themeProvider` was silently dropped between client and renderer, and every
 * preview came back wrapped in its own `@PreviewWrapper` instead of the chosen theme. On
 * preview.coo.ee that was the Theme picker whose chips all redrew identical pixels.
 *
 * This test closes the gap the two one-sided tests left between them: it drives a real
 * [JsonRpcServer] (`initialize` → `renderNow` with `overrides.themeProvider`) against a real
 * [PreviewManifestRouter] and asserts the *pixels* changed. The wire-shape assertion lives in
 * `:daemon:core`'s `ThemeProviderOverrideEncodingTest`.
 */
class ThemeProviderWireIntegrationTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  /** The declared theme's primary — see `RedFixturePreviews.BluePrimaryThemeProvider`. */
  private val themePrimaryRgb = 0x1565C0

  @Test(timeout = 120_000)
  fun themeProviderOverrideSurvivesTheRenderNowWire() {
    val outputDir = tempFolder.newFolder("renders-theme-wire")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)

    val default = renderOverJsonRpc(overridesJson = "{}")
    val themed =
      renderOverJsonRpc(
        overridesJson =
          """{"themeProvider":"ee.schimke.composeai.daemon.BluePrimaryThemeProvider"}"""
      )

    val themedBluePct = pixelMatchPct(themed, themePrimaryRgb, perChannelTolerance = 8)
    assertTrue(
      "a renderNow.overrides.themeProvider must reach the renderer and paint the declared " +
        "theme's primary; got ${"%.2f".format(themedBluePct * 100)}% — the encoder dropped the " +
        "field again",
      themedBluePct >= 0.95,
    )
    assertNotEquals(
      "the themed render must differ from the default-theme render",
      default.getRGB(default.width / 2, default.height / 2),
      themed.getRGB(themed.width / 2, themed.height / 2),
    )

    // Before/after PNGs for the PR's visual evidence, mirroring
    // `OverrideIntegrationTest`'s `build/theme-evidence/`.
    val evidenceDir = File("build/theme-wire-evidence").apply { mkdirs() }
    ImageIO.write(default, "png", File(evidenceDir, "wire-theme-default.png"))
    ImageIO.write(themed, "png", File(evidenceDir, "wire-theme-selected.png"))
  }

  /**
   * Runs `initialize` → `initialized` → `renderNow(overrides = [overridesJson])` against a
   * [JsonRpcServer] whose [RenderHost] is a real [PreviewManifestRouter], and returns the decoded
   * PNG the render produced. Server output is drained rather than parsed — the recording host below
   * signals completion, so the test needs no access to `:daemon:core`'s internal frame reader.
   */
  private fun renderOverJsonRpc(overridesJson: String): java.awt.image.BufferedImage {
    val sourceKt = java.nio.file.Files.createTempFile("theme-provider-wire-test", ".kt")
    java.nio.file.Files.writeString(sourceKt, "@Preview fun WallpaperAwareSquare() {}\n")
    val previewId = "ambient-primary"
    val index =
      PreviewIndex.fromMap(
        path = sourceKt,
        byId =
          mapOf(
            previewId to
              PreviewInfoDto(
                id = previewId,
                className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
                methodName = "WallpaperAwareSquare",
                sourceFile = sourceKt.toAbsolutePath().toString(),
              )
          ),
      )
    val manifest =
      PreviewManifest(
        previews =
          listOf(
            PreviewManifestEntry(
              id = previewId,
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "WallpaperAwareSquare",
              widthPx = 200,
              heightPx = 120,
              density = 1.0f,
              outputBaseName = previewId,
            )
          )
      )

    val clientToServerOut = PipedOutputStream()
    val clientToServerIn = PipedInputStream(clientToServerOut, 64 * 1024)
    val serverToClientOut = PipedOutputStream()
    val serverToClientIn = PipedInputStream(serverToClientOut, 64 * 1024)

    val router = PreviewManifestRouter(manifest = manifest)
    val host = RecordingRenderHost(router)
    val server =
      JsonRpcServer(
        input = clientToServerIn,
        output = serverToClientOut,
        host = host,
        daemonVersion = "test",
        previewIndex = index,
        onExit = {},
      )
    val serverThread =
      Thread({ server.run() }, "theme-provider-wire-test").apply { isDaemon = true }
    serverThread.start()
    // Drain the server's replies; nothing here needs to read them.
    Thread(
        {
          try {
            val buf = ByteArray(4096)
            while (serverToClientIn.read(buf) >= 0) {}
          } catch (_: Throwable) {}
        },
        "theme-provider-wire-test-drain",
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
      writeFrame(clientToServerOut, """{"jsonrpc":"2.0","method":"initialized","params":{}}""")
      writeFrame(
        clientToServerOut,
        """{"jsonrpc":"2.0","id":2,"method":"renderNow","params":{
              "previews":["$previewId"],"tier":"fast","overrides":$overridesJson}}""",
      )
      assertTrue(
        "the render must complete within the timeout",
        host.completed.await(90, TimeUnit.SECONDS),
      )
      val pngPath = host.lastResult.get()?.pngPath
      assertNotNull("pngPath must be populated", pngPath)
      val pngFile = File(pngPath!!)
      assertTrue("rendered PNG must exist", pngFile.exists())
      return ByteArrayInputStream(pngFile.readBytes()).use { ImageIO.read(it) }
        ?: error("PNG failed to decode")
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

  /** Fraction of pixels within [perChannelTolerance] of the expected `0xRRGGBB`. */
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
        if (
          abs(((rgb shr 16) and 0xFF) - expR) <= perChannelTolerance &&
            abs(((rgb shr 8) and 0xFF) - expG) <= perChannelTolerance &&
            abs((rgb and 0xFF) - expB) <= perChannelTolerance
        ) {
          matches++
        }
      }
    }
    return matches.toDouble() / (img.width.toLong() * img.height.toLong()).toDouble()
  }
}

/**
 * Delegates to the real [PreviewManifestRouter] and records the [RenderResult] so the test can read
 * the rendered PNG without parsing the daemon's `renderFinished` notification.
 */
private class RecordingRenderHost(private val delegate: PreviewManifestRouter) : RenderHost {
  val lastResult: AtomicReference<RenderResult?> = AtomicReference(null)
  val completed = CountDownLatch(1)

  override fun start() = delegate.start()

  override fun submit(request: RenderRequest, timeoutMs: Long): RenderResult {
    try {
      val result = delegate.submit(request, timeoutMs)
      lastResult.set(result)
      return result
    } finally {
      // Release the waiter on a throwing render too, so a broken render surfaces as a missing
      // pngPath rather than as an opaque await timeout.
      completed.countDown()
    }
  }

  override fun shutdown(timeoutMs: Long) = delegate.shutdown(timeoutMs)
}
