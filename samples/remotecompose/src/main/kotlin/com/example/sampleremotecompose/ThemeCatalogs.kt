package com.example.sampleremotecompose

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.PreviewWrapperProvider
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import ee.schimke.composeai.preview.WearThemeCatalog

/**
 * Declared themes for a module rendering Remote Compose through approach 2:
 * `@PreviewWrapper(RemotePreviewWrapper::class)`, where the wrapper installs the capture. A
 * `themeProvider` override must nest outside such a structural wrapper rather than replace it
 * (`isStructuralPreviewWrapper` in `:data-render-core`), or every `RemoteBox` lands on the plain UI
 * applier and fails with `Invalid applier`.
 *
 * A theme doesn't repaint these widgets: `RemotePreviewWrapper` captures in a separate composition,
 * so composition locals don't cross into the recorded document. The theme is live only in the
 * specimen sheet `@WearThemeCatalog` emits per provider. (Re-theming a recorded document uses
 * named-value overrides; see the `remote-m3` catalog's `RemoteThemeCatalogs.kt`.)
 *
 * This doesn't reproduce the failure on its own: offline lanes never apply `themeProvider` to
 * ordinary previews, and a bundle-backed `serve` replays recorded `ir/<id>.rc` without resolving a
 * wrapper. The automatic guard is `themeProviderOverrideNestsAroundStructuralWrapper` in the
 * daemons; a lane exercising this module should assert on a recomposed render, not an IR replay.
 *
 * Wear M3 because only `androidx.wear.compose:compose-material3` is on the compile classpath. Each
 * provider declares its own `Wrap`, since the renderer resolves it reflectively on the concrete
 * class.
 */
@WearThemeCatalog(name = "Remote Default", group = "Remote")
class RemoteSampleDefaultThemeCatalog : PreviewWrapperProvider {
  @Composable override fun Wrap(content: @Composable () -> Unit) = MaterialTheme { content() }
}

/** Warm coral primary — the palette the specimen sheet is read against the default with. */
@WearThemeCatalog(name = "Remote Coral", group = "Remote")
class RemoteSampleCoralThemeCatalog : PreviewWrapperProvider {
  @Composable
  override fun Wrap(content: @Composable () -> Unit) =
    MaterialTheme(
      colorScheme = ColorScheme(primary = Color(0xFFFF6F61), secondary = Color(0xFFFFB4A9))
    ) {
      content()
    }
}
