@file:Suppress("RestrictedApiAndroidX")

package com.example.sampleremotecompose

import androidx.wear.compose.remote.material3.RemoteColorScheme
import androidx.wear.compose.remote.material3.RemoteShapes
import androidx.wear.compose.remote.material3.RemoteTypography
import ee.schimke.composeai.preview.ColorCatalog
import ee.schimke.composeai.preview.ShapeCatalog
import ee.schimke.composeai.preview.TypographyCatalog

/** Real Remote Material 3 whole-object catalogs, exercising discovery and renderer conversion. */
@ColorCatalog(name = "Remote Material 3", group = "Remote theme")
val RemoteCatalogColorScheme: RemoteColorScheme = RemoteColorScheme()

@TypographyCatalog(name = "Remote Material 3", group = "Remote theme")
val RemoteCatalogTypography: RemoteTypography = RemoteTypography()

@ShapeCatalog(name = "Remote Material 3", group = "Remote theme")
val RemoteCatalogShapes: RemoteShapes = RemoteShapes()
