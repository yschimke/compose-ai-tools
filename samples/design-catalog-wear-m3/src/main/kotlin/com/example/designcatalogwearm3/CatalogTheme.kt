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
 * a layout must adapt to — 192 dp (small round), 227 dp (large round), and 244 dp
 * (the largest round). 192/227 reuse the Wear tooling's device ids; 244 is an
 * explicit round `spec:` at the same 2.0× density. Stack on a full-screen
 * component (placed via [FullScreenWear] + `ScreenScaffold`) to capture it black
 * on the device shape at each breakpoint — mirrors how the official Wear samples
 * verify a screen across sizes.
 */
@WearPreviewSmallRound
@WearPreviewLargeRound
@Preview(
  name = "Extra Large Round",
  group = "Devices - Extra Large Round",
  device = "spec:width=244dp,height=244dp,dpi=320,isRound=true",
  showBackground = true,
  backgroundColor = 0xFF000000,
)
annotation class CatalogWearBreakpoints
