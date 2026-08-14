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

/**
 * The keyboard-focus (`focus-visible`) state of the filled button, showing the M3 inset focus ring.
 * The function name **must** stay `FilledButtonFocused` — the generator folds this render onto the
 * `Button/Filled` component's `keyboard-focus` variant in `catalog.spec.json` by matching it.
 *
 * The focus is **real** (issue #3672). This sticker used to seed a held `FocusInteraction.Focus`
 * onto a `MutableInteractionSource` from a `LaunchedEffect` — a forged visual: nothing was actually
 * focused, no `Unfocus` ever paired the emission, and the capture depended on `Button` happening to
 * read its indication off the interaction source rather than off the focus system. Which is a
 * strange thing for a sticker whose entire subject is the keyboard-focus indicator.
 *
 * `@FocusedPreview` is the repo's mechanism and applies here because this supplement renders on
 * Robolectric: it runs a real `FocusManager.moveFocus` traversal and flips `LocalInputModeManager`
 * to Keyboard mode — which Robolectric needs, since its host environment is permanently Touch and
 * `Modifier.clickable` registers its focusable as `Focusability.SystemDefined` (refused in touch
 * mode). `indices = [0]` is the single `Button` in the sticker; a single-capture `@FocusedPreview`
 * keeps the plain `renders/<id>.png` filename (see `emitStaticCross` in `PreviewDiscovery.kt`), so
 * the by-function-name fold is untouched.
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
@FocusedPreview(indices = [0])
@Composable
fun FilledButtonFocused() = FocusRingSticker { Button(onClick = {}) { Text("Focused") } }
