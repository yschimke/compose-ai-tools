@file:CatalogGroup(name = "Shared source", section = "Samples")

package com.example.previewsourceshared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.preview.CatalogComponent
import ee.schimke.composeai.preview.CatalogGroup

/**
 * Declared once here; rendered by `:samples:cmp-shared` on the Desktop lane AND by
 * `:samples:cmp-android-robolectric` on the Robolectric lane, because both name this module in
 * `composePreviewSource`. Neither re-declares it.
 */
@CatalogComponent(id = "shared-green", caption = "Rendered on both lanes from one declaration")
@Preview
@Composable
fun SharedSourceGreenBoxPreview() {
  Box(modifier = Modifier.size(120.dp).background(Color.Green)) { Text("shared source") }
}

/**
 * A second preview so the two lanes have to agree on a SET rather than on one file. The
 * `@file:CatalogGroup` above applies to both, and only does so because each consumer points
 * `previewSourceRoots` at this module's sources.
 */
@CatalogComponent(id = "shared-magenta", caption = "Second preview, same file, same group")
@Preview(name = "Magenta", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
fun SharedSourceMagentaBoxPreview() {
  Box(modifier = Modifier.size(120.dp).background(Color.Magenta)) { Text("shared source 2") }
}
