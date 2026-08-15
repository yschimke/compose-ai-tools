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
 * **[loading] is a required parameter, and nothing in this signature knows compose-ai-tools
 * exists** (issue #3675). It used to default to `placeholderActive(default = false)`, which made a
 * reusable application component source its production loading state from *preview* infrastructure
 * — the one thing a consumer must not copy out of a sample, since it inverts the dependency
 * (product code depending on the tool that photographs it) and silently changes on-device behaviour
 * if the override runtime is ever on the release classpath. State is hoisted; the caller decides.
 *
 * The `placeholderActive` seam is still exercised, one level up, by [PlaceholderCardOverrideDriven]
 * — see its doc for why that separation is the point rather than a workaround.
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
 * **Preview-only wrapper — do not copy this into an application.** The one place in this sample
 * where compose-ai-tools' override runtime is allowed to appear (issue #3675).
 *
 * It reads [placeholderActive], the opt-in seam the daemon's
 * `renderNow.overrides.placeholderActive` (`?placeholderActive=true` on `serve`) drives, and
 * forwards the answer into [PlaceholderCard]'s hoisted `loading` parameter. That keeps
 * issue #2646's acceptance criterion — *live `placeholderActive` overrides still work* — genuinely
 * exercised end to end: a live session can flip **this** preview between states on demand, without
 * the reusable component underneath having any idea a renderer is involved.
 *
 * The split is the lesson. A preview-only shim that reaches into tooling is fine, because it never
 * ships; the component it wraps is what a consumer copies, so that one stays clean. Unforced (any
 * render with no override set) [placeholderActive] returns its `default = false`, so this renders
 * identically to [PlaceholderCardLoaded] — the static PNG is the *loaded* state, and the loading
 * variant of this preview only appears when a caller asks for it.
 */
@Preview(device = "id:wearos_small_round", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PlaceholderCardOverrideDriven() = MaterialTheme {
  PlaceholderCard(loading = placeholderActive(default = false))
}
