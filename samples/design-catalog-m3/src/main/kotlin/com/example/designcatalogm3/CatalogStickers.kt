package com.example.designcatalogm3

import androidx.compose.runtime.Composable
import com.example.designcatalogm3.shared.CatalogComponent
import ee.schimke.composeai.preview.CatalogComponent

// The M3 catalog sticker sheet: one `@Preview` per component, in light + dark (`@CatalogModes`).
//
// The inventory is scoped to the preview PIPELINE's features, not to Material 3's component
// surface — m3-catalog is the exhaustive Material reference and this sheet is compose-ai-tools'
// own harness, so every entry earns its place by being the one or two carriers of some pipeline
// capability (slots, override-knob types, focus/press capture, interaction motion, i18n and a11y
// axes, generic + named font resolution, full-screen device capture). A component that only
// re-proved a capability another sticker already carries is not here.
//
// Each is a thin wrapper — `CatalogSticker { CatalogComponent("<slug>") }` — over the shared
// component set in `:samples:design-catalog-m3-shared`, so the bodies live in one place (also
// mounted live by the in-browser wasm tier).
//
// There is no lane flag. This sheet used to pass `interactive = !LocalInspectionMode.current`, so
// a baked snapshot composed an inert control and the held Live Compose session composed a stateful
// one — the published capture was not always the composable that runs live (issue #3674). The
// shared components are now unconditionally stateful and seed their initial state from the
// `catalogOverride*` knobs, so the baked frame is unchanged while a live click actually moves the
// control.
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
// One id, one composable, every lane — the one-shot `/render`, the held Live Compose daemon session
// (`DesktopHost.acquireInteractiveSession`), and the in-browser wasm tier all compose the same
// control. Nothing here reads `LocalInspectionMode`.
// Public, not `internal`: the playground's "open this preview" handoff seeds ONE cluster file and
// compiles it as its own module against the catalog's `classes/app.jar`. `internal` is
// name-mangled and invisible across that module boundary, so every sticker in a seeded file would
// fail to resolve its own helper — a regression the split introduced, since the helper used to be
// `private` inside the single file the seed carried. `CatalogSticker` / `FullScreenM3` next door in
// `CatalogTheme.kt` are public for the same reason.
fun Sticker(id: String) = CatalogSticker { CatalogComponent(id) }
