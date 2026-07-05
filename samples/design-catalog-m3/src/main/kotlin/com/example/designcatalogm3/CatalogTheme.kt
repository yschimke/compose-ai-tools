package com.example.designcatalogm3

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.LocalRippleThemeConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.RippleDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * The catalog's theme wrapper. Each sticker is a stock [MaterialTheme] — the
 * default light/dark `colorScheme` — so the `compose/theme` token set the
 * renderer extracts is the **real** Material 3 system, not a bespoke palette.
 * A uniform 16dp [padding] frames every sticker so the sheet reads cleanly and
 * the layout (semantics) variant has breathing room around the component.
 *
 * Every component opts into the Material 3 **inset focus ring** — via
 * [RippleDefaults.InsetFocusRingRippleThemeConfiguration] provided over
 * [LocalRippleThemeConfiguration] (material3 1.5.0-alpha+) — rather than the
 * legacy opacity state layer, so the keyboard-focus indicator the design system
 * now ships is what the sheet documents. This ring is the **focus-visible /
 * keyboard-focus** indicator (focus arriving via keyboard / D-pad / rotary), not
 * the pointer/hover state layer. The `keyboard-focus` state stickers seed a
 * `FocusInteraction.Focus`, so they capture that ring active; other states are
 * unaffected.
 *
 * The ring's stroke colours are overridden (via [LocalRippleConfiguration]'s
 * [RippleConfiguration.Focus.InsetRing]) to `primary` (outer) over `surface`
 * (inner gap) instead of the stock `secondary`/`onSecondary`: on the baseline
 * palette the default outer stroke is a muted grey and the default inner stroke
 * is near-white on a near-white surface, so the indicator all but disappears at
 * sticker size. A focus ring a designer can't see fails the catalog's purpose, so
 * the sheet trades that bit of default-colour fidelity for a legible indicator.
 * Only the `focus` override is set, so pressed/hover ripples are untouched.
 */
@Composable
fun CatalogSticker(content: @Composable () -> Unit) {
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
      Surface {
        androidx.compose.foundation.layout.Box(Modifier.padding(16.dp)) { content() }
      }
    }
  }
}

/**
 * The catalog's primary-mode multipreview: every component is rendered in both
 * light and dark, the two modes M3 ships. Stacking this annotation on a
 * composable yields the `· Light` / `· Dark` captures the sticker sheet pairs.
 * Further modes (states, breakpoints) are added per-component with extra
 * `@Preview`s where they matter.
 */
@Preview(name = "Light", showBackground = true, group = "modes")
@Preview(
  name = "Dark",
  showBackground = true,
  uiMode = Configuration.UI_MODE_NIGHT_YES,
  group = "modes",
)
annotation class CatalogModes
