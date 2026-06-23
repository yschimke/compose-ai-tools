package com.example.designcatalogwearm3

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
