package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A held recording session composes the same scene the one-shot render does, so a `@CaptureGutter`
 * preview has to record on the grown canvas too (issue #4443 review).
 *
 * The trap this pins: [DesktopRecordingSession] derives its output frame size from the spec, and
 * the spec's `widthPx`/`heightPx` are the component's frame, not the scene's. Once the scene grows
 * by the gutter, a frame size taken from the spec alone no longer matches the captured image — so
 * even at `scale = 1` every frame takes the resampling path and is squeezed back into the
 * un-guttered box, shrinking the component and defeating the annotation in scripted and live
 * recordings alike.
 */
class DesktopRecordingGutterTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private var savedRecordingsDir: String? = null

  @After
  fun tearDown() {
    val saved = savedRecordingsDir
    if (saved == null) System.clearProperty(DesktopHost.RECORDINGS_DIR_PROP)
    else System.setProperty(DesktopHost.RECORDINGS_DIR_PROP, saved)
  }

  @Test
  fun `a recorded frame is the grown scene, not the spec's un-guttered frame`() {
    val bare = recordFirstFrameSize(gutterDp = 0, label = "bare")
    assertEquals(COMPONENT_WIDTH_PX, bare.first)
    assertEquals(COMPONENT_HEIGHT_PX, bare.second)

    // 4 dp a side at density 1 ⇒ +8 across and +8 down, matching the still on the same lane.
    val guttered = recordFirstFrameSize(gutterDp = 4, label = "guttered")
    assertEquals(COMPONENT_WIDTH_PX + 8, guttered.first)
    assertEquals(COMPONENT_HEIGHT_PX + 8, guttered.second)
  }

  /** Records one probe frame of the fixture and returns its decoded pixel size. */
  private fun recordFirstFrameSize(gutterDp: Int, label: String): Pair<Int, Int> {
    val outputDir = tempFolder.newFolder("renders-$label")
    val recordingsRoot = tempFolder.newFolder("recordings-$label")
    savedRecordingsDir = System.getProperty(DesktopHost.RECORDINGS_DIR_PROP)
    System.setProperty(DesktopHost.RECORDINGS_DIR_PROP, recordingsRoot.absolutePath)

    val host =
      DesktopHost(
        engine = RenderEngine(outputDir = outputDir),
        previewSpecResolver = { previewId ->
          if (previewId == FIXTURE_PREVIEW_ID) {
            RenderSpec(
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "TristateClickSquare",
              widthPx = COMPONENT_WIDTH_PX,
              heightPx = COMPONENT_HEIGHT_PX,
              density = 1.0f,
              outputBaseName = "tristate-click-square-$label",
              gutterStartDp = gutterDp,
              gutterTopDp = gutterDp,
              gutterEndDp = gutterDp,
              gutterBottomDp = gutterDp,
            )
          } else null
        },
      )
    host.start()
    try {
      val classLoader =
        DesktopRecordingGutterTest::class.java.classLoader ?: ClassLoader.getSystemClassLoader()
      host
        .acquireRecordingSession(FIXTURE_PREVIEW_ID, "rec-$label", classLoader, FPS, 1.0f, null)
        .use { session ->
          session.postScript(listOf(RecordingScriptEvent(tMs = 0L, kind = "recording.probe")))
          val result = session.stop()
          val frame = File(result.framesDir, "frame-00000.png")
          assertTrue("$label: a frame must have been captured", frame.isFile && frame.length() > 0)
          val img =
            ByteArrayInputStream(frame.readBytes()).use { ImageIO.read(it) }
              ?: error("$label: frame failed to decode")
          return img.width to img.height
        }
    } finally {
      host.shutdown()
    }
  }

  private companion object {
    private const val FIXTURE_PREVIEW_ID = "tristate-click-square"
    private const val COMPONENT_WIDTH_PX = 120
    private const val COMPONENT_HEIGHT_PX = 60
    private const val FPS = 30
  }
}
