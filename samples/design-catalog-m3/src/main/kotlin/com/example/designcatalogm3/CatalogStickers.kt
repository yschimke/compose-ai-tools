package com.example.designcatalogm3

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalInspectionMode
import com.example.designcatalogm3.shared.CatalogComponent
import ee.schimke.composeai.preview.CatalogComponent

// The M3 catalog sticker sheet: one `@Preview` per component, in light + dark (`@CatalogModes`).
// Each is a thin wrapper — `CatalogSticker { CatalogComponent("<slug>", interactive = …) }` — over
// the shared component set in `:samples:design-catalog-m3-shared`, so the bodies live in one place
// (also mounted live by the in-browser wasm tier).
//
// `interactive` is derived from `LocalInspectionMode` rather than hard-coded, so the SAME
// `@Preview`
// serves both lanes correctly (the two share this sticker sheet — see [Sticker]):
//   * baked snapshot / one-shot `/render` (`LocalInspectionMode = true`) → `interactive = false`,
//     the deterministic frame (static toggles / determinate progress) the published catalog shows —
//     pixel-unchanged.
//   * held **Live Compose** daemon session (`LocalInspectionMode = false`) → `interactive = true`,
//     so its click dispatch actually toggles the segmented button / switch / chip and drives the
//     stateful widgets — matching what the in-browser wasm tier already does. Hard-coding `false`
//     left every live-lane click a no-op (the segmented toggle wouldn't flip).
//
// The catalog identity — component id, group, caption, and per-variant tags — lives on each preview
// via `@CatalogComponent` / `@CatalogVariant` (compose-ai-tools' catalog-annotations), so it sits
// next to the composable instead of being restated in `catalog.spec.json`. The design-artifacts
// export builds the inventory from these annotations; `catalog.spec.json` now carries only the
// cover-sheet fields (system / title / breakpoints / referenceKits). A `@CatalogVariant.of` names
// its parent by that parent's `@CatalogComponent.id`, so those ids are the join and must stay
// stable.

/**
 * Every sticker is the shared component (deterministic frame) inside the catalog theme wrapper. All
 * stickers render on a transparent surface — the interactive viewers (preview server, catalog
 * index) paint their own backing behind the PNG, so the sticker is a component silhouette rather
 * than carrying a baked-in surface of its own.
 */
@Composable
// A clean one-liner: the theme (and the font / palette override) lives entirely in
// [CatalogSticker], so a preview never spells the typeface or knows an override exists.
//
// `interactive = !LocalInspectionMode.current`, so a single sticker serves both render lanes:
//   * one-shot / baked render — `LocalInspectionMode = true` (Compose's preview signal, and what
//     the daemon's one-shot `/render` lane sets) → `interactive = false` → a deterministic static
//     frame, pixel-unchanged from before.
//   * held Live Compose daemon session — `DesktopHost.acquireInteractiveSession` seeds
//     `inspectionMode = false` → `interactive = true` → live, stateful widgets whose click dispatch
//     actually mutates state (the segmented toggle flips, the switch/chip toggle).
// This is the one lever on which the baked and live lanes diverge, exactly as `CatalogComponent`
// documents.
// Public, not `internal`: the playground's "open this preview" handoff seeds ONE cluster file and
// compiles it as its own module against the catalog's `classes/app.jar`. `internal` is
// name-mangled and invisible across that module boundary, so every sticker in a seeded file would
// fail to resolve its own helper — a regression the split introduced, since the helper used to be
// `private` inside the single file the seed carried. `CatalogSticker` / `FullScreenM3` next door in
// `CatalogTheme.kt` are public for the same reason.
fun Sticker(id: String) = CatalogSticker {
  CatalogComponent(id, interactive = !LocalInspectionMode.current)
}
