package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RecordingFormat
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * P5 — end-to-end test for [AndroidRecordingSession] (the Robolectric-backed scripted screen-
 * recording surface). Boots a 2-sandbox `RobolectricHost`, allocates a recording session against
 * the [ClickToggleSquare] fixture (red → green on first click), posts a click at `tMs = 67`, runs
 * `stop()`, and asserts:
 *
 * 1. Frame count = 4 (ceil(67 ms × 30 fps / 1000) + 1, with frame `tMs`'s of 0, 33, 66, 100).
 * 2. Frame 0 paints red — pre-click state at tMs=0.
 * 3. Frame 3 paints green — click@67 was drained at the first frame at or after 67 ms.
 * 4. APNG encode produces a non-empty file with the standard PNG header bytes.
 *
 * **Test cost.** This test pays the same ~2× cold-boot wall-clock as
 * [AndroidInteractiveSessionTest] (each sandbox download + instrumentation independent on a cold
 * cache) plus 4 paused-clock captures × ~500–1000 ms each. ~30 s on a warm cache is realistic;
 * we deliberately don't multiply this across more recording scenarios — the broader scripted
 * coverage stays on the desktop side where renders are sub-100 ms each.
 *
 * Pixel-match helper inlined for the same reason `AndroidInteractiveSessionTest` and
 * `RenderEngineTest` inline theirs — pulling `:daemon:harness`'s `PixelDiff` would invert the
 * dependency graph.
 */
class AndroidRecordingSessionTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun scriptedClickFlipsStateAndProducesFrames() {
    val outputDir = tempFolder.newFolder("recording-renders")
    val recordingsRoot = tempFolder.newFolder("recordings-root")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty(RobolectricHost.RECORDINGS_DIR_PROP, recordingsRoot.absolutePath)
    System.setProperty("roborazzi.test.record", "true")

    val host = RobolectricHost(sandboxCount = 2, previewSpecResolver = previewSpecResolver())
    host.start()
    try {
      assertTrue(
        "supportsRecording must be true with sandboxCount=2 + resolver wired",
        host.supportsRecording,
      )
      assertTrue(
        "supportedRecordingFormats must include apng when recording is supported",
        "apng" in host.supportedRecordingFormats,
      )

      val session =
        host.acquireRecordingSession(
          previewId = INTERACTIVE_PREVIEW_ID,
          recordingId = "test-rec-android",
          classLoader = AndroidRecordingSessionTest::class.java.classLoader!!,
          fps = FPS,
          scale = 1.0f,
          overrides = null,
          live = false,
        )
      try {
        // Click at tMs=67 lands just after the 66 ms frame boundary. The frame-count math must
        // ceil the script duration so frame 3 (100 ms) exists and drains the event.
        session.postScript(
          listOf(
            RecordingScriptEvent(
              tMs = 67L,
              kind = InteractiveInputKind.CLICK,
              pixelX = INTERACTIVE_WIDTH_PX / 2,
              pixelY = INTERACTIVE_HEIGHT_PX / 2,
            )
          )
        )
        val result = session.stop()

        // 1. Frame count: ceil(67ms × 30fps / 1000) + 1 = 4 frames.
        assertEquals(
          "expected 4 frames at 30 fps over 67 ms timeline; got ${result.frameCount}",
          4,
          result.frameCount,
        )
        assertEquals("durationMs", 67L, result.durationMs)
        for (i in 0 until result.frameCount) {
          val f = File(result.framesDir, "frame-${"%05d".format(i)}.png")
          assertTrue("frame $i missing on disk: ${f.absolutePath}", f.isFile)
          assertTrue("frame $i must be non-empty PNG", f.length() > 0)
        }

        // 2. Frame 0 — pre-click; tMs=0 < 66 so no event drained yet → red.
        val frame0 = decode(File(result.framesDir, "frame-00000.png"))
        val redAt0 = pixelMatchPct(frame0, RED_RGB, perChannelTolerance = 8)
        assertTrue(
          "frame 0 expected ≥ 95% red (pre-click); got ${"%.2f".format(redAt0 * 100)}%",
          redAt0 >= 0.95,
        )

        // 3. Frame 3 — click@67 was drained at frame 3; final frame reflects the post-click
        //    composition. This is the load-bearing assertion: it proves the script event reached
        //    the held-rule loop, the state mutated, and the next render captured the new state.
        val frame3 = decode(File(result.framesDir, "frame-00003.png"))
        val greenAt3 = pixelMatchPct(frame3, GREEN_RGB, perChannelTolerance = 8)
        assertTrue(
          "frame 3 expected ≥ 95% green (post-click); got ${"%.2f".format(greenAt3 * 100)}% — " +
            "either dispatchTouchEvent didn't reach pointerInput across the bridge or the held " +
            "composition didn't capture the post-click state",
          greenAt3 >= 0.95,
        )

        // 4. APNG encode round-trip — the agent's eventual `recording/encode` path.
        val encoded = session.encode(RecordingFormat.APNG)
        assertEquals("image/apng", encoded.mimeType)
        val apngFile = File(encoded.videoPath)
        assertTrue("APNG file must exist on disk", apngFile.isFile)
        assertTrue("APNG file must be non-empty", encoded.sizeBytes > 0)
        val firstEight = apngFile.readBytes().copyOf(8)
        val expectedHeader = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        assertTrue(
          "APNG must carry the PNG header; got ${firstEight.joinToString { "0x%02x".format(it) }}",
          firstEight.contentEquals(expectedHeader),
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun acquireWithLiveTrueThrowsUnsupported() {
    // Live mode is host-side rejected on Android — JsonRpcServer translates the throw to
    // MethodNotFound on the wire, agents see "live recording not supported on Android".
    val host = RobolectricHost(sandboxCount = 2, previewSpecResolver = previewSpecResolver())
    host.start()
    try {
      try {
        host.acquireRecordingSession(
          previewId = INTERACTIVE_PREVIEW_ID,
          recordingId = "test-rec-live-rejected",
          classLoader = AndroidRecordingSessionTest::class.java.classLoader!!,
          fps = FPS,
          scale = 1.0f,
          overrides = null,
          live = true,
        )
        fail("expected UnsupportedOperationException for live recording on Android")
      } catch (expected: UnsupportedOperationException) {
        assertNotNull(expected.message)
        assertTrue(
          "error message should mention live recording is unsupported on Android",
          expected.message!!.contains("live recording", ignoreCase = true),
        )
      }
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun acquireWithOverridesAppliesFrameSize() {
    val outputDir = tempFolder.newFolder("recording-override-renders")
    val recordingsRoot = tempFolder.newFolder("recording-override-root")
    System.setProperty(RenderEngine.OUTPUT_DIR_PROP, outputDir.absolutePath)
    System.setProperty(RobolectricHost.RECORDINGS_DIR_PROP, recordingsRoot.absolutePath)
    System.setProperty("roborazzi.test.record", "true")

    val host = RobolectricHost(sandboxCount = 2, previewSpecResolver = previewSpecResolver())
    host.start()
    try {
      val session =
        host.acquireRecordingSession(
          previewId = INTERACTIVE_PREVIEW_ID,
          recordingId = "test-rec-android-overrides",
          classLoader = AndroidRecordingSessionTest::class.java.classLoader!!,
          fps = FPS,
          scale = 1.0f,
          overrides = PreviewOverrides(widthPx = 48, heightPx = 64, density = 1.0f),
          live = false,
        )
      try {
        val result = session.stop()
        assertEquals(1, result.frameCount)
        assertEquals(48, result.frameWidthPx)
        assertEquals(64, result.frameHeightPx)
        val frame0 = decode(File(result.framesDir, "frame-00000.png"))
        assertEquals(48, frame0.width)
        assertEquals(64, frame0.height)
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun acquireWithSandboxCountOneThrowsUnsupported() {
    // Same gating as interactive: sandboxCount=1 refuses recording too because it rides on the
    // held-rule loop's slot pinning.
    val host = RobolectricHost(sandboxCount = 1, previewSpecResolver = previewSpecResolver())
    assertEquals(
      "supportsRecording must be false with sandboxCount=1",
      false,
      host.supportsRecording,
    )
    host.start()
    try {
      try {
        host.acquireRecordingSession(
          previewId = INTERACTIVE_PREVIEW_ID,
          recordingId = "test-rec-no-slot",
          classLoader = AndroidRecordingSessionTest::class.java.classLoader!!,
          fps = FPS,
          scale = 1.0f,
          overrides = null,
          live = false,
        )
        fail("expected UnsupportedOperationException for sandboxCount=1")
      } catch (expected: UnsupportedOperationException) {
        assertNotNull(expected.message)
      }
    } finally {
      host.shutdown()
    }
  }

  private fun previewSpecResolver(): (String) -> RenderSpec? = { previewId ->
    if (previewId == INTERACTIVE_PREVIEW_ID) {
      RenderSpec(
        className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
        functionName = "ClickToggleSquare",
        widthPx = INTERACTIVE_WIDTH_PX,
        heightPx = INTERACTIVE_HEIGHT_PX,
        density = 1.0f,
        showBackground = true,
        outputBaseName = "recording-clicktoggle",
      )
    } else null
  }

  private fun decode(file: File): java.awt.image.BufferedImage {
    require(file.exists()) { "expected capture at ${file.absolutePath}" }
    val bytes = file.readBytes()
    require(bytes.isNotEmpty()) { "capture is empty: ${file.absolutePath}" }
    return ByteArrayInputStream(bytes).use { ImageIO.read(it) }
      ?: error("ImageIO refused to decode capture: ${file.absolutePath}")
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
    return matches.toDouble() / (img.width.toLong() * img.height.toLong()).toDouble()
  }

  companion object {
    private const val INTERACTIVE_PREVIEW_ID = "recording-clicktoggle"
    private const val INTERACTIVE_WIDTH_PX = 96
    private const val INTERACTIVE_HEIGHT_PX = 96
    private const val FPS = 30
    private const val RED_RGB = 0xEF5350
    private const val GREEN_RGB = 0x66BB6A
  }
}
