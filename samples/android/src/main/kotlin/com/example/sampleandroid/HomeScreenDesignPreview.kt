package com.example.sampleandroid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * The UI-builder design in [`samples/android/design/home-screen.uibuilder.json`] as Compose: the
 * design half of the device-capture parity lane (`DeviceCaptureParityTest` compares it with the real
 * `MainActivity` capture). See
 * [`docs/design/DEVICE_CAPTURE.md`](../../../../../../../docs/design/DEVICE_CAPTURE.md).
 *
 * Hand-transcribed rather than generated: the builder's exporter lives in compose-preview-server
 * (layer 2), which this repository may not depend on
 * ([`REPOSITORY_LAYERS.md`](../../../../../../../docs/design/REPOSITORY_LAYERS.md)). Each composable
 * names its design node, following the exporter's rules:
 *
 * - `layout/column.verticalSpacingDp` → `verticalArrangement = Arrangement.spacedBy(n.dp)`
 * - a `padding` modifier → `.padding(start =, top =, end =, bottom =)`, always four named edges
 * - `m3/card.variant = filled` → `Card`; `elevated`/`outlined` would be the other two symbols
 * - `m3/button.style = filled` → `Button`
 * - `m3/text.style` → `MaterialTheme.typography.<style>`
 * - `m3/text.color` → `MaterialTheme.colorScheme.<token>`
 *
 * TODO: replace the transcription with the exporter's real output, committed here.
 */
// Rendered under the same conditions as the ACTIVITY capture (`DeviceDimensions.DEFAULT`, 400x800dp
// at 2.625, `showSystemUi = true`), so the score measures the design, not the setup.
@Preview(name = "Design", showSystemUi = true, widthDp = 400, heightDp = 800)
@Composable
fun HomeScreenDesignPreview() {
  MaterialTheme {
    // node `root` — m3/surface, modifier fillMaxSize
    Surface(modifier = Modifier.fillMaxSize()) {
      // node `screen-column` — layout/column, verticalSpacingDp 16, fillMaxSize + padding 24
      Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier =
          Modifier.fillMaxSize().padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 24.dp),
      ) {
        // node `title` — m3/text, style headlineMedium
        Text(text = "Sample Android", style = MaterialTheme.typography.headlineMedium)
        // node `blurb` — m3/text, style bodyMedium
        Text(
          text = "A tiny two-screen app used to exercise app-level previews and tours.",
          style = MaterialTheme.typography.bodyMedium,
        )
        // node `card` — m3/card, variant filled, modifier fillMaxWidth
        Card(modifier = Modifier.fillMaxWidth()) {
          // node `card-column` — layout/column, verticalSpacingDp 8, fillMaxWidth + padding 16
          Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
              Modifier.fillMaxWidth()
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp),
          ) {
            // node `card-heading` — m3/text, style titleMedium
            Text(text = "Continue listening", style = MaterialTheme.typography.titleMedium)
            // node `card-track` — m3/text, style bodyMedium, color onSurfaceVariant
            Text(
              text = "Midnight City — M83",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // node `open-now-playing` — m3/button, style filled; its content slot holds
            // `open-now-playing-label`, an m3/text. The design has no onClick: a captured screen's
            // behaviour is not part of what a still frame can be compared on.
            Button(onClick = {}) { Text("Open Now Playing") }
          }
        }
      }
    }
  }
}
