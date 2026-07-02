package com.example.sampleandroid

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.PreviewWrapperProvider
import ee.schimke.composeai.preview.ThemeCatalog

/**
 * `@ThemeCatalog` providers — the app's alternative themes, the analogue of Showkase's theme
 * browser. No `@Preview` is written: the compose-preview plugin discovers each annotated
 * `PreviewWrapperProvider` and renders a specimen sheet by composing its `Wrap` around a canned M3
 * role + type-scale grid, so each sheet shows that theme's resolved `colorScheme` / `typography`.
 * The two here are the N-ary generalization of a single `uiMode` light/dark toggle.
 */
@ThemeCatalog(name = "Brand Light", group = "Brand")
class BrandLightThemeCatalog : PreviewWrapperProvider {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) =
    MaterialTheme(
      colorScheme =
        lightColorScheme(
          primary = Color(0xFFFF6F61),
          secondary = Color(0xFF008080),
          tertiary = Color(0xFFB8860B),
        )
    ) {
      content()
    }
}

@ThemeCatalog(name = "Brand Dark", group = "Brand")
class BrandDarkThemeCatalog : PreviewWrapperProvider {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) =
    MaterialTheme(
      colorScheme =
        darkColorScheme(
          primary = Color(0xFFFF8A80),
          secondary = Color(0xFF4DD0E1),
          tertiary = Color(0xFFFFE082),
        )
    ) {
      content()
    }
}
