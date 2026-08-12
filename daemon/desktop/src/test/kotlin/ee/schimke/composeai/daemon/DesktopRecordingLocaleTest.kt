package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Issue #3721 — the **recording** lane composes under the preview's `localeTag`, on every frame.
 *
 * A recording is not one composition: it drives a virtual frame clock and renders a frame per tick,
 * on its own playback / live-tick thread. Those renders used to call `scene.render` directly, which
 * left them outside the locale scope entirely — so a `localeTag` recording composed its *first*
 * frame in the target language (that one comes from `setUp`) and every frame after it at the host
 * default, and an unlocalized recording could compose under whatever a concurrent held session had
 * installed.
 *
 * The fixture recomposes per frame precisely so the frames after the first are visible here; a
 * probe that only records on the initial composition would pass either way.
 */
class DesktopRecordingLocaleTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private var savedRecordingsDir: String? = null

  @After
  fun tearDown() {
    val saved = savedRecordingsDir
    if (saved == null) System.clearProperty(DesktopHost.RECORDINGS_DIR_PROP)
    else System.setProperty(DesktopHost.RECORDINGS_DIR_PROP, saved)
  }

  @Test
  fun every_recorded_frame_composes_under_the_preview_locale() {
    val hostDefault = Locale.getDefault()
    val outputDir = tempFolder.newFolder("recording-locale-renders")
    val recordingsRoot = tempFolder.newFolder("recording-locale-root")
    savedRecordingsDir = savedRecordingsDir ?: System.getProperty(DesktopHost.RECORDINGS_DIR_PROP)
    System.setProperty(DesktopHost.RECORDINGS_DIR_PROP, recordingsRoot.absolutePath)

    val host =
      DesktopHost(
        engine = RenderEngine(outputDir = outputDir),
        previewSpecResolver = { previewId ->
          if (previewId == PREVIEW_ID) {
            RenderSpec(
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "FrameTickLocaleProbeSquare",
              widthPx = 64,
              heightPx = 64,
              localeTag = "de",
              outputBaseName = PREVIEW_ID,
            )
          } else null
        },
      )
    host.start()
    try {
      // A host default that is emphatically not the override, so "composed under de" can't be an
      // accident of the machine the test runs on.
      Locale.setDefault(Locale.forLanguageTag("en-US"))
      PressLocaleProbe.reset()
      val session =
        host.acquireRecordingSession(
          previewId = PREVIEW_ID,
          recordingId = "rec-locale",
          classLoader = DesktopRecordingLocaleTest::class.java.classLoader!!,
          fps = FPS,
          scale = 1.0f,
          overrides = null,
          live = false,
        )
      try {
        // The script's last `tMs` is what sets the recording's length
        // (`totalFrames = durationMs * fps / 1000 + 1`), so the late event is what makes the
        // playback loop render a *run* of frames instead of the single one a `tMs = 0` script
        // would produce — and the frames after the first are the whole point here.
        session.postScript(
          listOf(
            RecordingScriptEvent(tMs = 0L, kind = "input.click", pixelX = 32, pixelY = 32),
            RecordingScriptEvent(
              tMs = SCRIPT_SPAN_MS,
              kind = "input.click",
              pixelX = 32,
              pixelY = 32,
            ),
          )
        )
        session.stop()

        val observed = PressLocaleProbe.observedByThread.values.flatMap { it.toList() }
        assertTrue(
          "the recording must have composed more than once, or a per-frame claim asserts " +
            "nothing; saw $observed",
          observed.size > 1,
        )
        assertEquals(
          "every recorded frame must compose under the preview's localeTag; saw $observed",
          emptyList<String>(),
          observed.filterNot { it.startsWith("de") },
        )
      } finally {
        session.close()
      }
    } finally {
      PressLocaleProbe.reset()
      Locale.setDefault(hostDefault)
      host.shutdown()
    }
  }

  private companion object {
    const val PREVIEW_ID = "frame-tick-locale-probe-square"
    const val FPS = 30

    /**
     * Virtual span of the script, so playback renders ~10 frames at [FPS] rather than the single
     * frame a `tMs = 0` script produces.
     */
    const val SCRIPT_SPAN_MS = 300L
  }
}
