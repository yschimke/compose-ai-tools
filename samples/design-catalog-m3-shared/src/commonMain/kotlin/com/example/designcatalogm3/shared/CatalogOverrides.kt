package com.example.designcatalogm3.shared

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The catalog's bridge to the opt-in `previewOverride*` named-override surface
 * (`:data-preview-overrides-runtime`). That runtime is a plain JVM artifact, so it can't be
 * referenced from this module's `commonMain` (the `wasmJs` target has no klib for it). These
 * `expect` wrappers let the **one** authoritative component body (`CatalogComponent`) declare its
 * editable knobs in `commonMain` while each target supplies the right backing:
 *
 * * **desktop** (the `@Preview` sticker sheet the renderer / daemon builds) delegates to the real
 *   `previewOverride*`, so the daemon can seed replacements and the `compose/overrides` producer
 *   can enumerate what's editable on each sticker.
 * * **wasmJs** (the in-browser tier, which never runs a daemon) returns the author default — the
 *   knob is inert but the shared body still compiles and renders identically.
 *
 * Every wrapper returns its `default` when nothing is seeded, so the baked sticker sheet is
 * pixel-unchanged; the override only diverges from the default once a daemon seeds a value.
 *
 * @param key the knob's stable name (the seed key); the `previewOverride*` label a viewer shows.
 * @param index an optional per-item index so a repeated component (a list row) exposes one knob per
 *   row (`key[index]`) instead of collapsing them onto a single value.
 */
@Composable
expect fun catalogOverrideString(key: String, default: String, index: Int? = null): String

// NOTE: a `catalogOverrideFont` wrapper (the autocompleting typeface override — see the wear
// catalog's `previewOverrideFont`) is deliberately NOT declared here yet: this shared module is
// built against the *released* `previewOverride*` runtime for the `design-artifacts` publish
// (`composeaiUseReleasedRuntimes`), which won't carry `previewOverrideFont` until the release that
// ships it lands. Add it (delegating to `previewOverrideFont` on desktop) once
// `composeaiReleasedRuntimeVersion` bumps.

/** Editable **int** knob (an item count, a badge number). See [catalogOverrideString]. */
@Composable expect fun catalogOverrideInt(key: String, default: Int, index: Int? = null): Int

/** Editable **float** knob (a slider / progress value). See [catalogOverrideString]. */
@Composable expect fun catalogOverrideFloat(key: String, default: Float, index: Int? = null): Float

/** Editable **boolean** knob (a checked / selected state). See [catalogOverrideString]. */
@Composable
expect fun catalogOverrideBoolean(key: String, default: Boolean, index: Int? = null): Boolean

/** Editable **color** knob (an accent / fill). See [catalogOverrideString]. */
@Composable expect fun catalogOverrideColor(key: String, default: Color, index: Int? = null): Color
