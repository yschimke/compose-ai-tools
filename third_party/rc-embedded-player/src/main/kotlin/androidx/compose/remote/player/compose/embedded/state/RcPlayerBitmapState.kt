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

package androidx.compose.remote.player.compose.embedded.state

import android.graphics.Bitmap
import androidx.compose.remote.player.compose.embedded.LocalCoreDocument
import androidx.compose.remote.player.compose.embedded.LocalRemoteContext
import androidx.compose.remote.player.compose.embedded.resolveBitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember

/*
 * Split out of `RcPlayerState.kt`, which is otherwise platform-neutral. This is the *only*
 * Android-typed reactive resolver in the `state/` package — it names `android.graphics.Bitmap` in
 * its return type and decodes through `resolveBitmap`. Keeping it in its own file is what lets the
 * remaining sixteen `rememberRemote*AsState` helpers move to `jvmCommonMain` under the CMP split
 * (see the "Planned: CMP android/jvm" section of PROVENANCE.md) — those sixteen are referenced by
 * fifteen of the module's otherwise platform-free files, so they are the single largest blocker to
 * a real source-set partition.
 *
 * Not a source delta against upstream in any behavioural sense: the function body is verbatim, only
 * the file it lives in changed.
 */

@Composable
internal fun rememberRemoteBitmapAsState(id: Int): State<Bitmap?> {
  val document = LocalCoreDocument.current
  val remoteContext = LocalRemoteContext.current
  // Lazy decode: an Image component composing here is the "drawn" trigger. Decode once in a keyed
  // remember (the snapshot write happens here, outside the derived read), then track the
  // snapshot-backed data store so a later host swap of the bitmap recomposes — no listener
  // bridge.
  remember(document, id) { resolveBitmap(remoteContext, id) }
  return remember(document, id) {
    derivedStateOf { remoteContext.mRemoteComposeState.getFromId(id) as? Bitmap }
  }
}
