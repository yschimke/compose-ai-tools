package com.example.designcatalogm3.shared

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ee.schimke.composeai.overrides.previewOverrideBoolean
import ee.schimke.composeai.overrides.previewOverrideColor
import ee.schimke.composeai.overrides.previewOverrideFloat
import ee.schimke.composeai.overrides.previewOverrideInt
import ee.schimke.composeai.overrides.previewOverrideString

// Desktop is the module the `compose-preview` renderer / daemon builds, so the catalog's knobs
// resolve against the real `previewOverride*` surface here: the daemon seeds replacements and the
// `compose/overrides` producer reads back the declared set for each sticker.
//
// An explicit `index` still wins; when a body passes none, the knob takes the instance it is being
// composed under (`LocalCatalogInstance`, provided per node by `CatalogScreen`), so an assembled
// screen declares one knob per instance rather than collapsing every button's label onto a single
// `label`. Null outside a screen — a lone sticker declares exactly the keys it always did, so no
// baked render and no `previews/<id>.overrides.json` moves.

@Composable
actual fun catalogOverrideString(key: String, default: String, index: Int?): String =
  previewOverrideString(key, default, index ?: LocalCatalogInstance.current)

@Composable
actual fun catalogOverrideInt(key: String, default: Int, index: Int?): Int =
  previewOverrideInt(key, default, index ?: LocalCatalogInstance.current)

@Composable
actual fun catalogOverrideFloat(key: String, default: Float, index: Int?): Float =
  previewOverrideFloat(key, default, index ?: LocalCatalogInstance.current)

@Composable
actual fun catalogOverrideBoolean(key: String, default: Boolean, index: Int?): Boolean =
  previewOverrideBoolean(key, default, index ?: LocalCatalogInstance.current)

@Composable
actual fun catalogOverrideColor(key: String, default: Color, index: Int?): Color =
  previewOverrideColor(key, default, index ?: LocalCatalogInstance.current)
