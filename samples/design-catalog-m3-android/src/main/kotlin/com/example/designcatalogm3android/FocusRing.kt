package com.example.designcatalogm3android

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.LocalRippleThemeConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.RippleDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.preview.FocusedPreview

/**
 * Android-only catalog theme opting into Material 3's inset focus ring
 * ([RippleDefaults.InsetFocusRingThemeConfiguration] via [LocalRippleThemeConfiguration], material3
 * 1.5.0-alpha+). CMP `material3` lacks it, so these stickers render here and are folded into the
 * `compose-m3` catalog. The ring is `primary` over `surface` (not the stock muted colours) so it
 * reads at sticker size; only the `focus` ripple is overridden.
 */
@Composable
private fun FocusRingSticker(content: @Composable () -> Unit) {
  val dark = isSystemInDarkTheme()
  val colorScheme = if (dark) darkColorScheme() else lightColorScheme()
  MaterialTheme(colorScheme = colorScheme) {
    CompositionLocalProvider(
      LocalRippleThemeConfiguration provides RippleDefaults.InsetFocusRingThemeConfiguration,
      LocalRippleConfiguration provides
        RippleConfiguration(
          focus =
            RippleConfiguration.Focus.InsetRing(
              outerStrokeColor = colorScheme.primary,
              innerStrokeColor = colorScheme.surface,
            )
        ),
    ) {
      Surface { androidx.compose.foundation.layout.Box(Modifier.padding(16.dp)) { content() } }
    }
  }
}

/**
 * The keyboard-focus state of the filled button, showing the inset focus ring. The function name
 * must stay `FilledButtonFocused`: the generator folds it onto `Button/Filled`'s `keyboard-focus`
 * variant by name.
 *
 * Focus is real, via `@FocusedPreview` (a `FocusManager.moveFocus` traversal in Keyboard input mode,
 * which Robolectric's permanent Touch mode otherwise refuses). A single capture keeps the plain
 * `renders/<id>.png` name.
 */
// Light + dark, like the CMP catalog's `@CatalogModes`; inlined so discovery resolves them reliably.
@Preview(name = "Light", showBackground = true, group = "modes")
@Preview(
  name = "Dark",
  showBackground = true,
  uiMode = Configuration.UI_MODE_NIGHT_YES,
  group = "modes",
)
@FocusedPreview(indices = [0])
@Composable
fun FilledButtonFocused() = FocusRingSticker { Button(onClick = {}) { Text("Focused") } }
