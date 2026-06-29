@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.cmpwasmcatalog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The M3 catalog component set, re-authored for Compose Multiplatform `wasmJs`.
 *
 * These are the same components the Android `:samples:design-catalog-m3` module renders into the
 * published `design-artifacts/compose-m3` sticker sheet, written against the CMP `material3`
 * artifact (which ships a `wasmJs` target) with no Android `@Preview` / `Configuration` tooling.
 *
 * **Keys are the catalog's slugged `componentId`** — the exact id the published `catalog.json` and
 * the live-server / README deep links use (`slug()` in `scripts/design-artifacts`: lowercase,
 * non-alphanumeric runs → `-`). e.g. `Switch/On` → `switch-on`, `Chip/Filter-Selected` →
 * `chip-filter-selected`, `TextField/Filled` → `textfield-filled`. This 1:1 mirrors
 * `samples/design-catalog-m3/catalog.spec.json` so `/wasm/compose-m3/?id=<slug>` resolves every
 * component instead of falling into the unknown-id branch.
 *
 * Interactive (real) state, not a baked snapshot: `Switch` / `Checkbox` / `Slider` / `FilterChip`
 * keep their own remembered state so a visitor can toggle them in the browser; the pressed/focused
 * states seed a held interaction so the resting state layer matches the catalog's static capture.
 */
val catalogComponents: Map<String, @Composable () -> Unit> =
  linkedMapOf(
    // Buttons — the five M3 emphasis levels, plus disabled.
    "button-filled" to { Button(onClick = {}) { Text("Filled") } },
    "button-tonal" to { FilledTonalButton(onClick = {}) { Text("Tonal") } },
    "button-outlined" to { OutlinedButton(onClick = {}) { Text("Outlined") } },
    "button-elevated" to { ElevatedButton(onClick = {}) { Text("Elevated") } },
    "button-text" to { TextButton(onClick = {}) { Text("Text") } },
    "button-filled-disabled" to { Button(onClick = {}, enabled = false) { Text("Disabled") } },
    // Selection controls — primary (checked/selected) state, interactive.
    "checkbox-checked" to { StatefulCheckbox(initial = true) },
    "switch-on" to { StatefulSwitch(initial = true) },
    "radiobutton-selected" to { RadioButton(selected = true, onClick = {}) },
    "slider" to { Box(Modifier.width(220.dp)) { StatefulSlider() } },
    "chip-filter-selected" to { StatefulFilterChip(initial = true) },
    "chip-assist" to { AssistChip(onClick = {}, label = { Text("Assist") }) },
    // Containment — cards and the FAB.
    "card-elevated" to
      {
        ElevatedCard { Box(Modifier.size(160.dp, 80.dp)) { Text("Elevated card") } }
      },
    "card-outlined" to
      {
        OutlinedCard { Box(Modifier.size(160.dp, 80.dp)) { Text("Outlined card") } }
      },
    "card-filled" to { Card { Box(Modifier.size(160.dp, 80.dp)) { Text("Filled card") } } },
    "fab" to { FloatingActionButton(onClick = {}) { Text("+") } },
    // Communication — progress + badge.
    "progress-linear" to
      {
        Box(Modifier.width(220.dp)) { LinearProgressIndicator(progress = { 0.6f }) }
      },
    "progress-circular" to { CircularProgressIndicator(progress = { 0.6f }) },
    "badge" to { Badge { Text("8") } },
    // Text fields.
    "textfield-filled" to
      {
        TextField(value = "Filled", onValueChange = {}, label = { Text("Label") })
      },
    "textfield-outlined" to
      {
        OutlinedTextField(value = "Outlined", onValueChange = {}, label = { Text("Label") })
      },
    // States — interaction (pressed / focused), disabled, and toggle off↔on.
    "button-filled-pressed" to
      {
        Button(onClick = {}, interactionSource = pressedSource()) { Text("Pressed") }
      },
    "button-filled-focused" to
      {
        Button(onClick = {}, interactionSource = focusedSource()) { Text("Focused") }
      },
    "button-outlined-disabled" to
      {
        OutlinedButton(onClick = {}, enabled = false) { Text("Disabled") }
      },
    "switch-off" to { StatefulSwitch(initial = false) },
    "checkbox-unchecked" to { StatefulCheckbox(initial = false) },
    "chip-filter-unselected" to { StatefulFilterChip(initial = false) },
    "segmentedbutton" to { SegmentedToggle() },
    // Text options — maxLines + ellipsis overflow.
    "text-maxlines-truncated" to
      {
        Box(Modifier.width(160.dp)) {
          Text(
            "This body text is deliberately long so it overflows two lines and truncates.",
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
      },
  )
