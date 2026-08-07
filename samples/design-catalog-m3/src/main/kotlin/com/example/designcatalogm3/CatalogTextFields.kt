package com.example.designcatalogm3

import androidx.compose.runtime.Composable
import com.example.designcatalogm3.shared.CatalogComponent
import ee.schimke.composeai.preview.CatalogComponent

// --- Text fields. ---

@CatalogComponent(id = "TextField/Filled", group = "Text fields")
@CatalogModes
@Composable
fun TextFieldSticker() = Sticker("textfield-filled")

@CatalogComponent(id = "TextField/Outlined", group = "Text fields")
@CatalogModes
@Composable
fun OutlinedTextFieldSticker() = Sticker("textfield-outlined")
