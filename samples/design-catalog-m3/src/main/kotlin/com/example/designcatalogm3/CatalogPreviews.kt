package com.example.designcatalogm3

import androidx.compose.runtime.Composable
import com.example.designcatalogm3.shared.CatalogComponent

// The M3 catalog sticker sheet: one `@Preview` per component, in light + dark (`@CatalogModes`).
// Each is a thin wrapper — `CatalogSticker { CatalogComponent("<slug>", interactive = false) }` —
// over the shared component set in `:samples:design-catalog-m3-shared`, so the bodies live in one
// place (also mounted live by the in-browser wasm tier). `interactive = false` renders the
// deterministic baked frame (static toggles / determinate progress) the published catalog shows.
//
// Function names are the join key the export driver matches against `catalog.spec.json`'s `preview`
// field (`PreviewDiscovery` keys off the function name), so they must not change.

// --- Buttons — the five M3 emphasis levels, plus a disabled state. ---

@CatalogModes @Composable fun FilledButton() = Sticker("button-filled")

@CatalogModes @Composable fun FilledTonalButtonSticker() = Sticker("button-tonal")

@CatalogModes @Composable fun OutlinedButtonSticker() = Sticker("button-outlined")

@CatalogModes @Composable fun ElevatedButtonSticker() = Sticker("button-elevated")

@CatalogModes @Composable fun TextButtonSticker() = Sticker("button-text")

@CatalogModes @Composable fun FilledButtonDisabled() = Sticker("button-filled-disabled")

// --- Selection controls — checked/selected states (the primary mode to show). ---

@CatalogModes @Composable fun CheckboxChecked() = Sticker("checkbox-checked")

@CatalogModes @Composable fun SwitchOn() = Sticker("switch-on")

@CatalogModes @Composable fun RadioSelected() = Sticker("radiobutton-selected")

@CatalogModes @Composable fun SliderMid() = Sticker("slider")

@CatalogModes @Composable fun FilterChipSelected() = Sticker("chip-filter-selected")

@CatalogModes @Composable fun AssistChipSticker() = Sticker("chip-assist")

// --- Containment — cards and the FAB. ---

@CatalogModes @Composable fun ElevatedCardSticker() = Sticker("card-elevated")

@CatalogModes @Composable fun OutlinedCardSticker() = Sticker("card-outlined")

@CatalogModes @Composable fun FilledCardSticker() = Sticker("card-filled")

@CatalogModes @Composable fun FabSticker() = Sticker("fab")

// --- Communication — progress + badge. ---

@CatalogModes @Composable fun LinearProgressSticker() = Sticker("progress-linear")

@CatalogModes @Composable fun CircularProgressSticker() = Sticker("progress-circular")

@CatalogModes @Composable fun BadgeSticker() = Sticker("badge")

// --- Text fields. ---

@CatalogModes @Composable fun TextFieldSticker() = Sticker("textfield-filled")

@CatalogModes @Composable fun OutlinedTextFieldSticker() = Sticker("textfield-outlined")

// --- Text options — maxLines + ellipsis overflow, generic-family specimens. ---

@CatalogModes @Composable fun TextMaxLinesTruncated() = Sticker("text-maxlines-truncated")

@CatalogModes @Composable fun TextSerifSpecimen() = Sticker("text-serif")

@CatalogModes @Composable fun TextMonospaceSpecimen() = Sticker("text-monospace")

// --- States — interaction (pressed / focused), disabled, and toggle off↔on. ---

@CatalogModes @Composable fun FilledButtonPressed() = Sticker("button-filled-pressed")

@CatalogModes @Composable fun FilledButtonFocused() = Sticker("button-filled-focused")

@CatalogModes @Composable fun OutlinedButtonDisabled() = Sticker("button-outlined-disabled")

@CatalogModes @Composable fun SwitchOff() = Sticker("switch-off")

@CatalogModes @Composable fun CheckboxUnchecked() = Sticker("checkbox-unchecked")

@CatalogModes @Composable fun FilterChipUnselected() = Sticker("chip-filter-unselected")

@CatalogModes @Composable fun SegmentedToggle() = Sticker("segmentedbutton")

/** Every sticker is the shared component (deterministic frame) inside the catalog theme wrapper. */
@Composable
private fun Sticker(id: String) = CatalogSticker { CatalogComponent(id, interactive = false) }
