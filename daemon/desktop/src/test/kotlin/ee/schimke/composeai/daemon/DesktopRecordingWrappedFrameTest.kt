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
 * A held recording of a **wrap-content** preview has to be the component, not the sandbox it was
 * measured in (issue #4467).
 *
 * `DesktopRecordingSession` derived its frame size from `spec.widthPx`/`heightPx`, which on a
 * wrapped axis are the generous sandbox bound the preview measures inside — not the component's
 * natural size. `RenderEngine.renderOnce` crops a still with `state.measuredContent`; the recording
 * paths encoded the raw scene. So one preview published a still at its own size and a recording
 * beside it at 400×800 dp with the component in the corner: the same picture at two different
 * sizes, which is the disagreement `@CaptureGutter`'s work was meant to end rather than extend.
 * This predates gutters entirely — the gutter term added in #4443 just rode on top of it.
 */
class DesktopRecordingWrappedFrameTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private var savedRecordingsDir: String? = null

  @After
  fun tearDown() {
    val saved = savedRecordingsDir
    if (saved == null) System.clearProperty(DesktopHost.RECORDINGS_DIR_PROP)
    else System.setProperty(DesktopHost.RECORDINGS_DIR_PROP, saved)
  }

  @Test
  fun `a wrapped recording is the measured component, not the sandbox`() {
    val still = renderStill("wrapped-still")
    // Sanity: the still path is cropping to the measured content, so there is something to agree
    // with. The sticker is far smaller than the sandbox it measured in.
    assertTrue("still must be smaller than the sandbox: $still", still.first < SANDBOX_WIDTH_PX)

    val recorded = recordFirstFrameSize("wrapped-rec", wrap = true)
    assertEquals("a recorded frame must match the still", still, recorded)
  }

  @Test
  fun `a fixed-size recording is unchanged`() {
    // The whole pre-existing behaviour for a fixed preview: the declared frame, verbatim.
    assertEquals(
      FIXED_WIDTH_PX to FIXED_HEIGHT_PX,
      recordFirstFrameSize("fixed-rec", wrap = false),
    )
  }

  @Test
  fun `the axis rule is the still's crop rule, clause for clause`() {
    // A wrapped axis takes its measurement…
    assertEquals(176, recordingNaturalAxisPx(wrapped = true, measuredPx = 176, scenePx = 800))
    // …a fixed one never does, whatever was measured.
    assertEquals(800, recordingNaturalAxisPx(wrapped = false, measuredPx = 176, scenePx = 800))
    // A `fillMax*` composable measures the whole sandbox: that IS the frame, and cropping to a
    // measurement at or past the bound would sample off the image.
    assertEquals(800, recordingNaturalAxisPx(wrapped = true, measuredPx = 800, scenePx = 800))
    assertEquals(800, recordingNaturalAxisPx(wrapped = true, measuredPx = 4000, scenePx = 800))
    // Nothing measured yet (or an un-wrapped axis) falls through to the scene on the same clause.
    assertEquals(800, recordingNaturalAxisPx(wrapped = true, measuredPx = 0, scenePx = 800))
  }

  /** The still of the same fixture through the same engine, for the comparison above. */
  private fun renderStill(label: String): Pair<Int, Int> {
    val engine = RenderEngine(outputDir = tempFolder.newFolder("renders-$label"))
    val result =
      engine.render(
        RenderSpec(
          previewId = "sticker",
          className = STICKER_CLASS,
          functionName = "WrapContentStickerPreview",
          widthPx = SANDBOX_WIDTH_PX,
          heightPx = SANDBOX_HEIGHT_PX,
          wrapWidth = true,
          wrapHeight = true,
          density = 2.0f,
          showBackground = true,
          outputBaseName = "sticker-$label",
        ),
        requestId = 1L,
        classLoader = javaClass.classLoader,
      )
    val png = File(result.pngPath ?: error("$label: pngPath must be populated"))
    return decode(png.readBytes())
  }

  /** Records one probe frame and returns its decoded pixel size. */
  private fun recordFirstFrameSize(label: String, wrap: Boolean): Pair<Int, Int> {
    val outputDir = tempFolder.newFolder("renders-$label")
    val recordingsRoot = tempFolder.newFolder("recordings-$label")
    savedRecordingsDir = System.getProperty(DesktopHost.RECORDINGS_DIR_PROP)
    System.setProperty(DesktopHost.RECORDINGS_DIR_PROP, recordingsRoot.absolutePath)

    val host =
      DesktopHost(
        engine = RenderEngine(outputDir = outputDir),
        previewSpecResolver = { previewId ->
          if (previewId != FIXTURE_PREVIEW_ID) null
          else if (wrap)
            RenderSpec(
              className = STICKER_CLASS,
              functionName = "WrapContentStickerPreview",
              widthPx = SANDBOX_WIDTH_PX,
              heightPx = SANDBOX_HEIGHT_PX,
              wrapWidth = true,
              wrapHeight = true,
              density = 2.0f,
              showBackground = true,
              outputBaseName = "sticker-$label",
            )
          else
            RenderSpec(
              className = STICKER_CLASS,
              functionName = "TristateClickSquare",
              widthPx = FIXED_WIDTH_PX,
              heightPx = FIXED_HEIGHT_PX,
              density = 1.0f,
              outputBaseName = "square-$label",
            )
        },
      )
    host.start()
    try {
      val classLoader =
        DesktopRecordingWrappedFrameTest::class.java.classLoader
          ?: ClassLoader.getSystemClassLoader()
      host
        .acquireRecordingSession(FIXTURE_PREVIEW_ID, "rec-$label", classLoader, FPS, 1.0f, null)
        .use { session ->
          session.postScript(listOf(RecordingScriptEvent(tMs = 0L, kind = "recording.probe")))
          val result = session.stop()
          val frame = File(result.framesDir, "frame-00000.png")
          assertTrue("$label: a frame must have been captured", frame.isFile && frame.length() > 0)
          return decode(frame.readBytes())
        }
    } finally {
      host.shutdown()
    }
  }

  private fun decode(bytes: ByteArray): Pair<Int, Int> {
    val img =
      ByteArrayInputStream(bytes).use { ImageIO.read(it) } ?: error("frame failed to decode")
    return img.width to img.height
  }

  private companion object {
    private const val FIXTURE_PREVIEW_ID = "wrapped-sticker"
    private const val STICKER_CLASS = "ee.schimke.composeai.daemon.RedFixturePreviewsKt"
    private const val SANDBOX_WIDTH_PX = 800
    private const val SANDBOX_HEIGHT_PX = 1600
    private const val FIXED_WIDTH_PX = 120
    private const val FIXED_HEIGHT_PX = 60
    private const val FPS = 30
  }
}
