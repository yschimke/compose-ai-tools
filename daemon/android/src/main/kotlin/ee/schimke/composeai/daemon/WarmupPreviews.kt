package ee.schimke.composeai.daemon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Boot-time warm-render target for background-booted sandbox slots — see
 * [RobolectricHost.warmSlotRender]. Rendered once per background-booted sandbox so the slot's
 * first *real* render doesn't pay the per-sandbox cold-render init: first composition + layout
 * pass, the NATIVE-graphics `HardwareRenderer` pipeline Roborazzi's capture walks, the font/text
 * stack (the [BasicText] below is what pulls that in), and the PNG encode.
 *
 * Ships in the daemon's own main source set (not a user preview, not a testFixture) so it is
 * always resolvable on the sandbox classpath regardless of what the served bundle carries, and a
 * broken or heavy first catalog entry can never poison the warm-up. Keep it trivial and
 * dependency-light: foundation + ui only, no Material, no resources.
 */
@Composable
fun DaemonWarmupPreview() {
  Box(modifier = Modifier.fillMaxSize().background(Color(0xFF3A3A3A))) {
    BasicText(text = "compose-preview warm-up")
  }
}
