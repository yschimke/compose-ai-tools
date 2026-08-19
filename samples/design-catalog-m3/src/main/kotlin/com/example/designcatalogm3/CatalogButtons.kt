package com.example.designcatalogm3

import androidx.compose.runtime.Composable
import com.example.designcatalogm3.shared.CatalogComponent
import ee.schimke.composeai.preview.CatalogComponent
import ee.schimke.composeai.preview.OverrideVariant

// --- Buttons — one emphasis level. ---
//
// This catalog covers the preview pipeline's features, not Material's component surface
// (m3-catalog is the exhaustive Material 3 reference), so the tonal / outlined / elevated / text
// emphasis levels are gone: they were five spellings of "a `@CatalogComponent` with
// `@CatalogModes`", and the filled button already carries that plus the disabled `@OverrideVariant`
// and — in `CatalogStates.kt` / `CatalogI18n.kt` — the pressed, keyboard-focus, content-axis and
// font-scale captures that hang off it.

@CatalogComponent(
  id = "Button/Filled",
  group = "Buttons",
  caption =
    "The catalog's stateless-action carrier; the disabled state folds in as an " +
      "@OverrideVariant (enabled = false).",
)
@CatalogModes
@OverrideVariant(name = "disabled", booleans = ["enabled=false"])
@Composable
fun FilledButton() = Sticker("button-filled")

// `FilledButtonPressed` / `FilledButtonFocused` / `FilledButtonIconLabel` (all `Button/Filled`
// variants) are declared in the States section, and `FilledButtonLargeFont` in the i18n/a11y one,
// so the annotation-derived variant order matches the sheet's intended order (pressed →
// keyboard-focus → disabled → content → a11y axes).
