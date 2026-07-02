@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.designcatalogm3

import android.content.res.Configuration
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// Buttons — the five M3 emphasis levels, plus a disabled state.
// ---------------------------------------------------------------------------

@CatalogModes
@Composable
fun FilledButton() = CatalogSticker { Button(onClick = {}) { Text("Filled") } }

@CatalogModes
@Composable
fun FilledTonalButtonSticker() =
  CatalogSticker { FilledTonalButton(onClick = {}) { Text("Tonal") } }

@CatalogModes
@Composable
fun OutlinedButtonSticker() =
  CatalogSticker { OutlinedButton(onClick = {}) { Text("Outlined") } }

@CatalogModes
@Composable
fun ElevatedButtonSticker() =
  CatalogSticker { ElevatedButton(onClick = {}) { Text("Elevated") } }

@CatalogModes
@Composable
fun TextButtonSticker() = CatalogSticker { TextButton(onClick = {}) { Text("Text") } }

@CatalogModes
@Composable
fun FilledButtonDisabled() =
  CatalogSticker { Button(onClick = {}, enabled = false) { Text("Disabled") } }

// ---------------------------------------------------------------------------
// Selection controls — checked/selected states (the primary mode to show).
// ---------------------------------------------------------------------------

@CatalogModes
@Composable
fun CheckboxChecked() = CatalogSticker { Checkbox(checked = true, onCheckedChange = {}) }

@CatalogModes
@Composable
fun SwitchOn() = CatalogSticker { Switch(checked = true, onCheckedChange = {}) }

@CatalogModes
@Composable
fun RadioSelected() = CatalogSticker { RadioButton(selected = true, onClick = {}) }

@CatalogModes
@Composable
fun SliderMid() =
  CatalogSticker { Box(Modifier.width(220.dp)) { Slider(value = 0.5f, onValueChange = {}) } }

@CatalogModes
@Composable
fun FilterChipSelected() =
  CatalogSticker { FilterChip(selected = true, onClick = {}, label = { Text("Filter") }) }

@CatalogModes
@Composable
fun AssistChipSticker() =
  CatalogSticker { AssistChip(onClick = {}, label = { Text("Assist") }) }

// ---------------------------------------------------------------------------
// Containment — cards and the FAB.
// ---------------------------------------------------------------------------

@CatalogModes
@Composable
fun ElevatedCardSticker() =
  CatalogSticker {
    ElevatedCard { Box(Modifier.size(160.dp, 80.dp)) { Text("Elevated card") } }
  }

@CatalogModes
@Composable
fun OutlinedCardSticker() =
  CatalogSticker { OutlinedCard { Box(Modifier.size(160.dp, 80.dp)) { Text("Outlined card") } } }

@CatalogModes
@Composable
fun FilledCardSticker() =
  CatalogSticker { Card { Box(Modifier.size(160.dp, 80.dp)) { Text("Filled card") } } }

@CatalogModes
@Composable
fun FabSticker() =
  CatalogSticker { FloatingActionButton(onClick = {}) { Text("+") } }

// ---------------------------------------------------------------------------
// Communication — progress + badge.
// ---------------------------------------------------------------------------

@CatalogModes
@Composable
fun LinearProgressSticker() =
  CatalogSticker { Box(Modifier.width(220.dp)) { LinearProgressIndicator(progress = { 0.6f }) } }

@CatalogModes
@Composable
fun CircularProgressSticker() =
  CatalogSticker { CircularProgressIndicator(progress = { 0.6f }) }

@CatalogModes
@Composable
fun BadgeSticker() = CatalogSticker { Badge { Text("8") } }

// ---------------------------------------------------------------------------
// Text fields.
// ---------------------------------------------------------------------------

@CatalogModes
@Composable
fun TextFieldSticker() =
  CatalogSticker { TextField(value = "Filled", onValueChange = {}, label = { Text("Label") }) }

@CatalogModes
@Composable
fun OutlinedTextFieldSticker() =
  CatalogSticker {
    OutlinedTextField(value = "Outlined", onValueChange = {}, label = { Text("Label") })
  }

// ---------------------------------------------------------------------------
// Text options — exercises the `compose/semantics` textOverflow product
// (maxLines / lineCount / truncated / overflow) the sticker sheet annotates.
// ---------------------------------------------------------------------------

@Preview(name = "Light", showBackground = true, group = "modes", widthDp = 160)
@Preview(
  name = "Dark",
  showBackground = true,
  group = "modes",
  widthDp = 160,
  uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun TextMaxLinesTruncated() =
  CatalogSticker {
    Text(
      "This body text is deliberately long so it overflows two lines and truncates.",
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
  }

// Generic-family specimens: the two non-default families Android resolves through its system font
// table (serif → Noto Serif, monospace → Droid Sans Mono). They exist to pin the in-browser Wasm
// tier's font interception — its resolver substitutes URL-loaded copies of the same font files —
// so keep the strings/styles in lockstep with the CMP port in `samples/cmp-wasm-catalog`.

@CatalogModes
@Composable
fun TextSerifSpecimen() =
  CatalogSticker { Text("Serif specimen 0123", fontFamily = FontFamily.Serif) }

@CatalogModes
@Composable
fun TextMonospaceSpecimen() =
  CatalogSticker { Text("Mono specimen 0123", fontFamily = FontFamily.Monospace) }

// ---------------------------------------------------------------------------
// States — interaction (pressed / focused), disabled, and toggle off↔on.
// A held interaction is seeded into the InteractionSource so the static capture
// shows that state's resting state-layer (the design contract for the state),
// not the default resting button.
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

@CatalogModes
@Composable
fun FilledButtonPressed() =
  CatalogSticker { Button(onClick = {}, interactionSource = pressedSource()) { Text("Pressed") } }

@CatalogModes
@Composable
fun FilledButtonFocused() =
  CatalogSticker { Button(onClick = {}, interactionSource = focusedSource()) { Text("Focused") } }

@CatalogModes
@Composable
fun OutlinedButtonDisabled() =
  CatalogSticker { OutlinedButton(onClick = {}, enabled = false) { Text("Disabled") } }

@CatalogModes
@Composable
fun SwitchOff() = CatalogSticker { Switch(checked = false, onCheckedChange = {}) }

@CatalogModes
@Composable
fun CheckboxUnchecked() = CatalogSticker { Checkbox(checked = false, onCheckedChange = {}) }

@CatalogModes
@Composable
fun FilterChipUnselected() =
  CatalogSticker { FilterChip(selected = false, onClick = {}, label = { Text("Filter") }) }

@CatalogModes
@Composable
fun SegmentedToggle() =
  CatalogSticker {
    SingleChoiceSegmentedButtonRow {
      SegmentedButton(
        selected = true,
        onClick = {},
        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
      ) {
        Text("On")
      }
      SegmentedButton(
        selected = false,
        onClick = {},
        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
      ) {
        Text("Off")
      }
    }
  }
