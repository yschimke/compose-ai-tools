package ee.schimke.composeai.daemon

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.unit.dp

/**
 * Test fixtures for [RenderEngineTest] and the D-harness.v2 Android real-mode scenarios. Lives in
 * the `testFixtures` source set so `:daemon:harness`'s test runtime classpath can pull these
 * composables via `testImplementation(testFixtures(project(":daemon:android")))` — same
 * shape `:daemon:desktop`'s testFixtures already use. Promoted here from `src/test/...` in
 * D-harness.v2 (was previously test-source-only because B1.4 only needed same-module verification).
 *
 * Each preview is a single solid-colour fill, identical in coordinates and hue to the desktop
 * counterpart in [`daemon/desktop`'s `RedFixturePreviews`][
 * ee.schimke.composeai.daemon.RedSquare] (see also `daemon/harness/baselines/desktop/`).
 * Class FQN — `ee.schimke.composeai.daemon.RedFixturePreviewsKt` — and function names match
 * across both backends so a single `RealModePreview(className=…, functionName="RedSquare")` row in
 * the harness's `realModeScenario(...)` manifest resolves to the right composable on either
 * target. The PNG bytes will differ per target (Skiko AA vs Robolectric/HardwareRenderer) — that's
 * what the per-target baseline directories under `daemon/harness/baselines/<target>/` absorb.
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
 * Third solid-colour fixture used by D-harness.v2's S4 Android real-mode test (visibility filter).
 * Same shape as [RedSquare] / [BlueSquare]; distinct hue so a wire-level mix-up between the three
 * preview ids surfaces as a pixel-diff failure against the per-id baseline PNG.
 */
@Composable
fun GreenSquare() {
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFF66BB6A)))
}

/**
 * Fixture for D-harness.v2's S2 Android real-mode test (drain semantics). Sleeps for ~500ms inside
 * the composition body so the harness can race a `shutdown` request against an in-flight render
 * and assert the render still completes (per
 * [DESIGN.md § 9](../../../../../../docs/daemon/DESIGN.md#no-mid-render-cancellation--invariant--enforcement)).
 *
 * The sleep is deliberately *inside* the composition rather than around the capture — we want to
 * exercise the very window that's most dangerous to cancel: a partly-built Compose graph is the
 * worst leak shape per [PREDICTIVE.md § 9](../../../../../../docs/daemon/PREDICTIVE.md#9-decisions-made).
 * Mirrors the desktop counterpart's contract exactly.
 */
@Composable
fun SlowSquare() {
  Thread.sleep(500)
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFF80FFAA.toInt())))
}

/**
 * Fixture for D-harness.v2's S5 Android real-mode test (renderFailed surfacing). Throws
 * unconditionally inside the composition body so [RenderEngine] propagates the exception out of
 * `setContent`, the dispatcher catch in [RobolectricHost.SandboxRunner] returns a stub fallback
 * rather than a real `RenderResult`, and `JsonRpcServer.runHostSubmitter` surfaces the failure as
 * a `renderFailed` notification (… or, today, falls through to `renderFinished` with the stub
 * path — see S5 Android test KDoc for the documented gap).
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
 * Reads `isSystemInDarkTheme()` and fills the box with white in light mode, black in dark mode.
 * Used by `OverrideIntegrationTest` to prove `renderNow.overrides.uiMode` actually flips the
 * resource qualifier — `setQualifiers("+night")` toggles `Configuration.UI_MODE_NIGHT_YES`,
 * which is what `isSystemInDarkTheme()` reads.
 */
@Composable
fun DarkAwareSquare() {
  val bg =
    if (androidx.compose.foundation.isSystemInDarkTheme()) Color.Black else Color.White
  Box(modifier = Modifier.fillMaxSize().background(bg))
}

/**
 * Stateful fixture for the v3 Android-interactive test ([AndroidInteractiveSessionTest]). Paints
 * red on first composition; flips to green when any pointer-down event lands. Same shape as the
 * desktop `ClickToggleSquare` fixture in `daemon/desktop`'s testFixtures so the two backends'
 * integration tests assert against an identical state-mutation contract ("first capture red;
 * dispatch click; second capture green — `remember{}` state survived across captures").
 *
 * Uses `awaitFirstDown` rather than `Modifier.clickable` because `clickable` sits on top of
 * `detectTapGestures`, whose coroutine timing under Compose's paused clock is non-trivial. The
 * `RobolectricInteractiveProbeTest` empirical probe verified `awaitFirstDown` fires reliably for
 * a synthesised `MotionEvent` dispatched through `decorView.dispatchTouchEvent` under the held
 * rule — the simplest pointerInput shape gives the cleanest yes/no answer for the wire-level
 * test and matches what the desktop counterpart already asserts on.
 */
@Composable
fun ClickToggleSquare() {
  var clicked by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
  val color = if (clicked) Color(0xFF66BB6A) else Color(0xFFEF5350)
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

@Composable
fun ClickableToggleSquare() {
  var clicked by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
  val color = if (clicked) Color(0xFF66BB6A) else Color(0xFFEF5350)
  Box(
    modifier =
      Modifier.fillMaxSize()
        .clickable { clicked = true }
        .background(color)
  )
}

@Composable
fun DragScrollableSquare() {
  val scrollState = rememberScrollState()
  Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
    Box(modifier = Modifier.width(96.dp).height(96.dp).background(Color(0xFFEF5350)))
    Box(modifier = Modifier.width(96.dp).height(96.dp).background(Color(0xFF66BB6A)))
  }
}

@Composable
fun ReleasePositionSquare() {
  var releasedNearTop by androidx.compose.runtime.remember {
    androidx.compose.runtime.mutableStateOf(false)
  }
  val color = if (releasedNearTop) Color(0xFF66BB6A) else Color(0xFFEF5350)
  Box(
    modifier =
      Modifier.fillMaxSize().background(color).pointerInput(Unit) {
        awaitPointerEventScope {
          awaitFirstDown()
          while (true) {
            val change = awaitPointerEvent().changes.first()
            if (change.changedToUp()) {
              releasedNearTop = change.position.y < 24f
              break
            }
          }
        }
      }
  )
}

@Composable
fun RotaryToggleSquare() {
  var scrolled by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
  val requester = androidx.compose.runtime.remember { FocusRequester() }
  LaunchedEffect(Unit) { requester.requestFocus() }
  val color = if (scrolled) Color(0xFF66BB6A) else Color(0xFFEF5350)
  Box(
    modifier =
      Modifier
        .fillMaxSize()
        .onRotaryScrollEvent {
          scrolled = true
          true
        }
        .focusRequester(requester)
        .focusable()
        .background(color)
  )
}
