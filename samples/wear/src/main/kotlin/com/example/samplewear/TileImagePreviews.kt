package com.example.samplewear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.CONTENT_SCALE_MODE_FIT
import androidx.wear.protolayout.LayoutElementBuilders.Image
import androidx.wear.protolayout.ResourceBuilders.AndroidImageResourceByResId
import androidx.wear.protolayout.ResourceBuilders.IMAGE_FORMAT_ARGB_8888
import androidx.wear.protolayout.ResourceBuilders.ImageResource
import androidx.wear.protolayout.ResourceBuilders.InlineImageResource
import androidx.wear.protolayout.ResourceBuilders.Resources
import androidx.wear.protolayout.material3.avatarImage
import androidx.wear.protolayout.material3.materialScopeWithResources
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.tiles.tooling.preview.Preview
import androidx.wear.tiles.tooling.preview.TilePreviewData
import androidx.wear.tiles.tooling.preview.TilePreviewHelper
import androidx.wear.tooling.preview.devices.WearDevices
import java.nio.ByteBuffer

/**
 * Wear Tiles image previews, covering how a tile references artwork through the protolayout
 * resource bundle:
 *
 * - [InlineImageTilePreview] — an [InlineImageResource]: raw pixels inside the tile's `Resources`,
 *   self-contained, so it survives bundle IR replay (`renders/<stem>.tileresources`).
 * - [DrawableImageTilePreview] — an [AndroidImageResourceByResId] naming an app drawable, resolved
 *   against the module's merged resources.
 *
 * `TileRenderer` loads resources on a direct executor, so the bitmap is set synchronously during
 * inflate without needing the paused main looper; these previews cover that path.
 */
private const val INLINE_IMAGE_ID = "hero"
private const val DRAWABLE_IMAGE_ID = "watchface"
private const val INLINE_IMAGE_PX = 96

/**
 * A small concentric-ring bitmap as the raw ARGB_8888 bytes an [InlineImageResource] carries
 * (`width * height * 4`, as `DefaultInlineImageResourceResolver` asserts).
 */
private fun heroImageBytes(): ByteArray {
  val bitmap = Bitmap.createBitmap(INLINE_IMAGE_PX, INLINE_IMAGE_PX, Bitmap.Config.ARGB_8888)
  val canvas = Canvas(bitmap)
  canvas.drawColor(Color.parseColor("#0B3D2E"))
  val paint = Paint(Paint.ANTI_ALIAS_FLAG)
  val center = INLINE_IMAGE_PX / 2f
  paint.color = Color.parseColor("#F4B400")
  canvas.drawCircle(center, center, INLINE_IMAGE_PX * 0.38f, paint)
  paint.color = Color.parseColor("#0F9D58")
  canvas.drawCircle(center, center, INLINE_IMAGE_PX * 0.22f, paint)
  paint.color = Color.WHITE
  canvas.drawCircle(center, center, INLINE_IMAGE_PX * 0.09f, paint)
  val buffer = ByteBuffer.allocate(bitmap.byteCount)
  bitmap.copyPixelsToBuffer(buffer)
  return buffer.array()
}

/** The [heroImageBytes] pixels wrapped as a self-contained inline [ImageResource]. */
private fun heroImageResource(): ImageResource =
  ImageResource.Builder()
    .setInlineResource(
      InlineImageResource.Builder()
        .setData(heroImageBytes())
        .setWidthPx(INLINE_IMAGE_PX)
        .setHeightPx(INLINE_IMAGE_PX)
        .setFormat(IMAGE_FORMAT_ARGB_8888)
        .build()
    )
    .build()

/**
 * Centres an `Image` of [imageId] on the watchface; variants differ only in how
 * `onTileResourceRequest` backs the id. Uses the deprecated (still supported) `setResourceId`
 * mapping the Wear Tiles docs show, rather than `ProtoLayoutScope` plumbing.
 */
@Suppress("DEPRECATION")
private fun imageTile(imageId: String, sizeDp: Float) =
  TilePreviewHelper.singleTimelineEntryTileBuilder(
      Box.Builder()
        .setWidth(expand())
        .setHeight(expand())
        .addContent(
          Image.Builder()
            .setResourceId(imageId)
            .setWidth(dp(sizeDp))
            .setHeight(dp(sizeDp))
            .setContentScaleMode(CONTENT_SCALE_MODE_FIT)
            .build()
        )
        .build()
    )
    .build()

/** Inline image tile: the artwork travels as raw bytes and replays intact from a bundle. */
@Preview(device = WearDevices.LARGE_ROUND, name = "Inline Image")
fun InlineImageTilePreview(context: Context): TilePreviewData =
  TilePreviewData(
    onTileResourceRequest = {
      Resources.Builder()
        .setVersion("1")
        .addIdToImageMapping(INLINE_IMAGE_ID, heroImageResource())
        .build()
    },
    onTileRequest = { imageTile(INLINE_IMAGE_ID, INLINE_IMAGE_PX.toFloat()) },
  )

/** Drawable-by-resource-id image tile, the path real tiles use for bundled icons. */
@Preview(device = WearDevices.LARGE_ROUND, name = "Drawable Image")
fun DrawableImageTilePreview(context: Context): TilePreviewData =
  TilePreviewData(
    onTileResourceRequest = {
      Resources.Builder()
        .setVersion("1")
        .addIdToImageMapping(
          DRAWABLE_IMAGE_ID,
          ImageResource.Builder()
            .setAndroidResourceByResId(
              AndroidImageResourceByResId.Builder().setResourceId(R.drawable.ic_watchface).build()
            )
            .build(),
        )
        .build()
    },
    onTileRequest = { imageTile(DRAWABLE_IMAGE_ID, 88f) },
  )

/**
 * Scope-registered image tile: the modern `materialScopeWithResources` + `avatarImage` API, which
 * registers the image into the `ProtoLayoutScope`; `TilePreviewComposable` harvests the scope
 * (`mergeScopeResources`) to serve it.
 */
@Preview(device = WearDevices.LARGE_ROUND, name = "Scope Image")
fun ScopeImageTilePreview(context: Context): TilePreviewData = TilePreviewData { request ->
  TilePreviewHelper.singleTimelineEntryTileBuilder(
      materialScopeWithResources(context, request.scope, request.deviceConfiguration) {
        primaryLayout(
          mainSlot = {
            avatarImage(resource = heroImageResource(), width = expand(), height = expand())
          }
        )
      }
    )
    .build()
}
