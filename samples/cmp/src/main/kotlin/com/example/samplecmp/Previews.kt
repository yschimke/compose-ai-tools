package com.example.samplecmp

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Preview(name = "Red Box", backgroundColor = 0xFFFF0000, showBackground = true)
@Composable
fun RedBoxPreview() {
  Box(modifier = Modifier.size(100.dp).background(Color.Red), contentAlignment = Alignment.Center) {
    Text("Red", color = Color.White)
  }
}

@Preview(name = "Blue Box", backgroundColor = 0xFF0000FF, showBackground = true)
@Composable
fun BlueBoxPreview() {
  Box(
    modifier = Modifier.size(100.dp).background(Color.Blue),
    contentAlignment = Alignment.Center,
  ) {
    Text("Blue", color = Color.White)
  }
}

@Preview
@Composable
fun AppPreview() {
  App()
}

/**
 * Paints `MaterialTheme.colorScheme.primary` directly. Useful as a manual smoke check for the
 * wallpaper data extension: drive `renderNow.overrides.wallpaper` with a seed and watch this
 * preview's primary track the derived scheme.
 */
@Preview(name = "Wallpaper Demo")
@Composable
fun WallpaperDemoPreview() {
  Box(
    modifier = Modifier.size(120.dp).background(MaterialTheme.colorScheme.primary),
    contentAlignment = Alignment.Center,
  ) {
    Text("Primary", color = MaterialTheme.colorScheme.onPrimary)
  }
}

/**
 * Issue #1930: rendering a known phone (Pixel 8) with `showSystemUi = true` on the **desktop /
 * Compose-Multiplatform** backend should produce a PNG that *looks* like a phone screenshot — full
 * device canvas plus the synthetic system bars (status bar at the top, gesture-pill nav at the
 * bottom). The desktop/Skiko renderer has no Android `SystemUI` process to draw real bars, so it
 * simulates them via `SystemBarsFrame` to match what the Android renderer (issue #256) and Android
 * Studio draw for the same `@Preview`, so a single committed design reference matches either
 * candidate.
 *
 * Two variants — light and dark — exercise the `uiMode`-aware tint branches of the frame so
 * reviewers can confirm the bars adapt to the theme rather than always rendering as light chrome on
 * a dark surface. This is the CMP-desktop counterpart of the Android sample's
 * `Pixel8SystemUiPreview`.
 */
@Preview(name = "Pixel 8", device = "id:pixel_8", showSystemUi = true)
@Preview(
  name = "Pixel 8 - Night",
  device = "id:pixel_8",
  showSystemUi = true,
  // 32 == android.content.res.Configuration.UI_MODE_NIGHT_YES. The CMP common source set has no
  // `android.*`, so the raw bit value is used directly (the discovery + renderer treat it as an
  // int).
  uiMode = 32,
)
@Composable
fun Pixel8SystemUiPreview() {
  val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
  MaterialTheme(colorScheme = scheme) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Column(modifier = Modifier.padding(24.dp)) {
        Text(text = "Pixel 8", color = MaterialTheme.colorScheme.onBackground, fontSize = 28.sp)
        Box(modifier = Modifier.size(8.dp))
        Text(text = "showSystemUi = true", color = MaterialTheme.colorScheme.onBackground)
      }
    }
  }
}
