package com.example.designcatalogm3

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
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
 * component [CatalogSticker]. The stock [MaterialTheme] fills the device with the
 * `background` surface and the template supplies its own `Scaffold`.
 *
 * Templates render on a phone device with `showSystemUi = true`, so the renderer
 * paints its synthetic Android chrome (a 24dp status bar with the OS clock, a
 * 24dp gesture-pill nav bar — see `SystemBarsFrame` in renderer-android) as a
 * translucent overlay on top of the capture. That overlay *is* the "status bar"
 * the template demonstrates; the matching [SYSTEM_BAR_INSET] padding keeps the
 * template's own app chrome (TopAppBar, bottom bar) clear of the OS clock and
 * gesture pill rather than colliding with them.
 */
@Composable
fun FullScreenM3(content: @Composable () -> Unit) {
  val dark = isSystemInDarkTheme()
  MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
    Surface(Modifier.fillMaxSize()) {
      Box(Modifier.fillMaxSize().padding(top = SYSTEM_BAR_INSET, bottom = SYSTEM_BAR_INSET)) {
        content()
      }
    }
  }
}

/**
 * Height of the renderer's synthetic status / navigation bars (`SystemBarsFrame`
 * draws both at 24dp). Full-screen templates reserve this at the top and bottom
 * so their own chrome doesn't sit under the OS overlay.
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
