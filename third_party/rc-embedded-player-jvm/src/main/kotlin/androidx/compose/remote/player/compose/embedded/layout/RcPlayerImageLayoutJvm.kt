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

package androidx.compose.remote.player.compose.embedded.layout

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.remote.core.operations.layout.managers.ImageLayout
import androidx.compose.remote.core.operations.utilities.ImageScaling
import androidx.compose.remote.player.compose.embedded.LocalRemoteContext
import androidx.compose.remote.player.compose.embedded.resolveImage
import androidx.compose.remote.player.compose.embedded.state.rememberRemoteFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/*
 * The jvm counterpart of `layout/RcPlayerImageLayout.kt`. The Android original resolves the bitmap
 * through the pluggable `LocalRcImageLoader`, which hands back a framework `Drawable` painted via
 * `DrawablePainter` — both Android-typed. On jvm the document's embedded bitmap is decoded straight
 * to a Compose `ImageBitmap` by the image seam (`resolveImage`, backed by `JvmRemoteContext`), so
 * this paints it with the `ImageBitmap` overload of `Image`. There is no host-pluggable loader off
 * Android yet (that plumbing stays androidMain), so this always renders the document's own bitmap.
 *
 * The scaleType -> ContentScale mapping is verbatim: those are all multiplatform values, so the
 * scaling behaviour matches Android exactly.
 */
@Composable
internal fun RcPlayerImageLayout(layout: ImageLayout, modifier: Modifier) {
    val remoteContext = LocalRemoteContext.current
    val image = resolveImage(remoteContext, layout.bitmapId)
    val alpha by rememberRemoteFloatAsState(layout.alpha)

    Box(modifier = modifier) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale =
                    when (layout.scaleType) {
                        ImageScaling.SCALE_FIT -> ContentScale.Fit
                        ImageScaling.SCALE_CROP -> ContentScale.Crop
                        // No fixed scale-factor plumbing yet; 1:1 (None) is the closest faithful
                        // fallback and avoids crashing. See operation_coverage.md.
                        ImageScaling.SCALE_FIXED_SCALE -> ContentScale.None
                        ImageScaling.SCALE_INSIDE -> ContentScale.Inside
                        ImageScaling.SCALE_NONE -> ContentScale.None
                        ImageScaling.SCALE_FILL_BOUNDS -> ContentScale.FillBounds
                        ImageScaling.SCALE_FILL_HEIGHT -> ContentScale.FillHeight
                        ImageScaling.SCALE_FILL_WIDTH -> ContentScale.FillWidth
                        else -> {
                            println(
                                "Warning: unknown image scaleType ${layout.scaleType}; using Fit"
                            )
                            ContentScale.Fit
                        }
                    },
                alpha = alpha,
            )
        }
    }
}
