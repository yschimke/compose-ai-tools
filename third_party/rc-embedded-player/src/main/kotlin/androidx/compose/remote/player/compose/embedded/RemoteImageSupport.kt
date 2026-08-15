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

import androidx.compose.remote.core.Limits

/**
 * Let the AndroidX parser accept URL- and file-encoded bitmaps.
 *
 * `Limits.ENABLE_IMAGE_URLS` / `ENABLE_IMAGE_FILES` are public mutable globals in `remote-core`
 * that ship `false`. While they are off, `BitmapData.read` throws `URL image not supported [<id>]`
 * for any `BitmapData` carrying `ENCODING_URL`, and because that read happens inside
 * `inflateFromBuffer` the throw fails the **whole document** rather than just the image. A player
 * or harness handed such a document then produces nothing at all.
 *
 * Enabling the parse is not enabling a fetch: the reference is only resolved if the host supplies a
 * loader, so a caller that passes none simply leaves the image slot empty and draws the rest of the
 * document — which is what the JS and CMP players already do with the same bytes.
 *
 * **Call this before the bytes are parsed, not before they are drawn.** Parsing happens in more
 * than one place, and two of them are constructors: `RemoteDocument(bytes)` decodes inside its own
 * constructor, so a call sited after it has already missed. Every entry point that turns bytes into
 * a `CoreDocument` needs this ahead of it. That is the same rule the Home Assistant catalog follows
 * with its own `enableRemoteImageUrls()`, which it calls from each of its player entry points.
 *
 * Idempotent and cheap — two field writes — so re-asserting per parse is deliberate: the flags are
 * process-global and public, and anything else on the classpath can flip them back.
 */
public fun enableEncodedImageReferences() {
  Limits.ENABLE_IMAGE_URLS = true
  Limits.ENABLE_IMAGE_FILES = true
}
