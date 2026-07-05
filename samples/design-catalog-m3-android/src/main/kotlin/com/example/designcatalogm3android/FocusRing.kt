package com.example.designcatalogm3android

import android.content.res.Configuration
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Android-only catalog theme that opts into the Material 3 **inset focus ring** — via
 * [RippleDefaults.InsetFocusRingRippleThemeConfiguration] over [LocalRippleThemeConfiguration]
 * (material3 1.5.0-alpha+) — the keyboard-focus indicator the design system ships. CMP `material3`
 * has no equivalent yet, so the focus-ring stickers are rendered here (Robolectric) and folded into
 * the otherwise-CMP `compose-m3` catalog by the design-artifacts generator.
 *
 * Mirrors the ring-colour override the CMP-era catalog used: the stroke goes `primary` (outer) over
 * `surface` (inner gap) instead of the stock muted `secondary`/`onSecondary`, so the ring stays
 * legible at sticker size. Only the `focus` ripple is overridden; pressed/hover are untouched.
 */
@Composable
private fun FocusRingSticker(content: @Composable () -> Unit) {
  val dark = isSystemInDarkTheme()
  val colorScheme = if (dark) darkColorScheme() else lightColorScheme()
  MaterialTheme(colorScheme = colorScheme) {
    CompositionLocalProvider(
      LocalRippleThemeConfiguration provides RippleDefaults.InsetFocusRingRippleThemeConfiguration,
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

/** Seed a held [FocusInteraction.Focus] so the resting capture shows the focus ring active. */
@Composable
private fun focusedSource(): MutableInteractionSource {
  val source = remember { MutableInteractionSource() }
  LaunchedEffect(source) { source.emit(FocusInteraction.Focus()) }
  return source
}

/**
 * The keyboard-focus (`focus-visible`) state of the filled button, showing the M3 inset focus ring.
 * The function name **must** stay `FilledButtonFocused` — the generator folds this render onto the
 * `Button/Filled` component's `keyboard-focus` variant in `catalog.spec.json` by matching it.
 */
// Light + dark, matching the CMP catalog's `@CatalogModes` so the folded variant carries both.
// The `@Preview`s are inlined (not a shared multipreview annotation) so discovery reliably resolves
// them on this single-preview module.
@Preview(name = "Light", showBackground = true, group = "modes")
@Preview(
  name = "Dark",
  showBackground = true,
  uiMode = Configuration.UI_MODE_NIGHT_YES,
  group = "modes",
)
@Composable
fun FilledButtonFocused() =
  FocusRingSticker { Button(onClick = {}, interactionSource = focusedSource()) { Text("Focused") } }
