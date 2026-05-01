package ee.schimke.composeai.daemon

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

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
 * Fixture for the v2 PR-2 desktop interactive integration test (INTERACTIVE.md § 9.9.2). Paints
 * red on first composition; flips a `remember`'d `mutableStateOf` to green on the first
 * click anywhere in the surface, so the test can assert "click reaches the composition" by
 * comparing pre-click and post-click PNG bytes.
 *
 * The state lives in a `remember { mutableStateOf(...) }` — the load-bearing assertion of v2's
 * "held composition" promise: a one-shot render path would reset this state on every render and
 * the flip would never become visible. The interactive session keeps the scene warm across
 * inputs, so the recomposition sticks and the second render observes green.
 *
 * `Modifier.clickable` requires `LocalInspectionMode = false` (otherwise the modifier no-ops, per
 * Compose's inspection-mode contract). v2's [DesktopInteractiveSession] sets up its scene with
 * `runInspectionMode = false`, so the click reaches us; v1's one-shot render would still pass
 * inspection-mode = true and the click would be silently dropped.
 */
@Composable
fun ClickToGreenSquare() {
  var clicked by remember { mutableStateOf(false) }
  val color = if (clicked) Color(0xFF66BB6A) else Color(0xFFEF5350)
  Box(modifier = Modifier.fillMaxSize().background(color).clickable { clicked = true })
}
