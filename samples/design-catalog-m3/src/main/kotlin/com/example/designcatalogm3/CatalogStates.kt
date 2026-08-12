package com.example.designcatalogm3

import androidx.compose.runtime.Composable
import com.example.designcatalogm3.shared.CatalogComponent
import ee.schimke.composeai.preview.CatalogComponent
import ee.schimke.composeai.preview.CatalogVariant
import ee.schimke.composeai.preview.FocusedPreview

// --- States — interaction (pressed / focused), disabled, and toggle off↔on. ---
//
// The two interaction states are captured through **real input** (issue #3672). Both stickers
// compose a plain `Button` — see the note in `:samples:design-catalog-m3-shared`'s
// `CatalogComponents` — and `@FocusedPreview` drives the state at render time: the desktop renderer
// walks focus with `FocusManager.moveFocus(...)` under a synthetic keyboard input mode, and
// `pressed = true` dispatches a real pointer down onto the focused button, hit-tested like a click.
// Until the desktop renderer learned that walk these two seeded a held `MutableInteractionSource`
// from a `LaunchedEffect`, which painted a state layer without any component ever entering the
// state.

@CatalogVariant(
  of = "Button/Filled",
  state = "pressed",
  caption = "Pointer press dispatched onto the button → pressed state layer + ripple.",
)
@CatalogModes
// `indices = [0]` is the single `Button` in the sticker; `pressed = true` adds the pointer down
// after the walk lands. Material's state layer resolves to the topmost interaction, so the capture
// reads as pressed rather than as focused.
@FocusedPreview(indices = [0], pressed = true)
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
// A single capture, so discovery keeps the plain filename and the `catalog.spec.json` fold by
// function name is untouched — same shape the Android sibling uses.
@FocusedPreview(indices = [0])
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
