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

// VENDORED + adapted (compose-ai-tools) — from `androidx.compose.remote:remote-player-compose`
// 1.0.0-alpha15 (`-sources.jar`), `player-compose/.../utils/FloatsToPath.kt`. The command walk
// (MOVE/LINE/QUADRATIC/CUBIC/CLOSE + the start/stop trim via `PathMeasure`) is **verbatim** — the
// same Compose `Path` calls, so the geometry is identical to Android. Two Android-only lines are
// swapped for their neutral equivalents, and only those:
//   * CONIC — upstream reaches `(path as AndroidPath).internalPath.conicTo(...)`, guarded by
//     `Build.VERSION.SDK_INT >= 34`, because `androidx.compose.ui.graphics.Path` exposes no conic
//     (upstream TODO b/434130226). Desktop skiko's `org.jetbrains.skia.Path` DOES, so we call it
//     through `asSkiaPath()` — no SDK gate. A parity note, not a divergence: on Android below API 34
//     the op is dropped entirely, so the jvm side is if anything more faithful to the document.
//   * the odd-command branch logged via `android.util.Log`; here it is a silent no-op (the walk
//     already tolerates unknown commands by skipping the word).
// Kept in the original package so the vendored `PathUtils.kt` beside it resolves `FloatsToPath`
// unchanged. See the jvm module build.gradle.kts header and `.../PROVENANCE.md`.
package androidx.compose.remote.player.compose.utils

import androidx.compose.remote.core.operations.PathData
import androidx.compose.remote.core.operations.Utils.idFromNan
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.asSkiaPath
import kotlin.math.max
import kotlin.math.min

/** Utility class to convert a float array representation of a path into a Compose [Path] object. */
internal object FloatsToPath {

    /**
     * Converts a float array representing a path into a Path object.
     *
     * @param retPath The Path object to populate with the converted path data.
     * @param floatPath The float array representing the path.
     * @param start The starting percentage (0.0 to 1.0) of the path to include.
     * @param stop The ending percentage (0.0 to 1.0) of the path to include.
     */
    fun genPath(retPath: Path, floatPath: FloatArray, start: Float, stop: Float) {
        var i = 0
        val path = Path() // todo this should be cached for performance
        while (i < floatPath.size) {
            when (idFromNan(floatPath[i])) {
                PathData.MOVE -> {
                    i++
                    path.moveTo(floatPath[i + 0], floatPath[i + 1])
                    i += 2
                }

                PathData.LINE -> {
                    i += 3
                    path.lineTo(floatPath[i + 0], floatPath[i + 1])
                    i += 2
                }

                PathData.QUADRATIC -> {
                    i += 3
                    path.quadraticTo(
                        floatPath[i + 0],
                        floatPath[i + 1],
                        floatPath[i + 2],
                        floatPath[i + 3],
                    )
                    i += 4
                }

                PathData.CONIC -> {
                    i += 3
                    // Upstream guards this on `Build.VERSION.SDK_INT >= 34` and reaches
                    // AndroidPath.internalPath; skiko's Path exposes conicTo directly, so call it
                    // through the desktop backing path (no SDK gate). See the file header.
                    //
                    // Skiko m144 (CMP 1.11, skiko 0.144.6) deprecated the mutating `Path` API at
                    // DeprecationLevel.ERROR in favour of `PathBuilder` — the same API move that
                    // introduced `org.jetbrains.skia.PathBuilder`. It is a SOURCE-level deprecation
                    // only: the native still exports `Java_org_jetbrains_skia_PathKt__1nConicTo`, so
                    // the call is as correct at runtime as it was before. Suppressed rather than
                    // migrated because this walk mutates one `Path` incrementally across the whole
                    // command stream, while `PathBuilder` is a build-then-`snapshot()` type —
                    // porting it would restructure a block whose whole value is being *verbatim*
                    // upstream. Revisit when upstream `remote-player-compose` moves, so this file
                    // keeps tracking it line-for-line.
                    @Suppress("DEPRECATION_ERROR")
                    path.asSkiaPath()
                        .conicTo(
                            floatPath[i + 0],
                            floatPath[i + 1],
                            floatPath[i + 2],
                            floatPath[i + 3],
                            floatPath[i + 4],
                        )
                    i += 5
                }

                PathData.CUBIC -> {
                    i += 3
                    path.cubicTo(
                        floatPath[i + 0],
                        floatPath[i + 1],
                        floatPath[i + 2],
                        floatPath[i + 3],
                        floatPath[i + 4],
                        floatPath[i + 5],
                    )
                    i += 6
                }

                PathData.CLOSE -> {
                    path.close()
                    i++
                }

                PathData.DONE -> i++
                // Odd command — tolerate it by skipping the word (upstream logs via android.util.Log).
                else -> i++
            }
        }

        retPath.reset()
        if (start > 0f || stop < 1f) {
            if (start < stop) {
                val measure: PathMeasure = PathMeasure() // todo cached
                measure.setPath(path, false)
                val len: Float = measure.length
                val scaleStart = (max(start.toDouble(), 0.0) * len).toFloat()
                val scaleStop = (min(stop.toDouble(), 1.0) * len).toFloat()
                measure.getSegment(scaleStart, scaleStop, retPath, true)
            }
        } else {
            retPath.addPath(path)
        }
    }
}
