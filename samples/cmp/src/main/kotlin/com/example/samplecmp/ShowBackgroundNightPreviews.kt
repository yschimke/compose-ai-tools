package com.example.samplecmp

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Regression fixture for `@Preview(showBackground = true)` under a night `uiMode`.
 *
 * Deliberately **does not** wrap its content in a `Surface`: that is what makes the preview's
 * backing colour visible, and it is the shape that regressed. `MaterialTheme` on its own paints
 * nothing and leaves `LocalContentColor` at black, so a composable like this shows the renderer's
 * `showBackground` default directly. When that default was a hardcoded white, the dark variant came
 * out as dark-theme text on a white sheet — light-on-light, unreadable, and nothing like what
 * Android Studio draws for the same annotation (Studio paints the theme's `windowBackground`).
 *
 * Rendered in both modes so the diff bot carries the pair on every PR: the light sticker must stay
 * white and the dark one must stay dark. See `ee.schimke.composeai.data.render.PreviewBackground`.
 */
@Preview(name = "Day", showBackground = true, widthDp = 240, heightDp = 96)
@Preview(
  name = "Night",
  showBackground = true,
  widthDp = 240,
  heightDp = 96,
  // 32 == android.content.res.Configuration.UI_MODE_NIGHT_YES. The CMP common source set has no
  // `android.*`, so the raw bit value is used directly (discovery + renderer treat it as an int).
  uiMode = 32,
)
@Composable
fun ShowBackgroundNoSurfacePreview() {
  val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
  MaterialTheme(colorScheme = scheme) {
    Column(modifier = Modifier.padding(16.dp)) {
      // Both colours are stated explicitly. Left to `LocalContentColor` they would come out black
      // in either mode (only a `Surface` sets that local), which is a preview-author trap worth
      // knowing about but would muddy what this fixture is for: the backing colour alone.
      Text(
        "showBackground = true",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        "no Surface — the backing colour is what you see",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}
