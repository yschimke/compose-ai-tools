package com.example.designcatalogm3

import androidx.compose.runtime.Composable
import com.example.designcatalogm3.shared.CatalogComponent
import ee.schimke.composeai.preview.CatalogComponent
import ee.schimke.composeai.preview.OverrideVariant

// --- Buttons — the five M3 emphasis levels, plus a disabled state. ---

@CatalogComponent(
  id = "Button/Filled",
  group = "Buttons",
  caption =
    "Highest emphasis; the primary action; the disabled state folds in as an " +
      "@OverrideVariant (enabled = false).",
)
@CatalogModes
@OverrideVariant(name = "disabled", booleans = ["enabled=false"])
@Composable
fun FilledButton() = Sticker("button-filled")

@CatalogComponent(id = "Button/Tonal", group = "Buttons", caption = "Secondary, still prominent.")
@CatalogModes
@Composable
fun FilledTonalButtonSticker() = Sticker("button-tonal")

@CatalogComponent(
  id = "Button/Outlined",
  group = "Buttons",
  caption = "Medium emphasis on a busy surface.",
)
@CatalogModes
@OverrideVariant(name = "disabled", booleans = ["enabled=false"])
@Composable
fun OutlinedButtonSticker() = Sticker("button-outlined")

@CatalogComponent(
  id = "Button/Elevated",
  group = "Buttons",
  caption = "Outlined alternative needing separation.",
)
@CatalogModes
@Composable
fun ElevatedButtonSticker() = Sticker("button-elevated")

@CatalogComponent(
  id = "Button/Text",
  group = "Buttons",
  caption = "Lowest emphasis; inline actions.",
)
@CatalogModes
@Composable
fun TextButtonSticker() = Sticker("button-text")

// `FilledButtonDisabled` (a `Button/Filled` variant) is declared in the States section below,
// between the pressed/focused and content variants, so the annotation-derived variant order matches
// the sheet's intended order (pressed → keyboard-focus → disabled → content axes).
