package com.example.samplewear

import android.content.Context
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.tiles.tooling.preview.Preview
import androidx.wear.tiles.tooling.preview.TilePreviewData
import androidx.wear.tiles.tooling.preview.TilePreviewHelper
import androidx.wear.tooling.preview.devices.WearDevices

/**
 * Minimal `@androidx.wear.tiles.tooling.preview.Preview` sample — exercises the
 * tile-preview discovery + rendering path end-to-end. Not a real production tile.
 */
@Preview(device = WearDevices.SMALL_ROUND, name = "Small Round")
@Preview(device = WearDevices.LARGE_ROUND, name = "Large Round")
fun HelloTilePreview(context: Context): TilePreviewData =
    TilePreviewData { _ ->
        TilePreviewHelper.singleTimelineEntryTileBuilder(
            Text.Builder()
                .setText("Hello Tiles")
                .setFontStyle(
                    FontStyle.Builder()
                        .setSize(sp(20f))
                        .setColor(argb(0xFFFFFFFF.toInt()))
                        .build(),
                )
                .build(),
        ).build()
    }

/**
 * Multi-preview meta-annotation — mirrors Wear OS samples' `MultiRoundDevicesPreviews`
 * pattern. Our discovery walks annotation classes looking for @Preview, so this
 * should expand to small + large round previews on any function it annotates.
 */
@Preview(device = WearDevices.SMALL_ROUND, name = "Small Round")
@Preview(device = WearDevices.LARGE_ROUND, name = "Large Round")
internal annotation class MultiRoundTilesPreviews

@MultiRoundTilesPreviews
fun CounterTilePreview(context: Context): TilePreviewData =
    TilePreviewData { _ ->
        TilePreviewHelper.singleTimelineEntryTileBuilder(
            Text.Builder()
                .setText("42")
                .setFontStyle(
                    FontStyle.Builder()
                        .setSize(sp(48f))
                        .setColor(argb(0xFFFFFFFF.toInt()))
                        .build(),
                )
                .build(),
        ).build()
    }
