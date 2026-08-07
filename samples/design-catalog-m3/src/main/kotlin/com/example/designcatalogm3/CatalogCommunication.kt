package com.example.designcatalogm3

import androidx.compose.runtime.Composable
import com.example.designcatalogm3.shared.CatalogComponent
import ee.schimke.composeai.preview.CatalogComponent

// --- Communication — progress + badge. ---

@CatalogComponent(id = "Progress/Linear", group = "Communication")
@CatalogModes
@Composable
fun LinearProgressSticker() = Sticker("progress-linear")

@CatalogComponent(id = "Progress/Circular", group = "Communication")
@CatalogModes
@Composable
fun CircularProgressSticker() = Sticker("progress-circular")

@CatalogComponent(id = "Badge", group = "Communication")
@CatalogModes
@Composable
fun BadgeSticker() = Sticker("badge")
