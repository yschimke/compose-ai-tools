package com.example.designcatalogwearm3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.PreviewWrapperProvider
import androidx.wear.compose.material3.MaterialTheme
import ee.schimke.composeai.preview.WearThemeCatalog

/**
 * `@WearThemeCatalog` providers — the Wear analogue of `samples/android`'s `ThemeCatalogs.kt`, and
 * the render-side proof that the Wear specimen reads the Wear theme.
 *
 * Each wraps the stock Wear [MaterialTheme] with one of the catalog's declared palettes (the same
 * `wearColorScheme` mapping the `knob.theme.colors` override uses), so the rendered sheets differ
 * in their primary / secondary families. Rendered by the
 * `WEAR_THEME_CATALOG` strategy, which reads `androidx.wear.compose.material3.MaterialTheme`
 * reflectively; annotate these `@ThemeCatalog` instead and all three collapse to the identical
 * baseline mobile M3 palette, which is the bug this kind exists to fix.
 *
 * They also populate the preview server's **Theme** select for this module. The local marker keeps
 * [WearCatalogTheme] inside each sticker from shadowing the selected outer provider.
 */
@Composable
private fun WearThemeOverride(name: String, content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = wearColorScheme(name, MaterialTheme.colorScheme)) {
    CompositionLocalProvider(LocalWearCatalogThemeOverride provides true, content = content)
  }
}

@WearThemeCatalog(name = "M3", group = "Wear")
class WearM3ThemeCatalog : PreviewWrapperProvider {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) =
    WearThemeOverride("M3", content)
}

@WearThemeCatalog(name = "Coral", group = "Wear")
class WearCoralThemeCatalog : PreviewWrapperProvider {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) =
    WearThemeOverride("Coral", content)
}

@WearThemeCatalog(name = "Teal", group = "Wear")
class WearTealThemeCatalog : PreviewWrapperProvider {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) =
    WearThemeOverride("Teal", content)
}

/** Confetti Wear's dark KotlinConf identity, using its JetBrains purple seed palette. */
@WearThemeCatalog(name = "KotlinConf", group = "Confetti Wear")
class WearKotlinConfThemeCatalog : PreviewWrapperProvider {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) =
    WearThemeOverride("KotlinConf", content)
}
