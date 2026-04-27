package ee.schimke.composeai.daemon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Test fixtures for [RenderEngineTest]. Mirrors `:renderer-desktop-daemon`'s
 * `RedFixturePreviews.kt` so a future cross-backend driver can hit the same `RedSquare` /
 * `BlueSquare` previewIds against either backend with the same baseline PNG (modulo expected AA
 * drift between Skiko and Robolectric/HardwareRenderer).
 *
 * Each preview is a single solid-colour fill — the test asserts the PNG's dominant colour
 * matches, mirroring the "is this mostly red?" assertion pattern from
 * `samples/android/.../ScrollPreviewPixelTest.kt`.
 *
 * Lives in the test source set rather than `testFixtures` because B1.4 only needs same-module
 * verification; promotion to `testFixtures` is cheap (mirror `:renderer-desktop-daemon`'s
 * build.gradle.kts edit) and can land alongside whichever cross-module driver first needs it.
 */
@Composable
fun RedSquare() {
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFFEF5350)))
}

@Composable
fun BlueSquare() {
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFF42A5F5)))
}
