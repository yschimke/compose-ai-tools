package com.example.designcatalogm3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.example.designcatalogm3.shared.CatalogComponent
import ee.schimke.composeai.preview.CatalogComponent
import ee.schimke.composeai.preview.CatalogVariant
import ee.schimke.composeai.preview.slots.LocalSlotMode

// --- Containment — cards and the FAB. ---

@CatalogComponent(id = "Card/Elevated", group = "Containment")
@CatalogModes
@Composable
fun ElevatedCardSticker() = Sticker("card-elevated")

@CatalogComponent(id = "Card/Outlined", group = "Containment")
@CatalogModes
@Composable
fun OutlinedCardSticker() = Sticker("card-outlined")

@CatalogComponent(id = "Card/Filled", group = "Containment")
@CatalogModes
@Composable
fun FilledCardSticker() = Sticker("card-filled")

// A slotted card: its regions are `PreviewSlot` markers. The plain sticker renders normally (the
// markers are no-ops); `SlottedCardSlots` provides `LocalSlotMode = true` so each marker draws its
// labelled placeholder — the slot map a structured-screen builder fills. Same body, two modes.
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
  CompositionLocalProvider(LocalSlotMode provides true) {
    CatalogComponent("card-slots", interactive = false)
  }
}

@CatalogComponent(id = "FAB", group = "Containment")
@CatalogModes
@Composable
fun FabSticker() = Sticker("fab")
