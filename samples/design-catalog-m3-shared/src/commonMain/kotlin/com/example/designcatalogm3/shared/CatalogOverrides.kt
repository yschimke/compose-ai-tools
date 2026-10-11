package com.example.designcatalogm3.shared

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The catalog's bridge to the `previewOverride*` named-override surface, which is JVM-only and
 * can't be referenced from `commonMain`. On desktop these delegate to `previewOverride*` (so the
 * daemon can seed values and `compose/overrides` can enumerate knobs); on wasmJs they return the
 * default. Unseeded, every wrapper returns [default].
 *
 * @param key the knob's stable name (the seed key) and the label a viewer shows. @param index
 * optional per-item index, so repeated rows each get a knob (`key[index]`).
 */
@Composable
expect fun catalogOverrideString(key: String, default: String, index: Int? = null): String

// TODO: add `catalogOverrideFont` (delegating to `previewOverrideFont` on desktop) once
// `composeaiReleasedRuntimeVersion` ships it; this module builds against the released runtime.

/** Editable **int** knob (an item count, a badge number). See [catalogOverrideString]. */
@Composable expect fun catalogOverrideInt(key: String, default: Int, index: Int? = null): Int

/** Editable **float** knob (a slider / progress value). See [catalogOverrideString]. */
@Composable expect fun catalogOverrideFloat(key: String, default: Float, index: Int? = null): Float

/** Editable **boolean** knob (a checked / selected state). See [catalogOverrideString]. */
@Composable
expect fun catalogOverrideBoolean(key: String, default: Boolean, index: Int? = null): Boolean

/** Editable **color** knob (an accent / fill). See [catalogOverrideString]. */
@Composable expect fun catalogOverrideColor(key: String, default: Color, index: Int? = null): Color
