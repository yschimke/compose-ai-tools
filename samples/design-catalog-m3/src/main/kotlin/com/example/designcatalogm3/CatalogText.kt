package com.example.designcatalogm3

import androidx.compose.runtime.Composable
import com.example.designcatalogm3.shared.CatalogComponent
import ee.schimke.composeai.preview.CatalogComponent

// --- Text options — maxLines + ellipsis overflow, generic-family specimens. ---

@CatalogComponent(
  id = "Text/MaxLines-Truncated",
  group = "Text options",
  caption = "maxLines=2 + ellipsis overflow — exercises the textOverflow product.",
)
@CatalogModes
@Composable
fun TextMaxLinesTruncated() = Sticker("text-maxlines-truncated")

@CatalogComponent(
  id = "Text/Serif",
  group = "Text options",
  caption = "Generic serif family (Noto Serif) — pins the Wasm tier’s font interception.",
)
@CatalogModes
@Composable
fun TextSerifSpecimen() = Sticker("text-serif")

@CatalogComponent(
  id = "Text/Monospace",
  group = "Text options",
  caption = "Generic monospace family (Droid Sans Mono) — pins the Wasm tier’s font interception.",
)
@CatalogModes
@Composable
fun TextMonospaceSpecimen() = Sticker("text-monospace")

// `TextBrandedSpecimen` renders but is deliberately NOT in the published catalog inventory (it has
// no `@CatalogComponent`), matching the pre-annotation spec, which omitted it.
@CatalogModes @Composable fun TextBrandedSpecimen() = Sticker("text-branded")
