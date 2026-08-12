package ee.schimke.composeai.renderer

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Fixture for [DesktopFocusRendererTest] — the CMP-desktop twin of `:samples:android-alpha`'s
 * `ButtonRow`, which is what the Android `@FocusedPreview` pixel test drives.
 *
 * Deliberately plain: three stock `Button`s, no `MutableInteractionSource`, no `LaunchedEffect`
 * emitting interactions. Every focus ring and pressed state layer in the rendered PNGs therefore
 * has to come from input the renderer dispatched and the component itself consumed — which is the
 * whole property the tests assert (issue #3672).
 *
 * Top-level so the renderer can reflect it exactly as it reflects a consumer's `@Preview`
 * (`Class.forName("…FocusRenderTestFixturesKt")` + `getDeclaredComposableMethod`).
 */
@Composable
fun FocusableButtonRow() {
  MaterialTheme(colorScheme = lightColorScheme()) {
    Row(modifier = Modifier.padding(16.dp)) {
      listOf("Save", "Edit", "Share").forEach { label ->
        Button(onClick = {}, modifier = Modifier.padding(end = 8.dp)) { Text(label) }
      }
    }
  }
}

/**
 * A row with nothing focusable in it — the decline case. `renderFocusPreview` must report `false`
 * for this rather than writing a capture that claims a focus state nothing could have taken, so the
 * caller falls back to the ordinary undriven render.
 */
@Composable
fun NoFocusableRow() {
  MaterialTheme(colorScheme = lightColorScheme()) {
    Row(modifier = Modifier.padding(16.dp)) { Text("Nothing here can take focus") }
  }
}
