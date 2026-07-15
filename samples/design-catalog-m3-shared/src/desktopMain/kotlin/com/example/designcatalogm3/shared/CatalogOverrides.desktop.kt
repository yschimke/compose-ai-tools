package com.example.designcatalogm3.shared

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ee.schimke.composeai.overrides.previewOverrideBoolean
import ee.schimke.composeai.overrides.previewOverrideColor
import ee.schimke.composeai.overrides.previewOverrideFloat
import ee.schimke.composeai.overrides.previewOverrideFont
import ee.schimke.composeai.overrides.previewOverrideInt
import ee.schimke.composeai.overrides.previewOverrideString

// Desktop is the module the `compose-preview` renderer / daemon builds, so the catalog's knobs
// resolve against the real `previewOverride*` surface here: the daemon seeds replacements and the
// `compose/overrides` producer reads back the declared set for each sticker.

@Composable
actual fun catalogOverrideString(key: String, default: String, index: Int?): String =
  previewOverrideString(key, default, index)

@Composable
actual fun catalogOverrideFont(key: String, default: String, suggestions: List<String>): String =
  previewOverrideFont(key, default, suggestions = suggestions, googleFonts = true)

@Composable
actual fun catalogOverrideInt(key: String, default: Int, index: Int?): Int =
  previewOverrideInt(key, default, index)

@Composable
actual fun catalogOverrideFloat(key: String, default: Float, index: Int?): Float =
  previewOverrideFloat(key, default, index)

@Composable
actual fun catalogOverrideBoolean(key: String, default: Boolean, index: Int?): Boolean =
  previewOverrideBoolean(key, default, index)

@Composable
actual fun catalogOverrideColor(key: String, default: Color, index: Int?): Color =
  previewOverrideColor(key, default, index)
