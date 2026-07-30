/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.remote.player.compose.embedded

import androidx.compose.remote.core.RemoteContext
import androidx.compose.remote.core.operations.BitmapData
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint

/*
 * The jvm counterpart of `RcPlayerImagePlatform.kt`'s three draw-path functions.
 *
 * Where the Android seam decodes to an `android.graphics.Bitmap` and projects it with
 * `asImageBitmap()`, the jvm draw context ([JvmRemoteContext.loadBitmap]) already decodes straight to
 * a Compose [ImageBitmap] and caches it under the id — so this reads that back directly. The store is
 * untyped, so each platform caches and reads its own concrete type behind the same id.
 *
 * The Android-only surfaces the seam kept `resolveBitmap` framework-typed for (the AGSL
 * `BitmapShader`, the reactive bitmap state, the `Drawable` host loader) are not on the jvm draw
 * path, so this sibling exposes only the three functions the path actually calls and skips the host
 * image loader entirely in [resolveCanvasImage].
 */

/**
 * The decoded [ImageBitmap] for [id], decoding on first use. Mirrors the Android seam's
 * `resolveImage`: a cached image is returned, otherwise the registered [BitmapData] is applied
 * (which drives [JvmRemoteContext.loadBitmap] to decode + cache an [ImageBitmap]) and the freshly
 * cached value read back. Null when there is no bitmap or metadata for the id.
 */
internal fun resolveImage(remoteContext: RemoteContext, id: Int): ImageBitmap? {
    val cached = remoteContext.mRemoteComposeState.getFromId(id)
    if (cached is ImageBitmap) return cached
    val data = remoteContext.mRemoteComposeState.getObject(id) as? BitmapData ?: return null
    data.apply(remoteContext)
    return remoteContext.mRemoteComposeState.getFromId(id) as? ImageBitmap
}

/**
 * Resolves a document image draw to an [ImageBitmap]. On jvm there is no `Drawable`-typed host image
 * loader (that plumbing stays androidMain), so this is just the embedded decode — the loader fast
 * path the Android seam has for a `BitmapDrawable` has no jvm equivalent yet.
 */
internal fun resolveCanvasImage(
    @Suppress("UNUSED_PARAMETER") graph: GraphContext?,
    remoteContext: RemoteContext,
    id: Int,
): ImageBitmap? = resolveImage(remoteContext, id)

/**
 * Prepare the offscreen render target for `DrawToBitmap` and return the [ImageBitmap] the caller
 * points a Compose `Canvas` at; null when the id has no bitmap.
 *
 * Mirrors the Android seam: the target starts as a copy of the stored image (so a
 * `MODE_NO_INITIALIZE` pass keeps accumulating onto prior content) and is erased to [color] when
 * [initialize] is true. It is cached back under the id so a later `DrawBitmap` reads the rendered
 * content. The mutable framework `Bitmap` the Android seam copies into is, on jvm, a fresh mutable
 * [ImageBitmap] drawn into through a Compose `Canvas`.
 */
internal fun prepareOffscreenTarget(
    remoteContext: RemoteContext,
    bitmapId: Int,
    color: Int,
    initialize: Boolean,
): ImageBitmap? {
    val stored = resolveImage(remoteContext, bitmapId) ?: return null
    val width = stored.width
    val height = stored.height
    if (width <= 0 || height <= 0) return null
    val target = ImageBitmap(width, height)
    val canvas = Canvas(target)
    // Seed the target with the stored content (the Android copy() starts from it), then optionally
    // clear to `color` — BlendMode.Src overwrites rather than compositing, matching `eraseColor`.
    canvas.drawImage(stored, Offset.Zero, Paint())
    if (initialize) {
        canvas.drawRect(
            Rect(0f, 0f, width.toFloat(), height.toFloat()),
            Paint().apply {
                this.color = Color(color)
                blendMode = BlendMode.Src
            },
        )
    }
    remoteContext.mRemoteComposeState.cacheData(bitmapId, target)
    return target
}
