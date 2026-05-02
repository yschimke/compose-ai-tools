package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.RecordingFormat
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end test for the v1 scripted screen-record surface — see
 * [RECORDING.md](../../../../../../docs/daemon/RECORDING.md).
 *
 * Drives the desktop host's [DesktopRecordingSession] against a small component-preview fixture
 * ([TristateClickSquare], 120×60 tri-state square) with a scripted timeline of two clicks at
 * virtual `tMs = 0` and `tMs = 500`. Verifies:
 *
 * 1. **Frame count** — at 30 fps over 500ms, the playback emits 16 frames (`frame-00000.png` ..
 *    `frame-00015.png`).
 * 2. **State at each click** — frame 0 paints green (state=1 after first click drains at tMs=0),
 *    frame 15 paints blue (state=2 after second click drains at tMs=500).
 * 3. **State holds between events** — frame 5 (between the two scripted clicks) still paints green.
 *    This is the load-bearing "virtual time + held scene" assertion: without virtual time, the
 *    inter-click frames would have nothing to render between the two events; without held state,
 *    the second `setUp` would reset `mutableStateOf` and frame 15 would paint green again.
 * 4. **Component-preview suitability** — the test runs against a 120×60 sandbox (not a phone- sized
 *    preview) to prove the recording path doesn't bake in screen-sized assumptions.
 * 5. **APNG encode** — `session.encode(APNG)` produces a non-empty file at the advertised path with
 *    the advertised mime type.
 *
 * Pixel-match helper inlined to avoid a circular dep on the harness's `PixelDiff` (same reasoning
 * as `DesktopInteractiveSessionTest`).
 */
class DesktopRecordingSessionTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private var savedRecordingsDir: String? = null

  @After
  fun tearDown() {
    val saved = savedRecordingsDir
    if (saved == null) System.clearProperty(DesktopHost.RECORDINGS_DIR_PROP)
    else System.setProperty(DesktopHost.RECORDINGS_DIR_PROP, saved)
  }

  @Test
  fun scripted_clicks_at_virtual_time_produce_expected_frames_and_state_survives() {
    val outputDir = tempFolder.newFolder("recording-engine-renders")
    val recordingsRoot = tempFolder.newFolder("recordings-root")
    savedRecordingsDir = System.getProperty(DesktopHost.RECORDINGS_DIR_PROP)
    System.setProperty(DesktopHost.RECORDINGS_DIR_PROP, recordingsRoot.absolutePath)

    val engine = RenderEngine(outputDir = outputDir)
    val host =
      DesktopHost(
        engine = engine,
        previewSpecResolver = { previewId ->
          if (previewId == FIXTURE_PREVIEW_ID) {
            RenderSpec(
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "TristateClickSquare",
              widthPx = COMPONENT_WIDTH_PX,
              heightPx = COMPONENT_HEIGHT_PX,
              density = 1.0f,
              outputBaseName = "tristate-click-square",
            )
          } else null
        },
      )
    host.start()
    try {
      val session =
        host.acquireRecordingSession(
          previewId = FIXTURE_PREVIEW_ID,
          recordingId = "test-rec-1",
          classLoader =
            DesktopRecordingSessionTest::class.java.classLoader
              ?: ClassLoader.getSystemClassLoader(),
          fps = FPS,
          scale = 1.0f,
          overrides = null,
        )
      try {
        session.postScript(
          listOf(
            RecordingScriptEvent(
              tMs = 0L,
              kind = InteractiveInputKind.CLICK,
              pixelX = COMPONENT_WIDTH_PX / 2,
              pixelY = COMPONENT_HEIGHT_PX / 2,
            ),
            RecordingScriptEvent(
              tMs = 500L,
              kind = InteractiveInputKind.CLICK,
              pixelX = COMPONENT_WIDTH_PX / 2,
              pixelY = COMPONENT_HEIGHT_PX / 2,
            ),
          )
        )

        val result = session.stop()

        // 1. Frame count: at 30 fps over 500ms = 500 * 30 / 1000 + 1 = 16 frames.
        assertEquals(
          "frame count: expected 16 frames at 30 fps over 500ms; got ${result.frameCount}",
          16,
          result.frameCount,
        )
        assertEquals("durationMs", 500L, result.durationMs)
        assertEquals(
          "frameWidthPx (component preview at native size)",
          COMPONENT_WIDTH_PX,
          result.frameWidthPx,
        )
        assertEquals(
          "frameHeightPx (component preview at native size)",
          COMPONENT_HEIGHT_PX,
          result.frameHeightPx,
        )
        // Frame files actually exist on disk.
        for (i in 0 until result.frameCount) {
          val f = File(result.framesDir, "frame-${"%05d".format(i)}.png")
          assertTrue("frame $i missing on disk: ${f.absolutePath}", f.isFile)
          assertTrue("frame $i must be non-empty PNG: ${f.absolutePath}", f.length() > 0)
        }

        // 2. Frame 0 — click@0 has been drained before frame 0's render → state=1 (green).
        val frame0 = readPng(File(result.framesDir, "frame-00000.png"))
        val greenAt0 = pixelMatchPct(frame0, expectedRgb = 0x66BB6A, perChannelTolerance = 8)
        assertTrue(
          "frame 0 expected ≥ 95% green pixels (state=1 after click@0); got ${"%.2f".format(greenAt0 * 100)}%",
          greenAt0 >= 0.95,
        )

        // 3. Frame 5 — between the two clicks (tMs=166ms); state still 1 (green).
        // This is the load-bearing assertion: without held-scene + virtual-time playback, the
        // mutableStateOf would reset between renders and frame 5 would paint red.
        val frame5 = readPng(File(result.framesDir, "frame-00005.png"))
        val greenAt5 = pixelMatchPct(frame5, expectedRgb = 0x66BB6A, perChannelTolerance = 8)
        assertTrue(
          "frame 5 expected ≥ 95% green pixels (state=1 holds between scripted clicks); got " +
            "${"%.2f".format(greenAt5 * 100)}% — this is the load-bearing remember{} survival assertion",
          greenAt5 >= 0.95,
        )

        // 4. Frame 15 — click@500 has been drained → state=2 (blue).
        val frame15 = readPng(File(result.framesDir, "frame-00015.png"))
        val blueAt15 = pixelMatchPct(frame15, expectedRgb = 0x42A5F5, perChannelTolerance = 8)
        assertTrue(
          "frame 15 expected ≥ 95% blue pixels (state=2 after click@500); got ${"%.2f".format(blueAt15 * 100)}%",
          blueAt15 >= 0.95,
        )

        // 5. APNG encode produces a non-empty file with the advertised mime type.
        val encoded = session.encode(RecordingFormat.APNG)
        assertEquals("image/apng", encoded.mimeType)
        val apngFile = File(encoded.videoPath)
        assertTrue("encoded APNG must exist on disk: ${apngFile.absolutePath}", apngFile.isFile)
        assertTrue("encoded APNG must be non-empty", encoded.sizeBytes > 0)
        // Quick PNG-signature sanity check — APNG carries the standard PNG header.
        val firstEight = apngFile.readBytes().copyOf(8)
        assertNotNull("APNG file must contain bytes", firstEight)
        val expectedHeader = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        assertTrue(
          "APNG header must match PNG signature; got ${firstEight.joinToString { "0x%02x".format(it) }}",
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
  fun scale_changes_output_frame_size_but_not_pointer_coords() {
    val outputDir = tempFolder.newFolder("scale-engine-renders")
    val recordingsRoot = tempFolder.newFolder("scale-recordings-root")
    savedRecordingsDir = System.getProperty(DesktopHost.RECORDINGS_DIR_PROP)
    System.setProperty(DesktopHost.RECORDINGS_DIR_PROP, recordingsRoot.absolutePath)

    val engine = RenderEngine(outputDir = outputDir)
    val host =
      DesktopHost(
        engine = engine,
        previewSpecResolver = { previewId ->
          if (previewId == FIXTURE_PREVIEW_ID) {
            RenderSpec(
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "TristateClickSquare",
              widthPx = COMPONENT_WIDTH_PX,
              heightPx = COMPONENT_HEIGHT_PX,
              density = 1.0f,
              outputBaseName = "tristate-click-square-scaled",
            )
          } else null
        },
      )
    host.start()
    try {
      val session =
        host.acquireRecordingSession(
          previewId = FIXTURE_PREVIEW_ID,
          recordingId = "test-rec-scale-2x",
          classLoader =
            DesktopRecordingSessionTest::class.java.classLoader
              ?: ClassLoader.getSystemClassLoader(),
          fps = FPS,
          scale = 2.0f,
          overrides = null,
        )
      try {
        // Click coords stay in image-natural pixel space — same coords as in the unscaled test.
        session.postScript(
          listOf(
            RecordingScriptEvent(
              tMs = 0L,
              kind = InteractiveInputKind.CLICK,
              pixelX = COMPONENT_WIDTH_PX / 2,
              pixelY = COMPONENT_HEIGHT_PX / 2,
            )
          )
        )
        val result = session.stop()
        // Scale 2× → output frame is 240×120, not 120×60.
        assertEquals(
          "scale=2 output width: expected 240 (= 120 * 2); got ${result.frameWidthPx}",
          COMPONENT_WIDTH_PX * 2,
          result.frameWidthPx,
        )
        assertEquals(
          "scale=2 output height: expected 120 (= 60 * 2); got ${result.frameHeightPx}",
          COMPONENT_HEIGHT_PX * 2,
          result.frameHeightPx,
        )
        // Click still routed correctly: frame 0 paints green even though the canvas is 2×.
        val frame0 = readPng(File(result.framesDir, "frame-00000.png"))
        assertEquals(COMPONENT_WIDTH_PX * 2, frame0.width)
        assertEquals(COMPONENT_HEIGHT_PX * 2, frame0.height)
        val greenMatch = pixelMatchPct(frame0, expectedRgb = 0x66BB6A, perChannelTolerance = 8)
        assertTrue(
          "frame 0 (scale=2) expected ≥ 95% green; got ${"%.2f".format(greenMatch * 100)}% — " +
            "click coords must dispatch in natural pixel space, not scaled-canvas space",
          greenMatch >= 0.95,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  private fun readPng(file: File): java.awt.image.BufferedImage {
    assertTrue("rendered PNG must exist on disk: ${file.absolutePath}", file.exists())
    assertTrue("rendered PNG must be non-empty", file.length() > 0)
    val bytes = file.readBytes()
    return ByteArrayInputStream(bytes).use { ImageIO.read(it) }
      ?: error("PNG must decode via javax.imageio: ${file.absolutePath}")
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

  companion object {
    private const val FIXTURE_PREVIEW_ID = "tristate-click-square"
    // Component-preview-sized sandbox — a button-ish 120x60 surface, deliberately NOT phone-sized.
    private const val COMPONENT_WIDTH_PX = 120
    private const val COMPONENT_HEIGHT_PX = 60
    private const val FPS = 30
  }
}
