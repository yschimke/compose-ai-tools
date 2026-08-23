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

// WRITTEN HERE (compose-ai-tools), not vendored — the Android half of the path seam.
//
// `RcPlayerDrawing.kt` is compiled into both this module and the jvm sibling, and it needs
// `RemoteComposeState.getPath` / `getTweenPath`. The two targets need *different* implementations:
// upstream's Android version reaches `(path as AndroidPath).internalPath.conicTo(...)` behind an
// SDK-34 gate, which a `kotlin("jvm")` module cannot call, so the jvm side vendors a copy routing
// CONIC through skiko instead (see the jvm module's `utils/FloatsToPath.kt`).
//
// That per-target split used to be arranged by *package squatting*: the jvm copy declared itself in
// `androidx.compose.remote.player.compose.utils`, so the one import string in the shared source
// resolved to upstream's AAR on Android and to the vendored copy on jvm. It worked, but it made
// which-code-runs a property of the classpath rather than of the build — the same failure mode that
// took `remote-m3`'s render lane down when upstream started publishing the embedded player into the
// package this module vendors into (#4464).
//
// So the seam is explicit now: both targets import
// `ee.schimke.composeai.rcembedded.player.utils.getPath`, and each module supplies it — the jvm one
// from its adapted copy, this one by forwarding to upstream right here. Nothing about the Android
// rendering path changes; it is the same upstream function it always called.
package ee.schimke.composeai.rcembedded.player.utils

import androidx.compose.remote.core.RemoteComposeState
import androidx.compose.remote.player.compose.utils.getPath as upstreamGetPath
import androidx.compose.remote.player.compose.utils.getTweenPath as upstreamGetTweenPath
import androidx.compose.ui.graphics.Path

/** Android: upstream's `remote-player-compose` implementation, unchanged. */
public fun RemoteComposeState.getPath(id: Int, start: Float, end: Float): Path =
  upstreamGetPath(id, start, end)

/** Android: upstream's `remote-player-compose` implementation, unchanged. */
public fun RemoteComposeState.getTweenPath(
  path1Id: Int,
  path2Id: Int,
  tween: Float,
  start: Float,
  end: Float,
): Path = upstreamGetTweenPath(path1Id, path2Id, tween, start, end)
