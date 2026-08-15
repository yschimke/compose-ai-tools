package com.example.samplecmp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.preview.InteractionPreview

/**
 * An inline options menu that grows in place when it opens — the `@InteractionPreview` fixture for
 * a capture whose content **changes size during the recording**.
 *
 * Every other motion capture in this repo measures the same size on frame 0 and on the last frame,
 * so the renderer's single-pass path covers all of them and its re-record path — the one that
 * notices the composable outgrew the frame it committed to and records again at the larger size —
 * had only a unit fixture behind it. This is that case on a real component: closed it is one row,
 * open it is four, and the expansion arrives ~700ms into a capture that had already chosen its
 * frame size from the resting measurement.
 *
 * A capture that regressed to the single-pass behaviour does not fail loudly. It publishes a
 * recording of a menu opening into a wall, its revealed items sliced off at the bottom edge — which
 * is why this fixture is worth keeping even though nothing here is novel as a component.
 *
 * The menu is deliberately **inline** rather than a `DropdownMenu`: a real dropdown renders into a
 * separate popup window, which the captured root doesn't contain at all, so it would exercise
 * nothing. Expanding in place is also the honest shape for a sticker — the component's own bounds
 * are what changes.
 */
@Preview(name = "Interaction — Expandable Menu")
@InteractionPreview(
  targets = [0],
  leadInMs = 400,
  gapMs = 1200,
  caption =
    "Open the menu. The card grows into its expanded height as the items reveal — the capture " +
      "is re-recorded at that grown size so the reveal isn't clipped at the resting frame edge.",
)
@Composable
fun ExpandableMenuInteractionPreview() {
  var open by remember { mutableStateOf(false) }
  Card(modifier = Modifier.width(200.dp)) {
    Column(modifier = Modifier.padding(4.dp)) {
      TextButton(onClick = { open = !open }, modifier = Modifier.fillMaxWidth()) {
        Text(if (open) "Options ▲" else "Options ▼")
      }
      AnimatedVisibility(visible = open) {
        Column {
          for (label in listOf("Rename", "Duplicate", "Delete")) {
            Text(
              text = label,
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            )
          }
        }
      }
    }
  }
}
