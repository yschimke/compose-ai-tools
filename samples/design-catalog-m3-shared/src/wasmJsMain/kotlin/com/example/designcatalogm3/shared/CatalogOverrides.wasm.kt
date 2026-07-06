package com.example.designcatalogm3.shared

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The in-browser tier never runs a daemon, so there is nothing to seed a knob: every lookup is the
// author default. Keeping the wrappers here (rather than dropping the `previewOverride*` calls)
// lets
// the shared body declare its knobs once in `commonMain` and still compile to a wasm klib — the
// `:data-preview-overrides-runtime` JVM artifact has none.

@Composable
actual fun catalogOverrideString(key: String, default: String, index: Int?): String = default

@Composable actual fun catalogOverrideInt(key: String, default: Int, index: Int?): Int = default

@Composable
actual fun catalogOverrideFloat(key: String, default: Float, index: Int?): Float = default

@Composable
actual fun catalogOverrideBoolean(key: String, default: Boolean, index: Int?): Boolean = default

@Composable
actual fun catalogOverrideColor(key: String, default: Color, index: Int?): Color = default
