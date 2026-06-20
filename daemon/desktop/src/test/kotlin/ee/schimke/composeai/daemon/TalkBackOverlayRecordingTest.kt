package ee.schimke.composeai.daemon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RecordingFormat
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import java.awt.Color as AwtColor
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end test for the TalkBack focus-overlay recording path (issue #1956). Records a multi-stop
 * [SettingsFixture] with `overrides.talkBack = true` over the real desktop renderer and verifies:
 *
 * 1. The recording spans the whole focus walk (many frames).
 * 2. The overlay actually painted on the real render — captured frames carry the TalkBack-green
 *    focus rectangle / badges.
 * 3. The focus walk *advances*: an early frame concentrates the green near the top of the screen
 *    (the heading, first stop); a late frame concentrates it lower (a button further down the
 *    traversal order).
 * 4. APNG encodes (always), and MP4 encodes when ffmpeg is on PATH.
 *
 * Also stitches a GIF artifact for the PR comment. Fixture lives in this file (resolved by the
 * `…TalkBackOverlayRecordingTestKt` synthetic class name) so the test is self-contained.
 */
class TalkBackOverlayRecordingTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private var savedRecordingsDir: String? = null

  @After
  fun tearDown() {
    val saved = savedRecordingsDir
    if (saved == null) System.clearProperty(DesktopHost.RECORDINGS_DIR_PROP)
    else System.setProperty(DesktopHost.RECORDINGS_DIR_PROP, saved)
  }

  @Test
  fun talkBack_overlay_walks_focus_through_stops_on_real_render() {
    val outputDir = tempFolder.newFolder("talkback-renders")
    val recordingsRoot = tempFolder.newFolder("talkback-recordings")
    savedRecordingsDir = System.getProperty(DesktopHost.RECORDINGS_DIR_PROP)
    System.setProperty(DesktopHost.RECORDINGS_DIR_PROP, recordingsRoot.absolutePath)

    val engine = RenderEngine(outputDir = outputDir)
    val host =
      DesktopHost(
        engine = engine,
        previewSpecResolver = { previewId ->
          if (previewId == TALKBACK_PREVIEW_ID) {
            RenderSpec(
              className = "ee.schimke.composeai.daemon.TalkBackOverlayRecordingTestKt",
              functionName = "SettingsFixture",
              widthPx = CANVAS_W,
              heightPx = CANVAS_H,
              density = 1.0f,
              outputBaseName = "talkback-walk",
            )
          } else null
        },
      )
    host.start()
    try {
      val session =
        host.acquireRecordingSession(
          previewId = TALKBACK_PREVIEW_ID,
          recordingId = "test-rec-talkback",
          classLoader =
            TalkBackOverlayRecordingTest::class.java.classLoader
              ?: ClassLoader.getSystemClassLoader(),
          fps = FPS,
          scale = 1.0f,
          overrides = PreviewOverrides(talkBack = true),
        )
      try {
        // No real interaction needed — just extend the recording long enough for the focus walk to
        // step through every stop. Two harmless clicks on empty padding bracket the timeline; the
        // walk advancement is driven by frame index, not by these events.
        session.postScript(
          listOf(
            RecordingScriptEvent(tMs = 0L, kind = "input.click", pixelX = 4, pixelY = 4),
            RecordingScriptEvent(tMs = WALK_MS, kind = "input.click", pixelX = 4, pixelY = 4),
          )
        )

        val result = session.stop()
        assertTrue(
          "expected a multi-frame walk over $WALK_MS ms at $FPS fps; got ${result.frameCount}",
          result.frameCount >= 100,
        )

        val framesDir = File(result.framesDir)
        // 2. Overlay painted on the real render — green present in a mid-walk frame.
        val mid = TouchOverlayTestSupport.readPng(frame(framesDir, result.frameCount / 2))
        val greenMid =
          TouchOverlayTestSupport.pixelMatchPctApprox(
            mid,
            FOCUS_GREEN_RGB,
            perChannelTolerance = 50,
          )
        assertTrue(
          "mid-walk frame must carry TalkBack-green overlay pixels; got ${"%.4f".format(greenMid * 100)}%",
          greenMid > 0.0005,
        )

        // 3. Focus advances down the traversal order: more green up top early, lower later.
        val early = TouchOverlayTestSupport.readPng(frame(framesDir, 5))
        val late = TouchOverlayTestSupport.readPng(frame(framesDir, result.frameCount - 5))
        val topBand = 0 until CANVAS_H / 3
        val bottomBand = (CANVAS_H * 2 / 3) until CANVAS_H
        val earlyTop = greenCount(early, topBand)
        val lateTop = greenCount(late, topBand)
        val lateBottom = greenCount(late, bottomBand)
        assertTrue(
          "focus rectangle should start near the top (early top-green=$earlyTop) and move down " +
            "(late top-green=$lateTop, late bottom-green=$lateBottom)",
          earlyTop > lateTop && lateBottom > lateTop,
        )

        // 4. Encode artifacts.
        val apng = session.encode(RecordingFormat.APNG)
        assertTrue("APNG must be non-empty", File(apng.videoPath).isFile && apng.sizeBytes > 0)
        if (FfmpegEncoder.available()) {
          val mp4 = session.encode(RecordingFormat.MP4)
          assertTrue(
            "MP4 must be non-empty when ffmpeg present",
            File(mp4.videoPath).isFile && mp4.sizeBytes > 0,
          )
        }

        // GIF artifact for the PR comment.
        val gif = ARTIFACT_DIR.also { it.mkdirs() }.resolve("talkback-desktop-walk.gif")
        TouchOverlayTestSupport.encodeFramesAsGif(framesDir, result.frameCount, gif, fps = FPS)
        assertTrue("GIF artifact must be written", gif.isFile && gif.length() > 0)
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  private fun frame(dir: File, index: Int) = File(dir, "frame-${"%05d".format(index)}.png")

  /** Count TalkBack-green pixels within the given vertical band. */
  private fun greenCount(img: java.awt.image.BufferedImage, yRange: IntRange): Int {
    var n = 0
    for (y in yRange) {
      if (y !in 0 until img.height) continue
      for (x in 0 until img.width) {
        val c = AwtColor(img.getRGB(x, y), true)
        if (c.green > 140 && c.green > c.red + 40 && c.green > c.blue + 40) n++
      }
    }
    return n
  }

  companion object {
    private const val TALKBACK_PREVIEW_ID = "talkback-settings"
    private const val CANVAS_W = 320
    private const val CANVAS_H = 520
    private const val FPS = 30
    private const val FOCUS_GREEN_RGB = 0x00C853
    // 4 stops × 900ms default dwell = 3600ms; record the full walk.
    private const val WALK_MS = 3600L
    // Repo-root `build/talkback-artifacts` (user.dir is the module dir under gradle — up two
    // levels),
    // matching where TouchOverlayTestSupport drops its GIF for the PR-artifact upload step.
    private val ARTIFACT_DIR: File =
      File(System.getProperty("user.dir")).parentFile.parentFile.resolve("build/talkback-artifacts")
  }
}

/**
 * Recording fixture: a small settings screen with a heading and three buttons — four TalkBack focus
 * stops the overlay walks top-to-bottom.
 */
@Composable
fun SettingsFixture() {
  Column(
    modifier = Modifier.fillMaxSize().background(Color(0xFFF5F5F7)).padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(20.dp),
  ) {
    Text("Settings", modifier = Modifier.semantics { heading() })
    Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Wi-Fi") }
    Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Bluetooth") }
    Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
  }
}
