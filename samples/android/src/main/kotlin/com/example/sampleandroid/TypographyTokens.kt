package com.example.sampleandroid

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ee.schimke.composeai.preview.TypographyCatalog

/**
 * Design tokens annotated with `@TypographyCatalog` — the type-scale sibling of `@ColorCatalog` and
 * the analogue of Showkase's `@ShowkaseTypography`. No `@Preview` is written: the compose-preview
 * plugin discovers the annotated `TextStyle` properties from bytecode and synthesises catalog
 * sheets — one per `group`, plus a module-wide "All type styles" sheet — that the renderer draws by
 * reflecting each value and setting a sample line in it. `name` defaults to the property name;
 * `Body Large` shows the annotation-name override.
 */
@TypographyCatalog(group = "Display")
val DisplayLarge: TextStyle = TextStyle(fontSize = 45.sp, fontWeight = FontWeight.Normal)

@TypographyCatalog(group = "Display")
val DisplaySmall: TextStyle = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Medium)

@TypographyCatalog(name = "Body Large", group = "Body")
val BodyL: TextStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal)

@TypographyCatalog(group = "Body")
val BodySmall: TextStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal)

@TypographyCatalog(group = "Body")
val Caption: TextStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Light)
