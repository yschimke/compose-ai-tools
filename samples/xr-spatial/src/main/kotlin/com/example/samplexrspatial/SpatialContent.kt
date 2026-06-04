package com.example.samplexrspatial

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

// The composables below give the showcase spatial previews (SpatialShowcasePreviews.kt) visually
// distinct panel bodies, so the baked composite stills read as a real multi-panel app rather than
// the same card repeated. All ordinary Material3 — no XR dependency.

/** A grid of album tiles — the kind of 2D content you'd host in a "library" panel. */
@Composable
fun LibraryGrid(modifier: Modifier = Modifier) {
  val albums =
    listOf(
      "Aurora" to Color(0xFF6750A4),
      "Drift" to Color(0xFF1E88E5),
      "Lumen" to Color(0xFF00897B),
      "Pulse" to Color(0xFFD81B60),
      "Halcyon" to Color(0xFFF4511E),
      "Vesper" to Color(0xFF5E35B1),
    )
  Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("Library", style = MaterialTheme.typography.titleLarge)
      LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        items(albums) { (title, color) ->
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
              Modifier.fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.55f))))
            )
            Text(
              title,
              style = MaterialTheme.typography.labelLarge,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }
  }
}

/** A scrollable track list — the kind of 2D content you'd host in a "queue" panel. */
@Composable
fun QueueList(modifier: Modifier = Modifier) {
  val tracks =
    listOf(
      "Slow Light" to "Aurora",
      "Tidal" to "Drift",
      "Glass Fields" to "Lumen",
      "Resonance" to "Pulse",
      "Soft Static" to "Halcyon",
      "Nightfall" to "Vesper",
      "Undertow" to "Drift",
      "Embers" to "Pulse",
    )
  Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text("Up Next", style = MaterialTheme.typography.titleLarge)
      LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(tracks) { (title, artist) ->
          Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Box(
              Modifier.size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
            )
            Column(Modifier.weight(1f)) {
              Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
  }
}

/** A large album-art card with a gradient cover — the "detail"/foreground panel body. */
@Composable
fun AlbumArtCard(modifier: Modifier = Modifier) {
  Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
    Column(
      Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Box(
        Modifier.fillMaxWidth()
          .aspectRatio(1f)
          .clip(RoundedCornerShape(16.dp))
          .background(
            Brush.linearGradient(listOf(Color(0xFF6750A4), Color(0xFF1E88E5), Color(0xFF00897B)))
          )
      )
      Text("Aurora", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
      Text(
        "Spatial Sessions",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** A search bar — the content you'd float in a top `Orbiter`. */
@Composable
fun SearchBar(modifier: Modifier = Modifier) {
  Card(modifier = modifier) {
    Row(
      Modifier.padding(horizontal = 20.dp, vertical = 14.dp).fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        Modifier.size(20.dp)
          .clip(RoundedCornerShape(50))
          .background(MaterialTheme.colorScheme.primary)
      )
      Text(
        "Search your library",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** A small equalizer / settings card with sliders — a distinct "controls" panel body. */
@Composable
fun EqualizerCard(modifier: Modifier = Modifier) {
  Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Equalizer", style = MaterialTheme.typography.titleLarge)
      EqBand("Bass", 0.75f)
      EqBand("Mid", 0.45f)
      EqBand("Treble", 0.6f)
    }
  }
}

@Composable
private fun EqBand(label: String, value: Float) {
  Column {
    Text(label, style = MaterialTheme.typography.labelMedium)
    Slider(value = value, onValueChange = {})
  }
}

/**
 * A label/title card on a very dark surface — a precise reproduction of the panel body in the
 * Android XR "spatial panels" reference (developer.android.com/develop/xr). The panel fills its
 * `SpatialPanel` edge-to-edge with no corner rounding of its own: the `xr-composite` compositor
 * rounds panel corners when it bakes, so a flat dark fill here becomes the reference's rounded dark
 * card. Colours are sampled straight from the reference shot — surface `#0F0C13`, near-white title
 * `#EFEAF1`, muted-grey eyebrow.
 */
@Composable
fun ReferencePanel(title: String, eyebrow: String = "Panel", modifier: Modifier = Modifier) {
  Surface(modifier = modifier.fillMaxSize(), color = Color(0xFF0F0C13)) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(eyebrow, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB6B1BD))
        Text(
          title,
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
          color = Color(0xFFEFEAF1),
        )
      }
    }
  }
}
