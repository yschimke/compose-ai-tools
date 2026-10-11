package com.example.samplewear

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.placeholder
import androidx.wear.compose.material3.placeholderShimmer
import androidx.wear.compose.material3.rememberPlaceholderState
import ee.schimke.composeai.overrides.placeholderActive

/**
 * Regression fixture for the state-aware placeholder export: a Wear M3 `TitleCard` with text drawn
 * through `Modifier.placeholder` and a `Modifier.placeholderShimmer` container (Confetti's
 * `SessionCard` shape), rendered in both states:
 * - [PlaceholderCardLoaded] — real, editable text drawn once, with `TitleCard`'s own corner.
 * - [PlaceholderCardLoading] — placeholder blocks exported as vector layers.
 *
 * [loading] is a required, hoisted parameter: an application component must not read its loading
 * state from preview tooling. The `placeholderActive` seam lives one level up, in
 * [PlaceholderCardOverrideDriven].
 */
@Composable
fun PlaceholderCard(loading: Boolean) {
  val placeholderState = rememberPlaceholderState(loading)
  Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
    TitleCard(
      onClick = {},
      title = { Text("Morning run", modifier = Modifier.placeholder(placeholderState)) },
      subtitle = { Text("5.2 km · 27 min", modifier = Modifier.placeholder(placeholderState)) },
      modifier = Modifier.fillMaxWidth().placeholderShimmer(placeholderState),
    )
  }
}

/** The content-loaded (ideal) state — the placeholder draws through to the real text. */
@Preview(device = "id:wearos_small_round", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PlaceholderCardLoaded() = MaterialTheme { PlaceholderCard(loading = false) }

/** The loading state — placeholder blocks cover the content until it arrives. */
@Preview(device = "id:wearos_small_round", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PlaceholderCardLoading() = MaterialTheme { PlaceholderCard(loading = true) }

/**
 * Preview-only wrapper — don't copy this into an application. Reads [placeholderActive] (driven by
 * `renderNow.overrides.placeholderActive`, `?placeholderActive=true` on `serve`) and forwards it
 * into [PlaceholderCard]'s `loading`, so a live session can flip states while the component stays
 * clean. Unforced, it renders the loaded state, identical to [PlaceholderCardLoaded].
 */
@Preview(device = "id:wearos_small_round", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PlaceholderCardOverrideDriven() = MaterialTheme {
  PlaceholderCard(loading = placeholderActive(default = false))
}
