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

import androidx.compose.remote.core.RemoteClock
import androidx.compose.remote.core.RemoteComposeState
import androidx.compose.remote.core.SystemClock
import androidx.compose.remote.core.operations.BitmapData
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * The desktop/JVM draw [androidx.compose.remote.core.RemoteContext] — the jvm counterpart of
 * `remote-player-core`'s `AndroidRemoteContext` for the pixel path.
 *
 * `AndroidRemoteContext` "is barely an Android class" (`PROVENANCE.md`): of the 42 `RemoteContext`
 * members, all but **one** are the platform-neutral variable/state store that
 * [StoreBackedRemoteContext] already reimplements. The sole platform-bound member is [loadBitmap] —
 * decoding a document's encoded bytes to a raster — so this class is exactly that one override over
 * skiko, plus the store it inherits.
 *
 * Where [StoreBackedRemoteContext] leaves [loadBitmap] a no-op (its subclass [GraphContext]
 * evaluates values and never paints), the *drawing* context must actually decode: `resolveBitmap`
 * drives `BitmapData.apply(context)` → `context.loadBitmap(...)` and then reads the decoded image
 * back out of the store under the same id. On Android that cached value is an
 * `android.graphics.Bitmap`; here it is a Compose [ImageBitmap] decoded through
 * `org.jetbrains.skia.Image`, which is the type the jvm half of the image seam
 * (`RcPlayerImagePlatform`'s `resolveImage`) will read back — the store is untyped (`putObject` /
 * `cacheData` take `Any`), so the two platforms cache different concrete types behind the same id
 * and each reads its own.
 *
 * ## Coverage and limits
 *
 * - **Encoding.** Only [BitmapData.ENCODING_INLINE] carries bytes to decode; `ENCODING_URL` /
 *   `ENCODING_FILE` are host-fetched (the pluggable image loader's job, deferred) and
 *   `ENCODING_EMPTY` has nothing, so all three are a no-op here.
 * - **PNG types** (`TYPE_PNG`, `TYPE_PNG_8888`, `TYPE_PNG_ALPHA_8`) decode through
 *   `Image.makeFromEncoded`. `TYPE_PNG_ALPHA_8` is an alpha-only mask on Android; skiko decodes it
 *   to RGBA like any other PNG, a parity nuance for documents that use it as a tint mask.
 * - **Raw types** (`TYPE_RAW8888` straight RGBA, `TYPE_RAW8` single-channel alpha) build a raster
 *   directly from the pixel bytes. Buffers too short for the declared `width × height × stride` are
 *   rejected rather than read out of bounds.
 * - A decode that throws (malformed/truncated bytes, an unsupported type) is swallowed to a null,
 *   so a bad image leaves a blank rather than failing the whole render — mirroring the Android
 *   loader's resilience and the text seam's never-throw contract.
 */
internal class JvmRemoteContext(
    state: RemoteComposeState = SnapshotRemoteComposeState(),
    clock: RemoteClock = SystemClock(),
) : StoreBackedRemoteContext(clock) {

    init {
        mRemoteComposeState = state
    }

    override fun loadBitmap(
        imageId: Int,
        encoding: Short,
        type: Short,
        width: Int,
        height: Int,
        data: ByteArray,
    ) {
        // Only inline bytes are decodable here; URL/FILE are the (deferred) host image loader's job
        // and EMPTY has nothing. Caching under the id is what a later `resolveImage` reads back.
        if (encoding != BitmapData.ENCODING_INLINE) return
        val image = decode(type, width, height, data) ?: return
        mRemoteComposeState.cacheData(imageId, image)
    }

    private fun decode(type: Short, width: Int, height: Int, data: ByteArray): ImageBitmap? =
        try {
            when (type) {
                BitmapData.TYPE_PNG,
                BitmapData.TYPE_PNG_8888,
                BitmapData.TYPE_PNG_ALPHA_8 -> Image.makeFromEncoded(data).toComposeImageBitmap()
                BitmapData.TYPE_RAW8888 ->
                    raster(data, width, height, rowBytes = width * 4, ColorType.RGBA_8888)
                BitmapData.TYPE_RAW8 ->
                    raster(data, width, height, rowBytes = width, ColorType.ALPHA_8)
                else -> null
            }
        } catch (e: Exception) {
            // A malformed/undersized buffer must not crash the render — skiko throws an ordinary
            // exception on bytes it can't decode. Deliberately NOT Throwable: a LinkageError (skiko
            // mispackaged) or OutOfMemoryError is a real deployment/runtime failure that must stay
            // visible rather than be hidden behind a blank image.
            null
        }

    /** A raster [ImageBitmap] over straight (unpremultiplied) pixel bytes; null if the buffer is short. */
    private fun raster(
        data: ByteArray,
        width: Int,
        height: Int,
        rowBytes: Int,
        colorType: ColorType,
    ): ImageBitmap? {
        if (width <= 0 || height <= 0 || data.size < rowBytes * height) return null
        val info = ImageInfo(width, height, colorType, ColorAlphaType.UNPREMUL)
        return Image.makeRaster(info, data, rowBytes).toComposeImageBitmap()
    }
}
