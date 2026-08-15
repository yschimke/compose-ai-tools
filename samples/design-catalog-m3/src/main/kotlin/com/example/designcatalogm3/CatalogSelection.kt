package com.example.designcatalogm3

import androidx.compose.runtime.Composable
import com.example.designcatalogm3.shared.CatalogComponent
import ee.schimke.composeai.preview.CatalogComponent
import ee.schimke.composeai.preview.InteractionPreview
import ee.schimke.composeai.preview.OverrideVariant

// --- Selection controls — checked/selected states (the primary mode to show). ---

// Selection controls carry their unchecked/unselected/off state as an `@OverrideVariant` (seeding
// the shared `checked` / `selected` knob) rather than a duplicated `*Unchecked` / `*Off` /
// `*Unselected`
// wrapper — the render emits a `_VARIANT_<state>` capture that folds under the primary sticker.
@CatalogComponent(
  id = "Checkbox/Checked",
  group = "Selection",
  caption = "Checked; the unchecked state folds in as an @OverrideVariant (checked = false).",
)
@CatalogModes
@OverrideVariant(name = "unchecked", booleans = ["checked=false"])
@Composable
fun CheckboxChecked() = Sticker("checkbox-checked")

// The interaction capture rides the SAME function as the sticker rather than a duplicated
// `SwitchOnInteraction` wrapper: the component keeps its static card and gains a motion artifact
// beside it. `targets = [0, 0]` is how a toggle is spelled — one tap off, one tap back on — so a
// reader sees the thumb travel in both directions and can judge the spring on each.
@CatalogComponent(
  id = "Switch/On",
  group = "Selection",
  caption = "On; the off state folds in as an @OverrideVariant (checked = false).",
)
@CatalogModes
@OverrideVariant(name = "off", booleans = ["checked=false"])
@InteractionPreview(
  targets = [0, 0],
  caption =
    "Toggle off and back on. The thumb resolves through the theme's spatial motion spec — " +
      "the travel and its settle are what a still frame of either end state cannot show.",
)
@Composable
fun SwitchOn() = Sticker("switch-on")

@CatalogComponent(
  id = "RadioButton/Selected",
  group = "Selection",
  caption = "Selected; the unselected state folds in as an @OverrideVariant (selected = false).",
)
@CatalogModes
@OverrideVariant(name = "unselected", booleans = ["selected=false"])
@Composable
fun RadioSelected() = Sticker("radiobutton-selected")

@CatalogComponent(id = "Slider", group = "Selection")
@CatalogModes
@Composable
fun SliderMid() = Sticker("slider")

@CatalogComponent(
  id = "Chip/Filter-Selected",
  group = "Selection",
  caption = "Selected; the unselected state folds in as an @OverrideVariant (selected = false).",
)
@CatalogModes
@OverrideVariant(name = "unselected", booleans = ["selected=false"])
@Composable
fun FilterChipSelected() = Sticker("chip-filter-selected")

@CatalogComponent(id = "Chip/Assist", group = "Selection")
@CatalogModes
@Composable
fun AssistChipSticker() = Sticker("chip-assist")
