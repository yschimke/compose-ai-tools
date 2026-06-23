package com.example.designcatalogwearm3

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CheckboxButton
import androidx.wear.compose.material3.ChildButton
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard

// ---------------------------------------------------------------------------
// Buttons — the Wear M3 emphasis levels plus the screen-hugging EdgeButton.
// ---------------------------------------------------------------------------

@CatalogWearModes
@Composable
fun FilledButton() = WearSticker { Button(onClick = {}) { Text("Filled") } }

@CatalogWearModes
@Composable
fun FilledTonalButtonSticker() =
  WearSticker { FilledTonalButton(onClick = {}) { Text("Tonal") } }

@CatalogWearModes
@Composable
fun OutlinedButtonSticker() =
  WearSticker { OutlinedButton(onClick = {}) { Text("Outlined") } }

@CatalogWearModes
@Composable
fun ChildButtonSticker() = WearSticker { ChildButton(onClick = {}) { Text("Child") } }

@CatalogWearModes
@Composable
fun EdgeButtonSticker() =
  WearSticker { EdgeButton(onClick = {}, buttonSize = EdgeButtonSize.Large) { Text("Edge") } }

// ---------------------------------------------------------------------------
// Selection controls.
// ---------------------------------------------------------------------------

@CatalogWearModes
@Composable
fun SwitchButtonOn() =
  WearSticker {
    SwitchButton(checked = true, onCheckedChange = {}, label = { Text("Wifi") })
  }

@CatalogWearModes
@Composable
fun CheckboxButtonChecked() =
  WearSticker {
    CheckboxButton(checked = true, onCheckedChange = {}, label = { Text("Sync") })
  }

// ---------------------------------------------------------------------------
// Containment + headers.
// ---------------------------------------------------------------------------

@CatalogWearModes
@Composable
fun CardSticker() = WearSticker { Card(onClick = {}) { Text("Card") } }

@CatalogWearModes
@Composable
fun TitleCardSticker() =
  WearSticker {
    TitleCard(onClick = {}, title = { Text("Morning run") }) { Text("5.2 km · 28 min") }
  }

@CatalogWearModes
@Composable
fun ListHeaderSticker() = WearSticker { ListHeader { Text("Today") } }

// ---------------------------------------------------------------------------
// Communication.
// ---------------------------------------------------------------------------

@CatalogWearModes
@Composable
fun CircularProgressSticker() =
  WearSticker { CircularProgressIndicator(modifier = Modifier.size(48.dp)) }

// ---------------------------------------------------------------------------
// Text options — exercises the maxLines / overflow product on a round screen.
// ---------------------------------------------------------------------------

@CatalogWearModes
@Composable
fun TextMaxLinesTruncated() =
  WearSticker {
    Text(
      "This Wear body text is long enough to overflow two lines and truncate.",
      modifier = Modifier.width(140.dp),
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
  }
