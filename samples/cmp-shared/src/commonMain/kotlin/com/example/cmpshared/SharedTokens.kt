package com.example.cmpshared

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import ee.schimke.composeai.preview.ColorCatalog
import ee.schimke.composeai.preview.TypographyCatalog

// Design tokens declared in `commonMain` with the multiplatform `@ColorCatalog` /
// `@TypographyCatalog`
// annotations — proving a KMP consumer can reference them from shared code (the whole point of the
// multiplatform `preview-annotations` artifact). This is the shape meshcore-mobile's shared theme
// tokens take.
@ColorCatalog(group = "Brand") val BrandTeal: Color = Color(0xFF006A60)

@TypographyCatalog(group = "Type") val Body: TextStyle = TextStyle(fontSize = 16.sp)
