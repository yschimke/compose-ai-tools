package com.example.designcatalogm3

import androidx.compose.runtime.Composable
import com.example.designcatalogm3.shared.CatalogComponent
import ee.schimke.composeai.preview.CatalogComponent

// --- Text field — the sheet's only component that owns text state, so the `value` / `label`
// --- string knobs have a carrier. The outlined twin was the same two knobs behind a different
// --- border, so it went with the rest of the by-component redundancy.

@CatalogComponent(
  id = "TextField/Filled",
  group = "Text fields",
  caption = "Editable value + floating label — the sheet's string-knob carrier.",
)
@CatalogModes
@Composable
fun TextFieldSticker() = Sticker("textfield-filled")
