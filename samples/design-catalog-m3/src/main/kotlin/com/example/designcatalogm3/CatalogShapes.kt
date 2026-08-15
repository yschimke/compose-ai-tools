package com.example.designcatalogm3

import androidx.compose.runtime.Composable
import ee.schimke.composeai.preview.CatalogComponent

/** Interactive Material expressive interpolation, pinned to 50% in deterministic captures. */
@CatalogComponent(
  id = "Shape/Morph",
  group = "Shape",
  caption = "Square to rounded 9-point star; drag the slider in Live Compose or Wasm.",
)
@CatalogModes
@Composable
fun ShapeMorph() = Sticker("shape-morph")
