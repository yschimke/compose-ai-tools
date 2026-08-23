package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.RecordingInputParams
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Laying a held scene out before projecting its semantics must be *invisible* to the recording it
 * happens inside (issue #4470 review).
 *
 * Two ways it can fail to be, both caught here:
 * 1. **The clock.** The layout pass has to render at the frame the scene is already on. A held
 *    session runs its own clock and never touches the engine's one-shot cursor, so reading the
 *    frame time off `virtualFrameNanos` renders a mid-timeline recording at the wall clock and then
 *    jumps it back on the next real frame — a large forward delta to every in-flight animation,
 *    followed by an equally large negative one.
 * 2. **The ordering.** In live mode the reverse map that turns a pixel click into a stable handle
 *    has to run against the screen the user clicked, not the screen the click produced.
 */
class DesktopHeldSceneLayoutTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private var savedRecordingsDir: String? = null

  @After
  fun tearDown() {
    val saved = savedRecordingsDir
    if (saved == null) System.clearProperty(DesktopHost.RECORDINGS_DIR_PROP)
    else System.setProperty(DesktopHost.RECORDINGS_DIR_PROP, saved)
  }

  @Test
  fun `a layout pass renders at the frame the scene is on, and leaves it there`() {
    val engine = RenderEngine(outputDir = tempFolder.newFolder("clock-renders"))
    val state =
      engine.setUp(
        RenderSpec(
          className = FIXTURE_CLASS,
          functionName = "TristateClickSquare",
          widthPx = 120,
          heightPx = 60,
          density = 1.0f,
          outputBaseName = "clock",
        ),
        classLoader = javaClass.classLoader,
      )
    try {
      // A scene that has never rendered lays out on the deterministic frozen-at-zero clock.
      assertEquals(0L, state.lastRenderedFrameNanos)
      engine.layOutForSemantics(state)
      assertEquals("laying out must not move the clock", 0L, state.lastRenderedFrameNanos)

      // Once a session has driven the scene to a frame, the layout pass belongs ON that frame —
      // not at zero (a rewind) and not at the wall clock (a jump).
      state.recordFrameNanos(SOME_LATE_FRAME_NANOS)
      engine.layOutForSemantics(state)
      assertEquals(SOME_LATE_FRAME_NANOS, state.lastRenderedFrameNanos)

      // And it is idempotent — the invariant that makes it safe to call before every resolution.
      engine.layOutForSemantics(state)
      assertEquals(SOME_LATE_FRAME_NANOS, state.lastRenderedFrameNanos)
    } finally {
      engine.tearDown(state)
    }
  }

  @Test
  fun `a recording frame is what the layout pass reproduces, not the wall clock`() {
    val engine = RenderEngine(outputDir = tempFolder.newFolder("recording-clock-renders"))
    val recordingsRoot = tempFolder.newFolder("recording-clock-root")
    savedRecordingsDir = System.getProperty(DesktopHost.RECORDINGS_DIR_PROP)
    System.setProperty(DesktopHost.RECORDINGS_DIR_PROP, recordingsRoot.absolutePath)
    val state =
      engine.setUp(
        RenderSpec(
          className = FIXTURE_CLASS,
          functionName = "TristateClickSquare",
          widthPx = 120,
          heightPx = 60,
          density = 1.0f,
          outputBaseName = "recording-clock",
        ),
        classLoader = javaClass.classLoader,
      )
    try {
      // The one-shot cursor stays untouched by a held session, which is exactly why the layout
      // pass cannot read the frame time off it.
      state.recordFrameNanos(SOME_LATE_FRAME_NANOS)
      assertEquals(0L, state.virtualFrameNanos)
      engine.layOutForSemantics(state)
      assertEquals(
        "the layout pass must follow the held clock, not the one-shot cursor",
        SOME_LATE_FRAME_NANOS,
        state.lastRenderedFrameNanos,
      )
    } finally {
      engine.tearDown(state)
    }
  }

  @Test
  fun `a live click is reverse-mapped to the node the user aimed at`() {
    val outputDir = tempFolder.newFolder("swap-renders")
    val recordingsRoot = tempFolder.newFolder("swap-recordings")
    savedRecordingsDir = System.getProperty(DesktopHost.RECORDINGS_DIR_PROP)
    System.setProperty(DesktopHost.RECORDINGS_DIR_PROP, recordingsRoot.absolutePath)

    val host =
      DesktopHost(
        engine = RenderEngine(outputDir = outputDir),
        previewSpecResolver = { previewId ->
          if (previewId == SWAP_PREVIEW_ID)
            RenderSpec(
              className = FIXTURE_CLASS,
              functionName = "ClickSwapsTargetSquare",
              widthPx = 120,
              heightPx = 60,
              density = 1.0f,
              outputBaseName = "click-swaps-target",
            )
          else null
        },
      )
    host.start()
    try {
      val session =
        host.acquireRecordingSession(
          previewId = SWAP_PREVIEW_ID,
          recordingId = "rec-swap",
          classLoader = javaClass.classLoader ?: ClassLoader.getSystemClassLoader(),
          fps = 30,
          scale = 1.0f,
          overrides = null,
          live = true,
        )
      val result =
        try {
          Thread.sleep(200L)
          session.postInput(
            RecordingInputParams(
              recordingId = "rec-swap",
              kind = InteractiveInputKind.CLICK,
              pixelX = 60,
              pixelY = 30,
            )
          )
          Thread.sleep(200L)
          session.stop()
        } finally {
          session.close()
        }

      val click = result.capturedScript.firstOrNull { it.pixelX == null && it.target != null }
      assertNotNull(
        "the live click should have been reverse-mapped to a handle; captured " +
          "${result.capturedScript}",
        click,
      )
      // The load-bearing assertion. `after-click` here would mean the reverse map ran against the
      // screen the click produced — a captured script that replays as a click on a node that did
      // not exist when the user aimed at it.
      assertEquals(
        "a live click must name the node that was under the pointer when it landed",
        "before-click",
        click!!.target!!.testTag,
      )
      assertTrue("the pixels must be dropped once a handle resolved", click.pixelY == null)
    } finally {
      host.shutdown()
    }
  }

  private companion object {
    private const val FIXTURE_CLASS = "ee.schimke.composeai.daemon.RedFixturePreviewsKt"
    private const val SWAP_PREVIEW_ID = "click-swaps-target"
    /** Any timestamp a session might have driven the scene to; the value itself is arbitrary. */
    private const val SOME_LATE_FRAME_NANOS = 250_000_000L
  }
}
