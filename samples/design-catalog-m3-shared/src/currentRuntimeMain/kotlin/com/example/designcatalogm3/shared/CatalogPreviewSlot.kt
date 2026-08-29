package com.example.designcatalogm3.shared

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ee.schimke.composeai.preview.slots.PreviewSlot
import ee.schimke.composeai.preview.slots.PreviewSlotConstraints
import ee.schimke.composeai.preview.slots.PreviewSlotSizing

internal enum class CatalogSlotSizing {
  Fixed,
  Hug,
}

@Composable
internal fun RowScope.CatalogPreviewSlot(
  name: String,
  modifier: Modifier,
  horizontal: CatalogSlotSizing,
  vertical: CatalogSlotSizing,
  content: @Composable () -> Unit,
) =
  PreviewSlot(
    name = name,
    modifier = modifier,
    constraints =
      PreviewSlotConstraints(horizontal = horizontal.toRuntime(), vertical = vertical.toRuntime()),
    content = content,
  )

@Composable
internal fun ColumnScope.CatalogPreviewSlot(
  name: String,
  modifier: Modifier,
  horizontal: CatalogSlotSizing,
  vertical: CatalogSlotSizing,
  content: @Composable () -> Unit,
) =
  PreviewSlot(
    name = name,
    modifier = modifier,
    constraints =
      PreviewSlotConstraints(horizontal = horizontal.toRuntime(), vertical = vertical.toRuntime()),
    content = content,
  )

private fun CatalogSlotSizing.toRuntime(): PreviewSlotSizing =
  when (this) {
    CatalogSlotSizing.Fixed -> PreviewSlotSizing.Fixed
    CatalogSlotSizing.Hug -> PreviewSlotSizing.Hug
  }
