package ee.schimke.composeai.daemon

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Test fixtures for [RenderEngineTest] / [JsonRpcDesktopIntegrationTest]. Lives in the test source
 * set so we don't pollute production code with `@Composable` previews used only for verification.
 *
 * Each preview is a single solid-colour fill at the test sandbox size — the test asserts the PNG's
 * dominant colour matches, mirroring the "is this mostly red?" assertion pattern from
 * `samples/android/.../ScrollPreviewPixelTest.kt`.
 */
@Composable
fun RedSquare() {
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFFEF5350)))
}

@Composable
fun BlueSquare() {
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFF42A5F5)))
}

/**
 * Fixture for the B-desktop.1.6 cancellation-invariant regression test. Sleeps for ~500ms inside
 * the composition body so the test can race a `host.shutdown(...)` call against an in-flight render
 * and assert the render still completes (per
 * [DESIGN.md § 9](../../../../../../docs/daemon/DESIGN.md#no-mid-render-cancellation--invariant--enforcement)).
 *
 * The sleep is deliberately *inside* the composition rather than around `scene.render()` because we
 * want to exercise the very window that's most dangerous to cancel — a partly-built Compose graph
 * is the worst leak shape per
 * [PREDICTIVE.md § 9](../../../../../../docs/daemon/PREDICTIVE.md#9-decisions-made).
 */
@Composable
fun SlowSquare() {
  Thread.sleep(500)
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFF80FFAA.toInt())))
}

/**
 * Third solid-colour fixture used by D-harness.v1.5b's S4 real-mode test (visibility filter). Same
 * shape as [RedSquare] / [BlueSquare]; distinct hue so a wire-level mix-up between the three
 * preview ids would surface as a pixel-diff failure against the per-id baseline PNG.
 */
@Composable
fun GreenSquare() {
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFF66BB6A)))
}

/**
 * Fixture for D-harness.v1.5b's S5 real-mode test (renderFailed surfacing). Throws unconditionally
 * inside the composition body so [RenderEngine] propagates the exception out of `scene.render()`
 * and `JsonRpcServer.emitRenderFailed` emits a `renderFailed` notification.
 *
 * The thrown message is matched literally by the test's assertion on
 * `renderFailed.params.error.message`. Kept short and obviously-test-only ("boom") to avoid being
 * mistaken for a real render error in stderr scrollback.
 */
@Composable
fun BoomComposable() {
  error("boom")
}

/**
 * v2 interactive-mode fixture — fills red on first composition, flips to green when clicked. Used
 * by `DesktopInteractiveSessionTest` to assert end-to-end that `interactive/input →
 * DesktopInteractiveSession.dispatch → ImageComposeScene.sendPointerEvent → Modifier.clickable {}`
 * actually mutates composition state. Without v2 (one-shot RenderEngine path), `remember` resets
 * between renders and this preview always paints red — which is the negative-control assertion the
 * v2 work needs to flip.
 *
 * The whole-card `Modifier.clickable {}` covers every click coord we'd plausibly send from the
 * test, so the dispatch math doesn't have to be pixel-perfect; v2's wire shape carries
 * image-natural pixels, and the click region is the entire scene.
 */
@Composable
fun ClickToggleSquare() {
  var clicked by remember { mutableStateOf(false) }
  val color = if (clicked) Color(0xFF66BB6A) else Color(0xFFEF5350)
  // `awaitEachGesture { awaitFirstDown() }` is the simplest pointer-input shape that fires on a
  // bare Press event — no tap-gesture timing, no slop check, no need for a matching Release.
  // We deliberately avoid `Modifier.clickable {}` here because it sits on top of
  // `detectTapGestures` whose coroutine timing is non-trivial under [ImageComposeScene]'s manual
  // clock. The v2 wire-shape work just needs to prove "the dispatched pointer event reaches the
  // composition"; that's what this fixture asserts.
  Box(
    modifier =
      Modifier.fillMaxSize().background(color).pointerInput(Unit) {
        awaitPointerEventScope {
          awaitFirstDown()
          clicked = true
        }
      }
  )
}

/**
 * Reads `isSystemInDarkTheme()` and fills the box with white in light mode, black in dark mode.
 * Used by `OverrideIntegrationTest` (desktop) to prove `renderNow.overrides.uiMode` reaches
 * `LocalSystemTheme` — Compose Desktop's `isSystemInDarkTheme()` reads that local rather than the
 * OS-level Skiko theme probe.
 */
@Composable
fun DarkAwareSquare() {
  val bg = if (androidx.compose.foundation.isSystemInDarkTheme()) Color.Black else Color.White
  Box(modifier = Modifier.fillMaxSize().background(bg))
}

/**
 * Reads `LocalDensity.current.fontScale` and renders a square whose colour encodes the scale —
 * black at fontScale=1.0 (background), white at fontScale=2.0. A pure-pixel signal for proving the
 * override reaches `LocalDensity` without needing `Text` rendering (which would entangle
 * font-metrics across platforms).
 */
@Composable
fun FontScaleAwareSquare() {
  val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
  val bg = if (fontScale >= 1.5f) Color.White else Color.Black
  Box(modifier = Modifier.fillMaxSize().background(bg))
}
