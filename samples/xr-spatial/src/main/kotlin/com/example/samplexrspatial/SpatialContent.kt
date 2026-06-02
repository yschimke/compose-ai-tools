package com.example.samplexrspatial

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Reusable 2D content for the XR spatial sample.
 *
 * The Jetpack XR docs recommend authoring your app UI as ordinary 2D Compose and then *placing*
 * it spatially — a `SpatialPanel` hosts a 2D panel, an `Orbiter` floats a 2D control strip beside
 * it. The composables here are that 2D content. They carry no XR dependency at all, so they're the
 * natural unit to `@Preview`: what renders offline is identical to what the panel shows on-device.
 *
 * [SpatialPreviews] reuses these inside `Orbiter` / `SpatialElevation` to show how the spatial
 * affordances degrade to a 2D layout when spatialization is unavailable (Home Space / non-XR /
 * the offline renderer).
 */

/** The "main panel" body — the kind of 2D surface you would host inside a `SpatialPanel`. */
@Composable
fun NowPlayingPanel(modifier: Modifier = Modifier) {
  Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainer) {
    Column(
      modifier = Modifier.padding(24.dp).fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("Now Playing", style = MaterialTheme.typography.labelLarge)
      Text("Spatial Sessions", style = MaterialTheme.typography.headlineMedium)
      Text(
        "Ambient electronica for a focused workspace.",
        style = MaterialTheme.typography.bodyMedium,
      )
      Spacer(Modifier.height(8.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = {}) { Text("Play") }
        Button(onClick = {}) { Text("Queue") }
      }
    }
  }
}

/** A horizontal control strip — the content you would float in a top/bottom `Orbiter`. */
@Composable
fun TransportControls(modifier: Modifier = Modifier) {
  Card(modifier = modifier) {
    Row(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FilledTonalButton(onClick = {}) { Text("Prev") }
      FilledTonalButton(onClick = {}) { Text("Play") }
      FilledTonalButton(onClick = {}) { Text("Next") }
    }
  }
}
