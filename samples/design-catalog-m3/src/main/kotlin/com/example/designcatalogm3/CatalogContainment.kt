package com.example.designcatalogm3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.example.designcatalogm3.shared.CatalogComponent
import ee.schimke.composeai.preview.CatalogComponent
import ee.schimke.composeai.preview.CatalogVariant
import ee.schimke.composeai.preview.slots.LocalSlotMode

// --- Containment — one card, and it is the slotted one. ---
//
// The three plain cards (elevated / outlined / filled) and the FAB differed from each other only in
// Material's own surface treatment, which is m3-catalog's job. This card is here for a pipeline
// feature none of them touched: `PreviewSlot` regions, the slot map a structured-screen builder
// fills. It also carries the real-Arabic locale variant in `CatalogI18n.kt`, because it is the one
// sticker with both a leading region and a text column, so a mirror is unmistakable.

// The plain sticker renders normally (the markers are no-ops); `SlottedCardSlots` provides
// `LocalSlotMode = true` so each marker draws its labelled placeholder. Same body, two modes.
@CatalogComponent(
  id = "Card/Slots",
  group = "Containment",
  caption = "A card with named PreviewSlot regions a structured-screen builder fills.",
)
@CatalogModes
@Composable
fun SlottedCardSticker() = Sticker("card-slots")

@CatalogVariant(
  of = "Card/Slots",
  state = "slot-mode",
  caption = "Slot mode: each PreviewSlot draws its labelled placeholder.",
)
@CatalogModes
@Composable
fun SlottedCardSlotsSticker() = CatalogSticker {
  CompositionLocalProvider(LocalSlotMode provides true) { CatalogComponent("card-slots") }
}
