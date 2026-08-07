package com.example.designcatalogm3

import androidx.compose.runtime.Composable
import com.example.designcatalogm3.shared.CatalogComponent
import ee.schimke.composeai.preview.CatalogComponent
import ee.schimke.composeai.preview.CatalogVariant

// --- States — interaction (pressed / focused), disabled, and toggle off↔on. ---

@CatalogVariant(
  of = "Button/Filled",
  state = "pressed",
  caption = "Held PressInteraction → pressed state layer.",
)
@CatalogModes
@Composable
fun FilledButtonPressed() = Sticker("button-filled-pressed")

@CatalogVariant(
  of = "Button/Filled",
  state = "keyboard-focus",
  caption =
    "Keyboard focus (focus-visible) → M3 inset focus ring. This is the directional/keyboard " +
      "focus indicator, not the pointer/hover state layer.",
)
@CatalogModes
@Composable
fun FilledButtonFocused() = Sticker("button-filled-focused")

@CatalogVariant(
  of = "Button/Filled",
  props = ["content=icon+label"],
  caption = "Content axis (not a state): leading icon + label, vs the label-only default.",
)
@CatalogModes
@Composable
fun FilledButtonIconLabel() = Sticker("button-filled-icon-label")

// (`SwitchOff`, `CheckboxUnchecked`, `FilterChipUnselected`, `RadioUnselected` removed — those
// states now ride their primary selection control via `@OverrideVariant`, seeding the shared
// `checked` / `selected` knob.)

@CatalogComponent(
  id = "SegmentedButton",
  group = "Selection",
  caption = "Single-choice toggle: selected + unselected segments.",
)
@CatalogModes
@Composable
fun SegmentedToggle() = Sticker("segmentedbutton")
