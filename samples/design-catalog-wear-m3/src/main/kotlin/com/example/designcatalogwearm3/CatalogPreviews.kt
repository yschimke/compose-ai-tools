package com.example.designcatalogwearm3

import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.wear.compose.foundation.lazy.items
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
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import ee.schimke.composeai.preview.ScrollMode
import ee.schimke.composeai.preview.ScrollingPreview

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

// A workout history the EdgeButton sticker scrolls through. Long enough to
// overflow the viewport by a few screens so, scrolled to the end, the list fills
// the space above the edge button.
private val edgeButtonHistory =
  listOf(
    "Morning run" to "5.2 km · 28 min",
    "Heart rate" to "72 bpm",
    "Sleep" to "7h 14m",
    "Steps" to "6,482",
    "Calories" to "412 kcal",
    "Cycle" to "18 km · 41 min",
    "Swim" to "1.2 km · 32 min",
    "Hike" to "9.4 km · 1h 52m",
    "Strength" to "45 min",
    "Stretch" to "12 min",
    "Yoga" to "30 min",
    "Row" to "2.0 km · 9 min",
  )

// EdgeButton hugs the bottom edge of the round screen via the
// ScreenScaffold(edgeButton = …) slot — its curved shape *is* that placement, so
// it's placed in a real ScreenScaffold + TransformingLazyColumn with the Wear M3
// scaling transformation (SurfaceTransformation), exactly mirroring the official
// samples/wear ActivityListScreen so the button measures at its resting size.
//
// ScreenScaffold reveals the edge button from its scroll state: at the resting
// top it's collapsed, expanding only once the list settles at the bottom. A
// static capture freezes the hidden initial frame (the renderer pauses the
// clock), so the sticker uses @ScrollingPreview(END) — scroll the overflowing
// list to the end (the renderer now settles post-scroll animations, so the
// EdgeButton reveal lands at rest) and capture the single settled frame.
@CatalogWearModes
@ScrollingPreview(modes = [ScrollMode.END])
@Composable
fun EdgeButtonSticker() =
  MaterialTheme {
    // No TimeText: it renders the live wall clock, which would make the sticker
    // non-deterministic (and churn the weekly design-artifacts bundle). The edge
    // slot is what this sticker documents.
    AppScaffold(timeText = {}) {
      val listState = rememberTransformingLazyColumnState()
      val spec = rememberTransformationSpec()
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
          item {
            ListHeader(
              modifier = Modifier.transformedHeight(this, spec),
              transformation = SurfaceTransformation(spec),
            ) {
              Text("Workout")
            }
          }
          items(edgeButtonHistory) { (title, subtitle) ->
            TitleCard(
              onClick = {},
              title = { Text(title) },
              subtitle = { Text(subtitle) },
              modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
              transformation = SurfaceTransformation(spec),
            )
          }
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
