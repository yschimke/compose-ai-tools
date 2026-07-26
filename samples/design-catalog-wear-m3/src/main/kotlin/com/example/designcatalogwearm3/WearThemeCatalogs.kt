package com.example.designcatalogwearm3

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewWrapperProvider
import androidx.wear.compose.material3.MaterialTheme
import ee.schimke.composeai.preview.WearThemeCatalog

/**
 * `@WearThemeCatalog` providers — the Wear analogue of `samples/android`'s `ThemeCatalogs.kt`, and
 * the render-side proof that the Wear specimen reads the Wear theme.
 *
 * Each wraps the stock Wear [MaterialTheme] with one of the catalog's declared palettes (the same
 * `wearColorScheme` mapping the `knob.theme.colors` override uses), so the three rendered sheets —
 * `wearthemecatalog__M3`, `__Coral`, `__Teal` — differ in `primary` / `secondary`. Rendered by the
 * `WEAR_THEME_CATALOG` strategy, which reads `androidx.wear.compose.material3.MaterialTheme`
 * reflectively; annotate these `@ThemeCatalog` instead and all three collapse to the identical
 * baseline mobile M3 palette, which is the bug this kind exists to fix.
 *
 * They also populate the preview server's **Theme** select for this module, so any Wear sticker can
 * be re-rendered live under any of the three.
 */
@WearThemeCatalog(name = "M3", group = "Wear")
class WearM3ThemeCatalog : PreviewWrapperProvider {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = wearColorScheme("M3", MaterialTheme.colorScheme)) { content() }
}

@WearThemeCatalog(name = "Coral", group = "Wear")
class WearCoralThemeCatalog : PreviewWrapperProvider {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = wearColorScheme("Coral", MaterialTheme.colorScheme)) { content() }
}

@WearThemeCatalog(name = "Teal", group = "Wear")
class WearTealThemeCatalog : PreviewWrapperProvider {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = wearColorScheme("Teal", MaterialTheme.colorScheme)) { content() }
}
