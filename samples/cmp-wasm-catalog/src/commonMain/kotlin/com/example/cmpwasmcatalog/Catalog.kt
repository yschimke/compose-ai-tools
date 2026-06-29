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
import androidx.compose.ui.unit.dp

/**
 * The M3 catalog component set, re-authored for Compose Multiplatform `wasmJs`.
 *
 * These are the same components the Android `:samples:design-catalog-m3` module renders into the
 * published `design-artifacts/compose-m3` sticker sheet, but written against the CMP `material3`
 * artifact (which ships a `wasmJs` target) with no Android `@Preview` / `Configuration` tooling.
 * Each entry is keyed by the catalog **component id** (the slug the published `catalog.json` uses),
 * so the viewer can mount the live in-browser render for a given catalog component by id.
 *
 * Interactive (real) state, not a baked snapshot: `Switch` / `Checkbox` / `Slider` keep their own
 * remembered state so a visitor can actually toggle them in the browser.
 */
val catalogComponents: Map<String, @Composable () -> Unit> =
  linkedMapOf(
    // Buttons — the five M3 emphasis levels.
    "button-filled" to { Button(onClick = {}) { Text("Filled") } },
    "button-tonal" to { FilledTonalButton(onClick = {}) { Text("Tonal") } },
    "button-outlined" to { OutlinedButton(onClick = {}) { Text("Outlined") } },
    "button-elevated" to { ElevatedButton(onClick = {}) { Text("Elevated") } },
    "button-text" to { TextButton(onClick = {}) { Text("Text") } },
    // Selection controls — start in their resting state but stay interactive.
    "checkbox" to { StatefulCheckbox() },
    "switch" to { StatefulSwitch() },
    "radio-button" to { RadioButton(selected = true, onClick = {}) },
    "slider" to { Box(Modifier.width(220.dp)) { StatefulSlider() } },
    "filter-chip" to { StatefulFilterChip() },
    "assist-chip" to { AssistChip(onClick = {}, label = { Text("Assist") }) },
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
    "text-field-filled" to
      {
        TextField(value = "Filled", onValueChange = {}, label = { Text("Label") })
      },
    "text-field-outlined" to
      {
        OutlinedTextField(value = "Outlined", onValueChange = {}, label = { Text("Label") })
      },
    // Segmented toggle.
    "segmented-button" to { SegmentedToggle() },
  )
