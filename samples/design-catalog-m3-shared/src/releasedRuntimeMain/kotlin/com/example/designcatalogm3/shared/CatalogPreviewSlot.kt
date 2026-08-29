package com.example.designcatalogm3.shared

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ee.schimke.composeai.preview.slots.PreviewSlot

internal enum class CatalogSlotSizing {
  Fixed,
  Hug,
}

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun RowScope.CatalogPreviewSlot(
  name: String,
  modifier: Modifier,
  horizontal: CatalogSlotSizing,
  vertical: CatalogSlotSizing,
  content: @Composable () -> Unit,
) = PreviewSlot(name = name, modifier = modifier, content = content)

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun ColumnScope.CatalogPreviewSlot(
  name: String,
  modifier: Modifier,
  horizontal: CatalogSlotSizing,
  vertical: CatalogSlotSizing,
  content: @Composable () -> Unit,
) = PreviewSlot(name = name, modifier = modifier, content = content)
