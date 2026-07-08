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
 * Wear Tiles image previews — the counterpart to [TilePreviews.kt]'s text-only tiles, covering the
 * two ways a tile references artwork through the protolayout resource bundle, neither of which had
 * sample coverage before:
 *
 * - [InlineImageTilePreview] — an [InlineImageResource]: raw pixels shipped *inside* the tile's
 *   `Resources`. Self-contained and portable, so it also survives the bundle IR-replay path
 *   (`renders/<stem>.tileresources`) with no reference back to this function's bytecode.
 * - [DrawableImageTilePreview] — an [AndroidImageResourceByResId]: the tile names an app drawable
 *   (`R.drawable.ic_watchface`) and the renderer resolves it against the module's merged resources,
 *   the shape a real tile uses for icons.
 *
 * Both drive the `Image` element that `TilePreviewComposable` (renderer-android) inflates via
 * `TileRenderer`. `TileRenderer` wires a direct executor for resource loading, so the drawable
 * future is already resolved when `inflateAsync()` returns and the bitmap is set synchronously
 * during inflate — no async hop onto the (Robolectric-paused) main looper. These previews are the
 * regression coverage for that path.
 */
private const val INLINE_IMAGE_ID = "hero"
private const val DRAWABLE_IMAGE_ID = "watchface"
private const val INLINE_IMAGE_PX = 96

/**
 * Paints a small, recognisable device-independent bitmap (concentric rings) so the rendered PNG is
 * unambiguously "an image" rather than a flat colour block, and extracts it as the raw ARGB_8888
 * bytes an [InlineImageResource] carries. `copyPixelsToBuffer` emits exactly `width * height * 4`
 * bytes, matching the size the renderer's `DefaultInlineImageResourceResolver` asserts for
 * `IMAGE_FORMAT_ARGB_8888`.
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
 * Centres an `Image` of [imageId] on the watchface substrate. Shared by both variants so the only
 * thing that differs is how the resource id is backed in `onTileResourceRequest`.
 *
 * Uses the explicit `setResourceId` + `onTileResourceRequest` resource-mapping shape — the form the
 * Wear Tiles docs and samples use, and the clearer one to read here. protolayout 1.4 deprecates it
 * in favour of the `ProtoLayoutScope` `Image.Builder(scope).setImageResource(...)` auto-collection
 * API; the deprecated path is still fully supported by `TileRenderer`, so we suppress rather than
 * pull scope plumbing into a preview fixture.
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

/**
 * Inline (self-contained) image tile — the artwork travels as raw bytes in the tile's `Resources`,
 * so nothing outside this preview is needed to render it and it replays intact from a bundle.
 */
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

/**
 * Drawable-by-resource-id image tile — the tile names an app drawable and the renderer resolves it
 * against the module's merged resources, exercising the `AndroidImageResourceByResId` path a real
 * tile uses for bundled icons.
 */
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
 * Scope-registered image tile — the modern protolayout image API (`materialScopeWithResources` +
 * `avatarImage`) that real Wear tiles use. The image is registered into the `TileRequest`'s
 * `ProtoLayoutScope` during `onTileRequest` rather than through an `onTileResourceRequest` map, so
 * `TilePreviewComposable` has to harvest the scope (see `mergeScopeResources`) to serve it. Before
 * that harvest this rendered blank — the exact reason the wear-os-samples contact avatars
 * (`avatarImage` + `materialScopeWithResources`) came out empty.
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
