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

@file:Suppress("RestrictedApiAndroidX")

package androidx.compose.remote.player.compose.embedded

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.remote.core.RemoteContext
import androidx.compose.remote.core.operations.BitmapData
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/*
 * Every place the canvas draw path reaches for `android.graphics.Bitmap`, gathered behind three
 * functions so the rest of the draw path doesn't have to name the platform.
 *
 * This is a *seam*, not a port — the same shape as `RcPlayerTextPlatform.kt`. The bodies below are
 * the bitmap decode / lookup / offscreen-target logic that lived inline in `RcPlayerDrawing.kt`
 * (`resolveBitmap`, `resolveCanvasBitmap`, and the `DrawToBitmap` mutable-copy dance), moved here
 * unchanged. Android decodes and blits exactly as it did; what changes is that `RcPlayerDrawing.kt`
 * and `RcPlayerPaint.kt` no longer mention `android.graphics`, so a jvm sibling of *this file alone*
 * (over skiko `org.jetbrains.skia.Image` + a jvm draw context that decodes in `loadBitmap`) is what
 * the image half of the draw path needs to run off Android.
 *
 * The seam exposes Compose's multiplatform [ImageBitmap] rather than `android.graphics.Bitmap`: the
 * blit ops already converted with `asImageBitmap()` at the call site, and the paint decoder's
 * `TEXTURE` path already wrapped the result in an `ImageShader` — so returning [ImageBitmap] is the
 * type they wanted anyway, and the framework `Bitmap` stays private to this file. The offscreen
 * target ([prepareOffscreenTarget]) keeps its framework `Bitmap` mutation here too, handing back the
 * [ImageBitmap] view the caller wraps in a Compose `Canvas`.
 *
 * Deliberately not seamed here: the pluggable host [RcImageLoader] and its `Drawable` plumbing
 * (`RcImageLoader.kt` / `DrawablePainter.kt`) stay Android-only — a jvm host image loader is its own
 * follow-up. [resolveCanvasImage] reads the loader only for the `BitmapDrawable` fast path, exactly
 * as before, and falls back to the embedded decode otherwise.
 */

/**
 * The decoded framework [Bitmap] for [id], decoding it on first use (lazy bitmap loading).
 *
 * The embedded player registers each [BitmapData]'s metadata at setup (via `putObject`, so declared
 * width/height stay available to `ImageAttribute` without a decode) but defers the costly pixel
 * decode until the bitmap is actually needed — when it is drawn, or when its Image component first
 * composes. The decoded bitmap is cached in the state's data map (`getFromId`), so later lookups
 * are cheap. Returns null if there is no bitmap or metadata for the id.
 *
 * The draw path takes the [ImageBitmap] projections below; the framework [Bitmap] itself is still
 * needed by the Android-only surfaces that have no portable equivalent yet — the AGSL
 * `BitmapShader` ([RcPlayerShaders]), the reactive [rememberRemoteBitmapAsState], and the host
 * [RcImageLoader] — so this stays `internal` rather than private to the seam.
 */
internal fun resolveBitmap(remoteContext: RemoteContext, id: Int): Bitmap? {
  val cached = remoteContext.mRemoteComposeState.getFromId(id)
  if (cached is Bitmap) return cached
  // Not decoded yet: find the registered BitmapData and decode it now (apply = putObject +
  // loadBitmap, which caches the decoded Bitmap under the id).
  val data = remoteContext.mRemoteComposeState.getObject(id) as? BitmapData ?: return null
  data.apply(remoteContext)
  return remoteContext.mRemoteComposeState.getFromId(id) as? Bitmap
}

/** [resolveBitmap] projected to a Compose [ImageBitmap]; null when there is no bitmap. */
internal fun resolveImage(remoteContext: RemoteContext, id: Int): ImageBitmap? =
  resolveBitmap(remoteContext, id)?.asImageBitmap()

/**
 * Resolves a document image draw to an [ImageBitmap] through the pluggable [RcImageLoader] (on
 * [graph]), falling back to the embedded decode ([resolveImage]). Reading the loader's `State` here
 * registers the draw as an observer, so a host's asynchronously-loaded image re-runs the draw when
 * it arrives.
 *
 * The canvas blit ops need pixels (for src/dst sub-rect blitting), so only a [BitmapDrawable] from
 * the loader is used directly; any other host [android.graphics.drawable.Drawable] falls back to
 * the embedded bitmap. (The composable Image layout, by contrast, can render any Drawable.)
 */
internal fun resolveCanvasImage(
  graph: GraphContext?,
  remoteContext: RemoteContext,
  id: Int,
): ImageBitmap? {
  val loaded = (graph?.imageLoader as? RcImageLoader)?.loadImage(id)?.value
  if (loaded is BitmapDrawable) return loaded.bitmap.asImageBitmap()
  return resolveImage(remoteContext, id)
}

/**
 * Prepare the offscreen render target for `DrawToBitmap` and return the [ImageBitmap] the caller
 * points a Compose `Canvas` at. Returns null when the target id has no bitmap (nothing to redirect
 * onto — the caller leaves the on-screen canvas in place).
 *
 * Decoded document bitmaps are immutable, so an immutable target is copied into a mutable ARGB_8888
 * bitmap and stored back under the same id, so a later `DrawBitmap` of this id reads the rendered
 * content. When [initialize] is true the target is erased to [color] first (`MODE_NO_INITIALIZE`
 * clears it). This is the same framework `Bitmap` dance the op did inline; only the
 * `asImageBitmap()` view crosses back out.
 */
internal fun prepareOffscreenTarget(
  remoteContext: RemoteContext,
  bitmapId: Int,
  color: Int,
  initialize: Boolean,
): ImageBitmap? {
  val stored = resolveBitmap(remoteContext, bitmapId) ?: return null
  val target =
    if (stored.isMutable) {
      stored
    } else {
      stored.copy(Bitmap.Config.ARGB_8888, true).also {
        remoteContext.mRemoteComposeState.cacheData(bitmapId, it)
      }
    }
  if (initialize) {
    target.eraseColor(color)
  }
  return target.asImageBitmap()
}
