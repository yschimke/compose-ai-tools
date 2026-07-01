package com.example.samplecmp

import androidx.compose.ui.graphics.Color
import ee.schimke.composeai.preview.ColorCatalog

/**
 * `@ColorCatalog` tokens on the CMP/Desktop sample. Discovery emits `CATALOG` sheets for these just
 * as it does on Android, but the desktop render backend can't draw them yet (its flat-arg protocol
 * doesn't forward the token list), so `RenderPreviewsTask` skips catalog kinds rather than crash.
 * Their presence here guards that skip: `:samples:cmp:composePreviewRenderAll` must stay green.
 * Desktop catalog rendering is tracked in #2135.
 */
@ColorCatalog(group = "Brand") val CmpBrandPrimary: Color = Color(0xFF3D5AFE)

@ColorCatalog(group = "Brand") val CmpBrandAccent: Color = Color(0xFF00BFA5)
