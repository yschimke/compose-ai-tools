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
import org.junit.Assume.assumeTrue
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
              kind = "input.click",
              pixelX = COMPONENT_WIDTH_PX / 2,
              pixelY = COMPONENT_HEIGHT_PX / 2,
            ),
            RecordingScriptEvent(
              tMs = 500L,
              kind = "input.click",
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
              kind = "input.click",
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

  @Test
  fun mp4_format_round_trips_through_ffmpeg_encoder() {
    // Same shape as the APNG happy path, but encodes via ffmpeg's mp4 path. Skipped on machines
    // without ffmpeg on PATH so the test is portable; runs anywhere `FfmpegEncoder.available()` is
    // true. We're testing the dispatch in `DesktopRecordingSession.encode` here (APNG → ApngEncoder
    // vs MP4 → FfmpegEncoder), not the ffmpeg encoder itself — `FfmpegEncoderTest` covers the
    // signature-bytes side of that.
    assumeTrue("ffmpeg not on PATH; skipping mp4 round-trip", FfmpegEncoder.available())
    val outputDir = tempFolder.newFolder("mp4-engine-renders")
    val recordingsRoot = tempFolder.newFolder("mp4-recordings-root")
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
              outputBaseName = "tristate-click-square-mp4",
            )
          } else null
        },
      )
    // Capability advertisement should include mp4 (and webm) when ffmpeg is available.
    assertTrue(
      "DesktopHost should advertise mp4 when ffmpeg is on PATH; got ${host.supportedRecordingFormats}",
      "mp4" in host.supportedRecordingFormats,
    )
    host.start()
    try {
      val session =
        host.acquireRecordingSession(
          previewId = FIXTURE_PREVIEW_ID,
          recordingId = "test-rec-mp4",
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
              kind = "input.click",
              pixelX = COMPONENT_WIDTH_PX / 2,
              pixelY = COMPONENT_HEIGHT_PX / 2,
            ),
            RecordingScriptEvent(
              tMs = 200L,
              kind = "input.click",
              pixelX = COMPONENT_WIDTH_PX / 2,
              pixelY = COMPONENT_HEIGHT_PX / 2,
            ),
          )
        )
        val stop = session.stop()
        assertTrue("frame count must be > 1 for a 200ms timeline at 30fps", stop.frameCount > 1)
        val encoded = session.encode(RecordingFormat.MP4)
        assertEquals("video/mp4", encoded.mimeType)
        val out = File(encoded.videoPath)
        assertTrue("mp4 file must exist on disk: ${out.absolutePath}", out.isFile)
        assertTrue("mp4 file must be non-empty", encoded.sizeBytes > 0)
        // Same `ftyp`-at-byte-4 sanity check `FfmpegEncoderTest` uses.
        val head = out.readBytes().copyOf(12)
        assertTrue(
          "mp4 should carry 'ftyp' at bytes 4..7; got ${head.joinToString { "0x%02x".format(it) }}",
          head[4] == 'f'.code.toByte() &&
            head[5] == 't'.code.toByte() &&
            head[6] == 'y'.code.toByte() &&
            head[7] == 'p'.code.toByte(),
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun supported_recording_formats_includes_apng_when_resolver_wired() {
    // No ffmpeg dependence — APNG is always advertised when the host has a previewSpecResolver.
    val host =
      DesktopHost(
        engine = RenderEngine(outputDir = tempFolder.newFolder("ignored")),
        previewSpecResolver = { _ -> null },
      )
    assertTrue("apng must be advertised", "apng" in host.supportedRecordingFormats)
  }

  @Test
  fun supported_recording_formats_empty_when_resolver_unwired() {
    // Without a previewSpecResolver, recording isn't supported — the formats list is empty so
    // clients consistently see "no formats" rather than a misleading "apng available + recording
    // off" combination.
    val host = DesktopHost(engine = RenderEngine(outputDir = tempFolder.newFolder("ignored")))
    assertTrue(
      "supportedRecordingFormats must be empty without resolver; got ${host.supportedRecordingFormats}",
      host.supportedRecordingFormats.isEmpty(),
    )
  }

  @Test
  fun live_mode_captures_frames_at_fps_cadence_and_dispatches_inputs() {
    // P4 — live mode test. The session runs a background tick thread at fps cadence using
    // wall-clock-driven virtual time. We:
    //   1. Allocate a live session against TristateClickSquare (red → green → blue on click).
    //   2. Sleep a short window (250 ms wall) so several frames accumulate (initial-state red).
    //   3. Post a click via postInput.
    //   4. Sleep another window so the post-click state (green) renders into more frames.
    //   5. Stop, then assert:
    //      - Frame count is in a tolerant range — we expect ~3..30 frames at 30 fps over ~500 ms;
    //        wide bounds because CI machines vary in timing precision and the JIT may need a few
    //        frames to warm up the render path.
    //      - The first frame is red (pre-click state).
    //      - The last frame is green (post-click state, dispatched at some virtual tMs > 0).
    //
    // Wall-clock-based timing assertions are tolerant (range, not exact count) to keep the test
    // portable across slower CI runners. The state-flip assertion is the load-bearing one — it
    // proves the live tick loop is actually draining inputs and the held scene is mutating.
    val outputDir = tempFolder.newFolder("live-engine-renders")
    val recordingsRoot = tempFolder.newFolder("live-recordings-root")
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
              outputBaseName = "tristate-click-square-live",
            )
          } else null
        },
      )
    host.start()
    try {
      val session =
        host.acquireRecordingSession(
          previewId = FIXTURE_PREVIEW_ID,
          recordingId = "test-rec-live",
          classLoader =
            DesktopRecordingSessionTest::class.java.classLoader
              ?: ClassLoader.getSystemClassLoader(),
          fps = FPS,
          scale = 1.0f,
          overrides = null,
          live = true,
        )
      try {
        // Pre-click window: let several initial-state frames render before we dispatch.
        Thread.sleep(LIVE_PRE_CLICK_MS)

        session.postInput(
          ee.schimke.composeai.daemon.protocol.RecordingInputParams(
            recordingId = "test-rec-live",
            kind = ee.schimke.composeai.daemon.protocol.InteractiveInputKind.CLICK,
            pixelX = COMPONENT_WIDTH_PX / 2,
            pixelY = COMPONENT_HEIGHT_PX / 2,
          )
        )

        // Post-click window: let a few more frames render so the post-click state (green) lands.
        Thread.sleep(LIVE_POST_CLICK_MS)

        val result = session.stop()
        assertTrue(
          "live recording should produce > 1 frame; got ${result.frameCount}",
          result.frameCount > 1,
        )
        // Generous upper bound — slow CI runners might tick faster than fps in some configs.
        assertTrue(
          "live recording at 30 fps over ~500 ms should not exceed ${LIVE_MAX_FRAMES} frames; got ${result.frameCount}",
          result.frameCount <= LIVE_MAX_FRAMES,
        )

        // First frame: pre-click state (red).
        val frame0 = readPng(File(result.framesDir, "frame-00000.png"))
        val redAt0 = pixelMatchPct(frame0, expectedRgb = 0xEF5350, perChannelTolerance = 8)
        assertTrue(
          "live frame 0 expected ≥ 95% red pixels (pre-click); got ${"%.2f".format(redAt0 * 100)}%",
          redAt0 >= 0.95,
        )

        // Last frame: post-click state (green) — proves the tick loop drained the input and the
        // held scene mutated. Without this assertion, a regression that broke postInput would
        // still pass the frame-count check.
        val lastFrameIdx = result.frameCount - 1
        val frameLast = readPng(File(result.framesDir, "frame-${"%05d".format(lastFrameIdx)}.png"))
        val greenAtLast = pixelMatchPct(frameLast, expectedRgb = 0x66BB6A, perChannelTolerance = 8)
        assertTrue(
          "live last frame ($lastFrameIdx) expected ≥ 95% green pixels (state=1 after live click); " +
            "got ${"%.2f".format(greenAtLast * 100)}% — this is the load-bearing live-mode " +
            "dispatch assertion",
          greenAtLast >= 0.95,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun live_mode_postScript_throws() {
    // Mode-mismatch guard: postScript on a live session is illegal. The wire-side handler
    // catches the throw and logs it; we exercise the underlying contract here.
    val outputDir = tempFolder.newFolder("live-mismatch-engine-renders")
    val recordingsRoot = tempFolder.newFolder("live-mismatch-recordings-root")
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
              outputBaseName = "tristate-click-square-mismatch",
            )
          } else null
        },
      )
    host.start()
    try {
      val session =
        host.acquireRecordingSession(
          previewId = FIXTURE_PREVIEW_ID,
          recordingId = "test-rec-mismatch",
          classLoader =
            DesktopRecordingSessionTest::class.java.classLoader
              ?: ClassLoader.getSystemClassLoader(),
          fps = FPS,
          scale = 1.0f,
          overrides = null,
          live = true,
        )
      try {
        val thrown =
          runCatching {
              session.postScript(listOf(RecordingScriptEvent(tMs = 0L, kind = "input.click")))
            }
            .exceptionOrNull()
        assertTrue(
          "postScript on a live session must throw IllegalStateException; got ${thrown?.javaClass}",
          thrown is IllegalStateException,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun live_mode_propagates_render_failure_through_stop() {
    // Codex P1 follow-up on #491. Without per-tick try/catch + liveFailure propagation, an
    // exception inside scene.render or writeFramePng on the tick thread silently terminates it
    // and stop() reports a successful (truncated) recording. Mirrors the asymmetry vs scripted
    // mode where the synchronous playback loop propagates failures naturally.
    //
    // Setup: a live recording against ClickToBoomSquare. First composition paints cyan and arms
    // a click handler; postInput dispatches the click; the next recomposition reads `boom = true`
    // and throws inside scene.render. The tick loop catches that, stop() rethrows.
    val outputDir = tempFolder.newFolder("live-fail-engine-renders")
    val recordingsRoot = tempFolder.newFolder("live-fail-recordings-root")
    savedRecordingsDir = System.getProperty(DesktopHost.RECORDINGS_DIR_PROP)
    System.setProperty(DesktopHost.RECORDINGS_DIR_PROP, recordingsRoot.absolutePath)

    val engine = RenderEngine(outputDir = outputDir)
    val host =
      DesktopHost(
        engine = engine,
        previewSpecResolver = { previewId ->
          if (previewId == FAILURE_PREVIEW_ID) {
            RenderSpec(
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "ClickToBoomSquare",
              widthPx = COMPONENT_WIDTH_PX,
              heightPx = COMPONENT_HEIGHT_PX,
              density = 1.0f,
              outputBaseName = "click-to-boom-live",
            )
          } else null
        },
      )
    host.start()
    try {
      val session =
        host.acquireRecordingSession(
          previewId = FAILURE_PREVIEW_ID,
          recordingId = "test-rec-fail",
          classLoader =
            DesktopRecordingSessionTest::class.java.classLoader
              ?: ClassLoader.getSystemClassLoader(),
          fps = FPS,
          scale = 1.0f,
          overrides = null,
          live = true,
        )
      try {
        // Let a couple of healthy frames land before posting the click that arms the boom.
        Thread.sleep(LIVE_PRE_CLICK_MS)

        session.postInput(
          ee.schimke.composeai.daemon.protocol.RecordingInputParams(
            recordingId = "test-rec-fail",
            kind = ee.schimke.composeai.daemon.protocol.InteractiveInputKind.CLICK,
            pixelX = COMPONENT_WIDTH_PX / 2,
            pixelY = COMPONENT_HEIGHT_PX / 2,
          )
        )

        // Give the tick loop time to absorb the click, recompose, and throw.
        Thread.sleep(LIVE_POST_CLICK_MS)

        val thrown = runCatching { session.stop() }.exceptionOrNull()
        assertNotNull(
          "stop() must rethrow the live tick failure; got null (recording silently truncated)",
          thrown,
        )
        assertTrue(
          "stop() failure must be IllegalStateException; got ${thrown?.javaClass}",
          thrown is IllegalStateException,
        )
        val message = thrown!!.message ?: ""
        assertTrue(
          "failure message must mention the recording id; got '$message'",
          message.contains("test-rec-fail"),
        )
        // The original throwable is chained as the cause so callers can inspect render-side
        // detail without parsing the message.
        assertNotNull("failure must carry the underlying cause", thrown.cause)
      } finally {
        // close() is idempotent and safe even when stop() threw — the held scene was already
        // torn down via the try/finally inside stop().
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun live_mode_emits_at_least_one_frame_even_on_immediate_stop() {
    // Codex P2 follow-up on #491. Original tick loop had `while (!liveStopRequested)` as the
    // gate, so a recording/stop arriving before the OS scheduled the tick thread for its first
    // iteration produced 0 frames — and APNG explicitly requires ≥1, so the subsequent encode
    // would fail nondeterministically based on thread scheduling.
    //
    // Fix: do-while loop body — at least one frame always lands on disk, regardless of stop()
    // timing. This test calls stop() synchronously after acquireRecordingSession to maximise the
    // chance of the original racing failure mode, and asserts the result is well-formed.
    val outputDir = tempFolder.newFolder("live-immediate-engine-renders")
    val recordingsRoot = tempFolder.newFolder("live-immediate-recordings-root")
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
              outputBaseName = "tristate-click-square-immediate",
            )
          } else null
        },
      )
    host.start()
    try {
      val session =
        host.acquireRecordingSession(
          previewId = FIXTURE_PREVIEW_ID,
          recordingId = "test-rec-immediate",
          classLoader =
            DesktopRecordingSessionTest::class.java.classLoader
              ?: ClassLoader.getSystemClassLoader(),
          fps = FPS,
          scale = 1.0f,
          overrides = null,
          live = true,
        )
      try {
        // No sleep — stop synchronously to race the tick thread's first iteration.
        val result = session.stop()
        assertTrue(
          "live recording must always emit at least one frame; got ${result.frameCount} " +
            "(this is the load-bearing initial-frame guarantee)",
          result.frameCount >= 1,
        )
        // Frame 0 must exist on disk so a follow-up recording/encode (APNG requires ≥1 frame)
        // succeeds rather than failing with "at least one frame required".
        val frame0 = File(result.framesDir, "frame-00000.png")
        assertTrue("frame 0 must exist on disk: ${frame0.absolutePath}", frame0.isFile)
        assertTrue("frame 0 must be non-empty", frame0.length() > 0)
        // Encode confirms the end-to-end path works for very-short recordings.
        val encoded = session.encode(RecordingFormat.APNG)
        assertTrue("APNG encode must produce a non-empty file", encoded.sizeBytes > 0)
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
    private const val FAILURE_PREVIEW_ID = "click-to-boom-square"
    // Component-preview-sized sandbox — a button-ish 120x60 surface, deliberately NOT phone-sized.
    private const val COMPONENT_WIDTH_PX = 120
    private const val COMPONENT_HEIGHT_PX = 60
    private const val FPS = 30

    // Live-mode timing windows. Tuned so frames accumulate on either side of the click without
    // making the test slow. ~250 ms × 2 = ~500 ms wall total; at 30 fps that's ~15 frames in the
    // ideal case but we accept anywhere in the range below to absorb CI jitter.
    private const val LIVE_PRE_CLICK_MS: Long = 250L
    private const val LIVE_POST_CLICK_MS: Long = 250L
    private const val LIVE_MAX_FRAMES: Int = 60
  }
}
