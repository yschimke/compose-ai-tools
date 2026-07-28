package com.example.samplewear

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import ee.schimke.composeai.overrides.LocalPlaceholderActive
import ee.schimke.composeai.overrides.placeholderActive

/**
 * Committed regression fixture for the **state-aware placeholder export** (issue #2646), and the
 * coverage gap that let two placeholder defects reach the live preview un-noticed (#2644 text
 * doubled into a raster, #2645 container corner exported as a full pill).
 *
 * A Wear M3 `TitleCard` whose text is drawn through `Modifier.placeholder` and whose container
 * carries `Modifier.placeholderShimmer` — exactly the Confetti `SessionCard` shape both bugs came
 * from. Rendered in **both** states so the CI visual-diff bot diffs each one on every PR:
 * - [PlaceholderCardLoaded] — the ideal state. The card must export with its real, editable text
 *   (drawn once, not doubled by a frame crop) and its own modest `TitleCard` corner.
 * - [PlaceholderCardLoading] — the loading state, where the placeholder blocks paint over the
 *   content and the export emits *them* as vector layers.
 *
 * The state comes from [placeholderActive], the opt-in seam the daemon's
 * `renderNow.overrides.placeholderActive` (`?placeholderActive=true` on `serve`) drives — so the
 * same composable also renders in either state on demand from a live session, not only through the
 * two static previews. [PlaceholderCardLoading] pins the same local directly, which is what makes
 * the loading render reachable from the plain `@Preview` pipeline (static rendering doesn't run the
 * daemon's extension chain).
 */
@Composable
fun PlaceholderCard(loading: Boolean = placeholderActive(default = false)) {
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
fun PlaceholderCardLoading() =
  MaterialTheme {
    // Pinned through the same composition local the `placeholderActive` render override provides,
    // so the static preview and the daemon-driven render exercise one code path.
    CompositionLocalProvider(LocalPlaceholderActive provides true) { PlaceholderCard() }
  }
