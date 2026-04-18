package com.example.samplewear

import android.content.Context
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.material3.titleCard
import androidx.wear.protolayout.modifiers.LayoutModifier
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.modifiers.contentDescription
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.tooling.preview.Preview
import androidx.wear.tiles.tooling.preview.TilePreviewData
import androidx.wear.tiles.tooling.preview.TilePreviewHelper
import androidx.wear.tooling.preview.devices.WearDevices

/**
 * Multi-preview meta-annotation — mirrors Wear OS samples' `MultiRoundDevicesPreviews`
 * pattern. Our discovery walks annotation classes looking for @Preview, so this
 * expands to small + large round previews on any function it annotates.
 */
@Preview(device = WearDevices.SMALL_ROUND, name = "Small Round")
@Preview(device = WearDevices.LARGE_ROUND, name = "Large Round")
internal annotation class MultiRoundTilesPreviews

/**
 * Minimal Material3 tile — title card with a subtitle, exercising the happy
 * path for the protolayout-material3 `materialScope` / `primaryLayout` APIs.
 * Enough to prove the renderer produces readable content, not just a bounding
 * box.
 */
@MultiRoundTilesPreviews
fun HelloTilePreview(context: Context): TilePreviewData =
    TilePreviewData { request ->
        TilePreviewHelper.singleTimelineEntryTileBuilder(
            materialScope(context, request.deviceConfiguration) {
                primaryLayout(
                    titleSlot = { text("Today".layoutString) },
                    mainSlot = {
                        titleCard(
                            onClick = clickable(),
                            title = { text("Morning run".layoutString) },
                            content = { text("5.2 km · 28 min".layoutString) },
                            modifier = LayoutModifier.contentDescription("Morning run summary"),
                        )
                    },
                )
            },
        ).build()
    }

/**
 * Second tile variant — shows a single centered stat, demonstrating that
 * repeated tile previews on the same file render independently.
 */
@MultiRoundTilesPreviews
fun StepsTilePreview(context: Context): TilePreviewData =
    TilePreviewData { request ->
        TilePreviewHelper.singleTimelineEntryTileBuilder(
            materialScope(context, request.deviceConfiguration) {
                primaryLayout(
                    titleSlot = { text("Steps".layoutString) },
                    mainSlot = {
                        titleCard(
                            onClick = clickable(),
                            title = { text("6,482".layoutString) },
                            content = { text("Goal 10,000".layoutString) },
                            modifier = LayoutModifier.contentDescription("Steps today"),
                        )
                    },
                    bottomSlot = {
                        textEdgeButton(
                            onClick = clickable(),
                            labelContent = { text("Details".layoutString) },
                            modifier = LayoutModifier.contentDescription("Show step details"),
                        )
                    },
                )
            },
        ).build()
    }
