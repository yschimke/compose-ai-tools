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
 * An inline options menu that grows from one row to four ~700ms into the capture: the fixture for
 * the renderer's re-record path when content outgrows the frame chosen from the resting
 * measurement. A regression doesn't fail loudly; it clips the revealed items at the bottom edge.
 *
 * Inline rather than `DropdownMenu`, whose popup window isn't part of the captured root.
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
