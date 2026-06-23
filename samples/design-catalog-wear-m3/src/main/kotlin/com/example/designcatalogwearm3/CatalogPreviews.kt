package com.example.designcatalogwearm3

import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CheckboxButton
import androidx.wear.compose.material3.ChildButton
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
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

// EdgeButton hugs the bottom edge of the round screen via the
// ScreenScaffold(edgeButton = …) slot — its curved shape *is* that placement.
// ScreenScaffold reveals the edge button from its scroll state, so the content
// must be a real `TransformingLazyColumn` bound to the same state (a static Box
// leaves the scaffold with no list to anchor, and the button never draws — the
// shape this sticker exists to document). Mirrors the official Wear M3
// `ScreenScaffold` + `TransformingLazyColumn` sample (see samples/wear).
@CatalogWearModes
@Composable
fun EdgeButtonSticker() =
  MaterialTheme {
    // No TimeText: it renders the live wall clock, which would make the sticker
    // non-deterministic (and churn the weekly design-artifacts bundle). The edge
    // slot is what this sticker documents.
    AppScaffold(timeText = {}) {
      val listState = rememberTransformingLazyColumnState()
      ScreenScaffold(
        scrollState = listState,
        edgeButton = {
          EdgeButton(onClick = {}, buttonSize = EdgeButtonSize.Large) { Text("Start") }
        },
      ) { contentPadding ->
        TransformingLazyColumn(
          state = listState,
          contentPadding = contentPadding,
          modifier = Modifier.fillMaxSize(),
        ) {
          item { ListHeader { Text("Workout") } }
        }
      }
    }
  }

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

// ---------------------------------------------------------------------------
// States — interaction (pressed / focused; focus matters on Wear for rotary /
// D-pad), disabled, and toggle off↔on. A held interaction is seeded into the
// InteractionSource so the static capture shows that state's resting state-layer.
// ---------------------------------------------------------------------------

@Composable
private fun pressedSource(): MutableInteractionSource {
  val source = remember { MutableInteractionSource() }
  LaunchedEffect(source) { source.emit(PressInteraction.Press(Offset.Zero)) }
  return source
}

@Composable
private fun focusedSource(): MutableInteractionSource {
  val source = remember { MutableInteractionSource() }
  LaunchedEffect(source) { source.emit(FocusInteraction.Focus()) }
  return source
}

@CatalogWearModes
@Composable
fun ButtonPressed() =
  WearSticker { Button(onClick = {}, interactionSource = pressedSource()) { Text("Pressed") } }

@CatalogWearModes
@Composable
fun ButtonFocused() =
  WearSticker { Button(onClick = {}, interactionSource = focusedSource()) { Text("Focused") } }

@CatalogWearModes
@Composable
fun ButtonDisabled() =
  WearSticker { Button(onClick = {}, enabled = false) { Text("Disabled") } }

@CatalogWearModes
@Composable
fun SwitchButtonOff() =
  WearSticker {
    SwitchButton(checked = false, onCheckedChange = {}, label = { Text("Wifi") })
  }

@CatalogWearModes
@Composable
fun CheckboxButtonUnchecked() =
  WearSticker {
    CheckboxButton(checked = false, onCheckedChange = {}, label = { Text("Sync") })
  }
