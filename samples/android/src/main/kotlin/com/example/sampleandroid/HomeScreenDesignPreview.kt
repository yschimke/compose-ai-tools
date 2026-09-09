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
 * The UI-builder design in [`samples/android/design/home-screen.uibuilder.json`] as Compose, and
 * the design half of the device-capture parity lane.
 *
 * ## What this file is
 *
 * `DeviceCaptureParityTest` renders this preview and the real `MainActivity`
 * (`renders/activity__MainActivity.png`, the `kind=ACTIVITY` capture app tours already produce) and
 * reports how far apart they are. The app is the reference; this is the design authored to match
 * it. See [`docs/design/DEVICE_CAPTURE.md`](../../../../../../../docs/design/DEVICE_CAPTURE.md).
 *
 * ## Why it is checked in rather than generated at build time
 *
 * The builder's `CapabilityComposeCodeExporter` lives in `compose-preview-server`, which is layer 2
 * — this repository is layer 1 and may not depend upward
 * ([`REPOSITORY_LAYERS.md`](../../../../../../../docs/design/REPOSITORY_LAYERS.md)). So the design
 * document is the source of truth, this is its committed projection, and the parity lane compares
 * *pixels* rather than trusting the transcription.
 *
 * **It is therefore transcribed, not generated**, and that is the one seam in this lane a reviewer
 * has to check by eye. Each composable below states which design node it came from. The mapping
 * follows the exporter's own rules, which are worth knowing when checking it:
 *
 * - `layout/column.verticalSpacingDp` → `verticalArrangement = Arrangement.spacedBy(n.dp)`
 * - a `padding` modifier → `.padding(start =, top =, end =, bottom =)`, always four named edges
 * - `m3/card.variant = filled` → `Card`; `elevated`/`outlined` would be the other two symbols
 * - `m3/button.style = filled` → `Button`
 * - `m3/text.style` → `MaterialTheme.typography.<style>`
 * - `m3/text.color` → `MaterialTheme.colorScheme.<token>`
 *
 * A follow-up should replace the transcription with the exporter's real output, run in
 * compose-preview-server and committed here — see the issue linked from the parity test.
 */
// Rendered under exactly the conditions the ACTIVITY capture uses, so the comparison is
// apples-to-apples: `AppTourDiscovery.buildActivityPreviews` builds its preview from
// `DeviceDimensions.DEFAULT` (400x800dp at density 2.625) with `showSystemUi = true`. Any of the
// three differing would make the score measure the render setup rather than the design.
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
