package ee.schimke.composeai.daemon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
