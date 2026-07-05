package com.example.designcatalogm3

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * The catalog's theme wrapper. Each sticker is a stock [MaterialTheme] — the
 * default light/dark `colorScheme` — so the `compose/theme` token set the
 * renderer extracts is the **real** Material 3 system, not a bespoke palette.
 * A uniform 16dp [padding] frames every sticker so the sheet reads cleanly and
 * the layout (semantics) variant has breathing room around the component.
 */
@Composable
fun CatalogSticker(content: @Composable () -> Unit) {
  val dark = isSystemInDarkTheme()
  MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
    Surface {
      androidx.compose.foundation.layout.Box(Modifier.padding(16.dp)) { content() }
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

/**
 * Frame for **full-screen scaffold templates** — as opposed to the centred
 * component [CatalogSticker]. Just the stock [MaterialTheme] filling the device
 * with the `background` surface; the template supplies its own `Scaffold` and
 * drives the system-bar spacing through real window insets (see
 * [SYSTEM_BAR_INSET]).
 */
@Composable
fun FullScreenM3(content: @Composable () -> Unit) {
  val dark = isSystemInDarkTheme()
  MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
    Surface(Modifier.fillMaxSize()) { content() }
  }
}

/**
 * Height of the renderer's synthetic status / navigation bars (`SystemBarsFrame`
 * draws both at 24dp). The render environment has no real window insets behind
 * that overlay, so a template feeds this height to its `Scaffold`/`TopAppBar`
 * `windowInsets` — reproducing a real edge-to-edge M3 scaffold (the app bar
 * paints under the status bar with its title below the OS clock; content and the
 * FAB clear the gesture pill) rather than an outer padding that would push the
 * whole scaffold down into a blank band.
 */
val SYSTEM_BAR_INSET = 24.dp

/**
 * Full-screen template multipreview: a phone (`id:pixel_8`) with
 * `showSystemUi = true` so the capture carries the synthetic OS status + nav
 * chrome, rendered in both light and dark so the status-bar tint branches show.
 */
@Preview(name = "Light", device = "id:pixel_8", showSystemUi = true, group = "template")
@Preview(
  name = "Dark",
  device = "id:pixel_8",
  showSystemUi = true,
  uiMode = Configuration.UI_MODE_NIGHT_YES,
  group = "template",
)
annotation class CatalogTemplate
