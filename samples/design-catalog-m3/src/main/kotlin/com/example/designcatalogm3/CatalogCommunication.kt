package com.example.designcatalogm3

import androidx.compose.runtime.Composable
import com.example.designcatalogm3.shared.CatalogComponent
import ee.schimke.composeai.preview.CatalogComponent

// --- Communication — the determinate progress indicator (a Float knob) and the badge (the sheet's
// --- only Int knob). The circular indicator drew the same `progress` knob as the linear one.

@CatalogComponent(
  id = "Progress/Linear",
  group = "Communication",
  caption = "Determinate, driven by the `progress` Float knob.",
)
@CatalogModes
@Composable
fun LinearProgressSticker() = Sticker("progress-linear")

@CatalogComponent(
  id = "Badge",
  group = "Communication",
  caption = "The sheet's Int knob (`count`).",
)
@CatalogModes
@Composable
fun BadgeSticker() = Sticker("badge")
