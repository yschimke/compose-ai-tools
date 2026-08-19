package com.example.designcatalogm3

import androidx.compose.runtime.Composable
import com.example.designcatalogm3.shared.CatalogComponent
import ee.schimke.composeai.preview.CatalogComponent
import ee.schimke.composeai.preview.InteractionPreview
import ee.schimke.composeai.preview.OverrideVariant

// --- Selection controls — three, by knob TYPE rather than by Material coverage. ---
//
// The unchecked/unselected/off state rides its primary control as an `@OverrideVariant` (seeding
// the shared `checked` / `selected` knob) rather than a duplicated `*Unchecked` / `*Off` wrapper —
// the render emits a `_VARIANT_<state>` capture that folds under the primary sticker.
//
// The radio button, filter chip, assist chip and segmented button used to sit here too. The first
// two were a third and fourth spelling of "an `@OverrideVariant` seeding a `Boolean`", and the last
// two carried no pipeline feature at all — so what's left is one `Boolean` carrier (the checkbox),
// one `Boolean` carrier that also hosts the motion and i18n/a11y axes (the switch), and the one
// `Float` knob on the sheet (the slider).

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
  id = "Slider",
  group = "Selection",
  caption = "The sheet's Float knob (`value`).",
)
@CatalogModes
@Composable
fun SliderMid() = Sticker("slider")
