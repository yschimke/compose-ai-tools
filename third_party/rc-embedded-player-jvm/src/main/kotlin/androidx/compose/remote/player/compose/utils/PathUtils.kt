/*
 * Copyright 2025 The Android Open Source Project
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

// VENDORED (compose-ai-tools) — verbatim from `androidx.compose.remote:remote-player-compose`
// 1.0.0-alpha15 (`-sources.jar`), path `player-compose/.../utils/PathUtils.kt`. See the jvm module's
// build.gradle.kts header and `third_party/rc-embedded-player/PROVENANCE.md`. The embedded draw path
// (`RcPlayerDrawing.kt`) calls these two `RemoteComposeState` extensions to turn core `PathData` into
// a Compose `Path`; on Android they come from the `remote-player-compose` AAR, which a `kotlin("jvm")`
// module cannot consume — so the neutral source is vendored here for the jvm target. Body unchanged;
// its one Android-coupled callee, `FloatsToPath`, is vendored alongside with the conic op swapped to
// skiko (see `FloatsToPath.kt`).
package androidx.compose.remote.player.compose.utils

import androidx.annotation.RestrictTo
import androidx.compose.remote.core.RemoteComposeState
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public fun RemoteComposeState.getPath(id: Int, start: Float, end: Float): Path {
    val winding: Int = getPathWinding(id)
    val path = Path()
    val pathData: FloatArray? = getPathData(id)
    if (pathData != null) {
        FloatsToPath.genPath(path, pathData, start, end)
        if (winding == 1) {
            path.fillType = PathFillType.EvenOdd
        }
    }
    return path
}

/**
 * Interpolated path between [path1Id] and [path2Id] by [tween] in [0,1], trimmed to [start, end].
 * Mirrors the core `DrawTweenPath` interpolation (the View player's `getPath(path1, path2, tween,
 * start, end)`): at the endpoints it uses the source data verbatim; in between it linearly
 * interpolates each command word (preserving NaN command markers from path 1). Returns an empty
 * path if either path's data is missing.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public fun RemoteComposeState.getTweenPath(
    path1Id: Int,
    path2Id: Int,
    tween: Float,
    start: Float,
    end: Float,
): Path {
    val data1 = getPathData(path1Id)
    val data2 = getPathData(path2Id)
    val tmp =
        when {
            tween <= 0f || data2 == null -> data1
            tween >= 1f || data1 == null -> data2
            else ->
                FloatArray(data2.size) { i ->
                    if (data1[i].isNaN() || data2[i].isNaN()) data1[i]
                    else (data2[i] - data1[i]) * tween + data1[i]
                }
        }
    val path = Path()
    if (tmp != null) {
        FloatsToPath.genPath(path, tmp, start, end)
    }
    return path
}
