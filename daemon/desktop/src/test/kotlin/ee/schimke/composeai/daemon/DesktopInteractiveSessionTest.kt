package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.InteractiveInputParams
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v2 click-into-composition end-to-end test — see
 * [INTERACTIVE.md § 9](../../../../../../docs/daemon/INTERACTIVE.md#9-v2--click-dispatch-into-composition).
 *
 * Drives the desktop host's interactive surface against a stateful composable ([ClickToggleSquare])
 * that paints red on first composition and green after one click. The test verifies:
 *
 * 1. **Bootstrap render is red** — the initial frame, with no input dispatched, paints the
 *    `mutableStateOf(false)` branch.
 * 2. **Click dispatch flips state and re-renders green** — `interactive/input` with kind=CLICK,
 *    routed through [DesktopInteractiveSession.dispatch] → `ImageComposeScene.sendPointerEvent` →
 *    `Modifier.clickable {}` → `clicked = true`, then [DesktopInteractiveSession.render] encodes
 *    the post-click composition.
 * 3. **`remember` state survives across renders** — implicit in (2). Without v2's held scene the
 *    second `setUp` would reset the `mutableStateOf` and the second render would paint red, so a
 *    green second render is the load-bearing assertion.
 *
 * Pixel-match helper inlined to avoid a circular dep on the harness's `PixelDiff` (same reasoning
 * as `RenderEngineTest`).
 */
class DesktopInteractiveSessionTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun click_input_flips_state_and_repaints() {
    val outputDir = tempFolder.newFolder("interactive-renders")
    val engine = RenderEngine(outputDir = outputDir)
    // The host needs a previewSpecResolver to enable v2 sessions; for the test we resolve the
    // single fixture previewId we use into a hard-coded RenderSpec.
    val host =
      DesktopHost(
        engine = engine,
        previewSpecResolver = { previewId ->
          if (previewId == FIXTURE_PREVIEW_ID) {
            RenderSpec(
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "ClickToggleSquare",
              widthPx = 64,
              heightPx = 64,
              density = 1.0f,
              outputBaseName = "click-toggle-square",
            )
          } else null
        },
      )
    host.start()
    try {
      // Acquire the session — engine.setUp runs, the scene is held warm, classloader installed.
      val session =
        host.acquireInteractiveSession(
          previewId = FIXTURE_PREVIEW_ID,
          classLoader =
            DesktopInteractiveSessionTest::class.java.classLoader
              ?: ClassLoader.getSystemClassLoader(),
        )
      try {
        // 1. Bootstrap render — no input dispatched yet, scene paints the `clicked = false`
        //    branch (red).
        val first = session.render(requestId = RenderHost.nextRequestId())
        assertNotNull("first render must produce a pngPath", first.pngPath)
        val firstImage = readPng(File(first.pngPath!!))
        val redMatch = pixelMatchPct(firstImage, expectedRgb = 0xEF5350, perChannelTolerance = 8)
        assertTrue(
          "expected ≥ 95% red pixels on bootstrap render; got ${"%.2f".format(redMatch * 100)}%",
          redMatch >= 0.95,
        )

        // 2. Dispatch a click in the centre of the scene. The whole-card `Modifier.clickable {}`
        //    catches it; the held scene's `mutableStateOf` flips to true.
        session.dispatch(
          InteractiveInputParams(
            frameStreamId = "test-stream-1",
            kind = InteractiveInputKind.CLICK,
            pixelX = 32,
            pixelY = 32,
          )
        )

        // 3. Re-render — the second frame should now paint the `clicked = true` branch (green).
        val second = session.render(requestId = RenderHost.nextRequestId())
        assertNotNull("second render must produce a pngPath", second.pngPath)
        val secondImage = readPng(File(second.pngPath!!))
        val greenMatch = pixelMatchPct(secondImage, expectedRgb = 0x66BB6A, perChannelTolerance = 8)
        assertTrue(
          "expected ≥ 95% green pixels after click; got ${"%.2f".format(greenMatch * 100)}% — " +
            "this is the load-bearing v2 assertion (remember{} state must survive across renders)",
          greenMatch >= 0.95,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
    assertFalse(
      "render thread must not observe an InterruptedException",
      host.renderThreadInterrupted,
    )
  }

  @Test
  fun live_render_advances_frame_clock_with_monotonic_wall_time() {
    val outputDir = tempFolder.newFolder("interactive-frame-clock-renders")
    val frameTimes =
      java.util.ArrayDeque(listOf(1_000_000_000L, 1_000_000_000L, 1_300_000_000L, 1_300_000_000L))
    val engine = RenderEngine(outputDir = outputDir, frameNanoTime = { frameTimes.removeFirst() })
    val host =
      DesktopHost(
        engine = engine,
        previewSpecResolver = { previewId ->
          if (previewId == FRAME_CLOCK_PREVIEW_ID) {
            RenderSpec(
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "FrameClockSquare",
              widthPx = 64,
              heightPx = 64,
              density = 1.0f,
              outputBaseName = "frame-clock-square",
            )
          } else null
        },
      )
    host.start()
    try {
      val session =
        host.acquireInteractiveSession(
          previewId = FRAME_CLOCK_PREVIEW_ID,
          classLoader =
            DesktopInteractiveSessionTest::class.java.classLoader
              ?: ClassLoader.getSystemClassLoader(),
        )
      try {
        val first = session.render(requestId = RenderHost.nextRequestId())
        val firstImage = readPng(File(first.pngPath!!))
        val redMatch = pixelMatchPct(firstImage, expectedRgb = 0xEF5350, perChannelTolerance = 8)
        assertTrue(
          "expected frame-clock fixture to start red; got ${"%.2f".format(redMatch * 100)}%",
          redMatch >= 0.95,
        )

        val second = session.render(requestId = RenderHost.nextRequestId())
        val secondImage = readPng(File(second.pngPath!!))
        val greenMatch = pixelMatchPct(secondImage, expectedRgb = 0x66BB6A, perChannelTolerance = 8)
        assertTrue(
          "expected frame-clock fixture to turn green after 300ms of render time; got " +
            "${"%.2f".format(greenMatch * 100)}%",
          greenMatch >= 0.95,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
    assertTrue("all injected frame times should be consumed", frameTimes.isEmpty())
  }

  @Test
  fun acquire_throws_unsupported_when_resolver_unwired() {
    // No previewSpecResolver passed — host advertises no v2 support and throws
    // UnsupportedOperationException, which `JsonRpcServer.handleInteractiveStart` translates into
    // a clean fall-back to the v1 stateless dispatch path.
    val host = DesktopHost(engine = RenderEngine(outputDir = tempFolder.newFolder("ignored")))
    host.start()
    try {
      val thrown =
        runCatching {
            host.acquireInteractiveSession(
              previewId = FIXTURE_PREVIEW_ID,
              classLoader = DesktopInteractiveSessionTest::class.java.classLoader!!,
            )
          }
          .exceptionOrNull()
      assertNotNull(
        "DesktopHost without a resolver must throw on acquireInteractiveSession",
        thrown,
      )
      assertTrue(
        "expected UnsupportedOperationException; got ${thrown?.javaClass}",
        thrown is UnsupportedOperationException,
      )
    } finally {
      host.shutdown()
    }
  }

  @Test
  fun acquire_throws_unsupported_when_resolver_returns_null() {
    val host =
      DesktopHost(
        engine = RenderEngine(outputDir = tempFolder.newFolder("ignored")),
        previewSpecResolver = { _ -> null },
      )
    host.start()
    try {
      val thrown =
        runCatching {
            host.acquireInteractiveSession(
              previewId = "unknown-preview",
              classLoader = DesktopInteractiveSessionTest::class.java.classLoader!!,
            )
          }
          .exceptionOrNull()
      assertTrue(
        "expected UnsupportedOperationException for null resolver result; got ${thrown?.javaClass}",
        thrown is UnsupportedOperationException,
      )
    } finally {
      host.shutdown()
    }
  }

  private fun readPng(file: File): java.awt.image.BufferedImage {
    assertTrue("rendered PNG must exist on disk: ${file.absolutePath}", file.exists())
    assertTrue("rendered PNG must be non-empty", file.length() > 0)
    val bytes = file.readBytes()
    val img =
      ByteArrayInputStream(bytes).use { ImageIO.read(it) }
        ?: error("PNG must decode via javax.imageio: ${file.absolutePath}")
    return img
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

  /**
   * Issue #1203 — end-to-end coverage for the desktop `KEY_DOWN` / `KEY_UP` wire shape into a held
   * composition. Mirrors [click_input_flips_state_and_repaints]:
   *
   * 1. **Bootstrap render is red** — `KeyPressColorSquare` paints its `pressed = false` branch.
   * 2. **`KEY_DOWN` with `keyCode = "29"` (Android `KEYCODE_A`) reaches the composition** — routed
   *    through [DesktopInteractiveSession.dispatch] → `BaseComposeScene.sendKeyEvent` → the focused
   *    node's `Modifier.onKeyEvent` lambda flips `pressed = true`.
   * 3. **`remember` state survives across renders** — the second render paints green, which is only
   *    true if the held scene persisted the mutation. Same load-bearing assertion as the click
   *    test, applied to the keyboard surface.
   *
   * Counterpart to the harness scenario `SInteractiveKeyDispatch` from the issue's acceptance
   * criteria, run in-process here so we exercise the Skiko `sendKeyEvent` translation table without
   * paying the subprocess + baseline-PNG cost of real-mode harness tests.
   */
  @Test
  fun key_down_input_flips_state_and_repaints() {
    val outputDir = tempFolder.newFolder("interactive-key-renders")
    val engine = RenderEngine(outputDir = outputDir)
    val host =
      DesktopHost(
        engine = engine,
        previewSpecResolver = { previewId ->
          if (previewId == KEY_PRESS_PREVIEW_ID) {
            RenderSpec(
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "KeyPressColorSquare",
              widthPx = 64,
              heightPx = 64,
              density = 1.0f,
              outputBaseName = "key-press-color-square",
            )
          } else null
        },
      )
    host.start()
    try {
      val session =
        host.acquireInteractiveSession(
          previewId = KEY_PRESS_PREVIEW_ID,
          classLoader =
            DesktopInteractiveSessionTest::class.java.classLoader
              ?: ClassLoader.getSystemClassLoader(),
        )
      try {
        // Bootstrap render — no key dispatched yet, `pressed = false` so the box is red.
        val first = session.render(requestId = RenderHost.nextRequestId())
        assertNotNull("first render must produce a pngPath", first.pngPath)
        val firstImage = readPng(File(first.pngPath!!))
        val redMatch = pixelMatchPct(firstImage, expectedRgb = 0xEF5350, perChannelTolerance = 8)
        assertTrue(
          "expected ≥ 95% red pixels on bootstrap render; got ${"%.2f".format(redMatch * 100)}%",
          redMatch >= 0.95,
        )

        // Dispatch KEY_DOWN(KEYCODE_A) — wire format is the Android `KEYCODE_*` int as a
        // decimal string; the desktop session translates through `androidKeycodeToComposeKey`
        // to the Skiko `Key.A` constant. `KEYCODE_A == 29` per `InteractiveKeyCodes`.
        session.dispatch(
          InteractiveInputParams(
            frameStreamId = "test-stream-key-1",
            kind = InteractiveInputKind.KEY_DOWN,
            keyCode = "29",
          )
        )

        // Re-render — `Modifier.onKeyEvent` should have flipped `pressed = true` and the box
        // should paint green. Load-bearing for the issue #1203 contract: without the new
        // dispatch wiring this would still be red (the old no-op branch).
        val second = session.render(requestId = RenderHost.nextRequestId())
        assertNotNull("second render must produce a pngPath", second.pngPath)
        val secondImage = readPng(File(second.pngPath!!))
        val greenMatch = pixelMatchPct(secondImage, expectedRgb = 0x66BB6A, perChannelTolerance = 8)
        assertTrue(
          "expected ≥ 95% green pixels after KEY_DOWN(KEYCODE_A); got " +
            "${"%.2f".format(greenMatch * 100)}% — this is the load-bearing #1203 assertion " +
            "(daemon side wire shape went from no-op to real Skiko sendKeyEvent).",
          greenMatch >= 0.95,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
    assertFalse(
      "render thread must not observe an InterruptedException",
      host.renderThreadInterrupted,
    )
  }

  /**
   * Issue #1203 — unmapped keycodes drop silently. A forward-looking client that sends a key
   * outside the translation table (e.g. `F13`) shouldn't crash the dispatch loop or flip the
   * fixture state; the second render must still be red.
   */
  @Test
  fun key_down_with_unmapped_keycode_is_a_silent_no_op() {
    val outputDir = tempFolder.newFolder("interactive-key-unmapped-renders")
    val engine = RenderEngine(outputDir = outputDir)
    val host =
      DesktopHost(
        engine = engine,
        previewSpecResolver = { previewId ->
          if (previewId == KEY_PRESS_PREVIEW_ID) {
            RenderSpec(
              className = "ee.schimke.composeai.daemon.RedFixturePreviewsKt",
              functionName = "KeyPressColorSquare",
              widthPx = 64,
              heightPx = 64,
              density = 1.0f,
              outputBaseName = "key-press-unmapped",
            )
          } else null
        },
      )
    host.start()
    try {
      val session =
        host.acquireInteractiveSession(
          previewId = KEY_PRESS_PREVIEW_ID,
          classLoader =
            DesktopInteractiveSessionTest::class.java.classLoader
              ?: ClassLoader.getSystemClassLoader(),
        )
      try {
        // Bootstrap.
        val first = session.render(requestId = RenderHost.nextRequestId())
        assertNotNull(first.pngPath)

        // Dispatch a wire keycode that the desktop translation table doesn't cover (Android
        // `KEYCODE_F13 == 183`; intentionally outside `InteractiveKeyCodes`). Must NOT throw,
        // must NOT mutate composition state.
        session.dispatch(
          InteractiveInputParams(
            frameStreamId = "test-stream-key-unmapped",
            kind = InteractiveInputKind.KEY_DOWN,
            keyCode = "183",
          )
        )

        val second = session.render(requestId = RenderHost.nextRequestId())
        assertNotNull(second.pngPath)
        val secondImage = readPng(File(second.pngPath!!))
        val redMatch = pixelMatchPct(secondImage, expectedRgb = 0xEF5350, perChannelTolerance = 8)
        assertTrue(
          "unmapped keycode must not flip state; expected ≥ 95% red; got " +
            "${"%.2f".format(redMatch * 100)}%",
          redMatch >= 0.95,
        )
      } finally {
        session.close()
      }
    } finally {
      host.shutdown()
    }
  }

  companion object {
    private const val FIXTURE_PREVIEW_ID = "click-toggle-square"
    private const val FRAME_CLOCK_PREVIEW_ID = "frame-clock-square"
    private const val KEY_PRESS_PREVIEW_ID = "key-press-color-square"
  }
}
