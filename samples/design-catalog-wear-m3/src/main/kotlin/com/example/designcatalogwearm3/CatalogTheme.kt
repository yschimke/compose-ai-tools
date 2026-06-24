package com.example.designcatalogwearm3

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.ui.tooling.preview.WearPreviewLargeRound
import androidx.wear.compose.ui.tooling.preview.WearPreviewSmallRound

/**
 * The catalog's sticker frame. Each component is centred on the stock Wear
 * [MaterialTheme] background, so the `compose/theme` token set the renderer
 * extracts is the **real** Wear Material 3 system (which is dark-first).
 */
@Composable
fun WearSticker(content: @Composable () -> Unit) {
  MaterialTheme {
    Box(
      Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(8.dp),
      contentAlignment = Alignment.Center,
    ) {
      content()
    }
  }
}

/**
 * The catalog's primary-mode multipreview. Wear is dark-first, so the primary
 * modes are the two round **size breakpoints** — small round and large round —
 * the Wear preview tooling ships, rather than a light/dark pair. Stacking this on
 * a component yields one capture per round size.
 */
@WearPreviewSmallRound
@WearPreviewLargeRound
annotation class CatalogWearModes

/**
 * Frame for **full-screen** Wear components (scaffolds, lists, the EdgeButton) —
 * as opposed to the centred component [WearSticker]. The Wear dark [MaterialTheme]
 * fills the round display black and [AppScaffold] supplies the screen structure;
 * `timeText = {}` drops the status clock so the capture is deterministic (a live
 * clock would churn the weekly design-artifacts bundle). The content supplies its
 * own `ScreenScaffold`.
 */
@Composable
fun FullScreenWear(content: @Composable () -> Unit) {
  MaterialTheme {
    AppScaffold(timeText = {}) { content() }
  }
}

/**
 * Full-screen **size-breakpoint** multipreview: the three round Wear screen sizes
 * a layout must adapt to — 192 dp (small round), 227 dp (large round), and 240 dp
 * (extra-large round) — each black on the device shape. Stack on a full-screen
 * component (placed via [FullScreenWear] + `ScreenScaffold`) to capture it at each
 * breakpoint, mirroring how the official Wear samples verify a screen across sizes.
 *
 * All three are **direct** `@Preview`s rather than the nested `@WearPreviewSmallRound`
 * / `@WearPreviewLargeRound` aliases: `PreviewDiscovery.resolveMultiPreview` returns
 * an annotation class's direct previews without recursing into nested multipreviews,
 * so a mix would silently drop the nested 192/227 and render only the last. All
 * three use the Wear tooling **device ids** (192/227/240, round, 2.0×) — the render
 * pipeline only exercises named-id devices, not custom `spec:` strings.
 */
@Preview(
  name = "Small Round",
  group = "Devices - Small Round",
  device = "id:wearos_small_round",
  showBackground = true,
  backgroundColor = 0xFF000000,
)
@Preview(
  name = "Large Round",
  group = "Devices - Large Round",
  device = "id:wearos_large_round",
  showBackground = true,
  backgroundColor = 0xFF000000,
)
@Preview(
  name = "Extra Large Round",
  group = "Devices - Extra Large Round",
  device = "id:wearos_xl_round",
  showBackground = true,
  backgroundColor = 0xFF000000,
)
annotation class CatalogWearBreakpoints
