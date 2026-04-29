package ee.schimke.composeai.daemon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

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
