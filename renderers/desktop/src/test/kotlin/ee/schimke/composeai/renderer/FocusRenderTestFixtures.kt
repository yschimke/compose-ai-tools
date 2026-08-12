package ee.schimke.composeai.renderer

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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

/**
 * A scrollable column of focusable buttons, tall enough that the last one starts well outside the
 * viewport. Fixture for the `@ScrollingPreview(END)` + `@FocusedPreview` case: the two drives have
 * to compose in one scene, so the capture shows the list at its end *and* a focused button, not one
 * of the two.
 */
@Composable
fun ScrollableFocusableColumn() {
  MaterialTheme(colorScheme = lightColorScheme()) {
    // `LazyColumn` rather than `Column(verticalScroll(...))`: the drive dispatches
    // `SemanticsActions.ScrollBy`, and the lazy list is the shape the desktop scroll path is
    // proven against (see `ScrollEndCaptureTest`).
    LazyColumn(modifier = Modifier.fillMaxSize()) {
      items(20) { index ->
        Button(onClick = {}, modifier = Modifier.padding(8.dp)) { Text("Item " + (index + 1)) }
      }
    }
  }
}
