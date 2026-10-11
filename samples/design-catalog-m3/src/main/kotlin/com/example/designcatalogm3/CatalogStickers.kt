package com.example.designcatalogm3

import androidx.compose.runtime.Composable
import com.example.designcatalogm3.shared.CatalogComponent
import ee.schimke.composeai.preview.CatalogComponent

// The M3 catalog sticker sheet: one `@Preview` per component, in light + dark (`@CatalogModes`).
// Scoped to preview-pipeline features (slots, knob types, focus/press capture, interaction motion,
// i18n/a11y axes, font resolution, full-screen capture); m3-catalog is the exhaustive reference.
//
// Each is `CatalogSticker { CatalogComponent("<slug>") }` over the shared component set in
// `:samples:design-catalog-m3-shared`, also mounted live by the wasm tier; components are stateful
// and seeded from `catalogOverride*` knobs, so the baked frame matches the live one.
//
// Catalog identity lives on each preview via `@CatalogComponent` / `@CatalogVariant`;
// `catalog.spec.json` carries only cover-sheet fields. `@CatalogVariant.of` joins on
// `@CatalogComponent.id`, so those ids must stay stable.

/**
 * Every sticker is the shared component inside the catalog theme, on a transparent surface (viewers
 * paint their own backing).
 */
@Composable
// The theme and its overrides live entirely in [CatalogSticker]. One id, one composable, every
// lane. Public, not `internal`: the playground compiles a single seeded file as its own module
// against the catalog's classes, where `internal` is invisible (as are `CatalogSticker` /
// `FullScreenM3`).
fun Sticker(id: String) = CatalogSticker { CatalogComponent(id) }
