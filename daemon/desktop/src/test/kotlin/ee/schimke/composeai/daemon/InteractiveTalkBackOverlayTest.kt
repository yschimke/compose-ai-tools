package ee.schimke.composeai.daemon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `overrides.talkBack = true` must paint the TalkBack focus overlay on the frames of a **held
 * interactive session** — the frames the serve viewer's Live Compose lane shows, since
 * `stream/start` is layered on an interactive session rather than a recording one.
 *
 * The overlay used to be composited only in `DesktopRecordingSession.frameBytes`, so the viewer's
 * "Accessibility (TalkBack)" toggle travelled all the way to the daemon (parsed by
 * `ServeOverrides`, advertised in `DesktopHost.supportedOverrides`) and then painted nothing. It's
 * now applied in `RenderEngine.renderOnce`, the single encode every non-recording capture funnels
 * through, so the one-shot `/render` snapshot and the live stream agree. Recording sessions keep
 * their own per-frame walk (covered by `DesktopRecordingSessionTest`) and never call `renderOnce`.
 *
 * The assertion is the focus rectangle's green (`DesktopTalkBackFocusOverlay.FOCUS_GREEN`,
 * `#00C853`), which appears nowhere in the fixture's own palette — so a match means the overlay
 * drew. The negative case (same preview, no override) proves the green comes from the overlay and
 * not from the composable.
 */
class InteractiveTalkBackOverlayTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun talkBack_override_paints_the_focus_overlay_on_interactive_frames() {
    assertTrue(
      "a talkBack=true interactive session must contain focus-green pixels — if 0, the overlay " +
        "never reached the live lane's render path (RenderEngine.renderOnce)",
      focusGreenPct(talkBack = true) > 0.0005,
    )
  }

  @Test
  fun without_the_override_the_same_preview_has_no_focus_green() {
    assertTrue(
      "the fixture must not paint focus green on its own, or the positive case proves nothing",
      focusGreenPct(talkBack = null) == 0.0,
    )
  }

  /** Fraction of focus-green pixels in one interactive frame rendered under [talkBack]. */
  private fun focusGreenPct(talkBack: Boolean?): Double {
    val engine = RenderEngine(outputDir = tempFolder.newFolder("talkback-${talkBack}-renders"))
    val host =
      DesktopHost(
        engine = engine,
        previewSpecResolver = { previewId ->
          if (previewId == PREVIEW_ID) {
            RenderSpec(
              className = "ee.schimke.composeai.daemon.InteractiveTalkBackOverlayTestKt",
              functionName = "LabelledSquares",
              widthPx = CANVAS_PX,
              heightPx = CANVAS_PX,
              density = 1.0f,
              outputBaseName = "interactive-talkback-$talkBack",
            )
          } else null
        },
      )
    host.start()
    try {
      val session =
        host.acquireInteractiveSession(
          previewId = PREVIEW_ID,
          classLoader =
            InteractiveTalkBackOverlayTest::class.java.classLoader
              ?: ClassLoader.getSystemClassLoader(),
          overrides = PreviewOverrides(talkBack = talkBack),
        )
      return try {
        // The first render only bootstraps the held composition; sample the second, the same way
        // the live lane's first *pushed* frame is produced.
        session.render(requestId = RenderHost.nextRequestId())
        val frame = session.render(requestId = RenderHost.nextRequestId())
        TouchOverlayTestSupport.pixelMatchPctApprox(
          TouchOverlayTestSupport.readPng(File(frame.pngPath!!)),
          expectedRgb = FOCUS_GREEN_RGB,
          perChannelTolerance = 24,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  companion object {
    private const val PREVIEW_ID = "interactive-talkback"
    private const val CANVAS_PX = 240

    /** `DesktopTalkBackFocusOverlay.FOCUS_GREEN` — the focus rectangle's stroke colour. */
    private const val FOCUS_GREEN_RGB = 0x00C853
  }
}

/**
 * Two content-described squares on a plain background: enough merged semantics for
 * `TalkBackTraversal.focusStops` to return focus stops, and a palette (grey / blue / orange) with
 * no green in it.
 */
@Composable
fun LabelledSquares() {
  Column(
    modifier = Modifier.fillMaxSize().background(Color(0xFFECEFF1)),
    verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(label = "First", color = Color(0xFF1976D2))
    Box(label = "Second", color = Color(0xFFEF6C00))
  }
}

@Composable
private fun Box(label: String, color: Color) {
  androidx.compose.foundation.layout.Box(
    // `mergeDescendants = true` makes each square a merged semantics root, which is exactly what
    // `TalkBackTraversal.focusStops` selects — so the fixture has two focus stops by construction
    // rather than by inference from the surrounding tree.
    modifier =
      Modifier.size(72.dp).background(color).semantics(mergeDescendants = true) {
        contentDescription = label
      }
  )
}
