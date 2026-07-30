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
import androidx.compose.ui.graphics.Shader

/*
 * The jvm counterpart of `RcPlayerShaders.kt`'s two AGSL functions — the desktop half of the shader
 * seam, deliberately a no-op for now (issue #2954).
 *
 * The Android side builds an `android.graphics.RuntimeShader` from the document's AGSL source;
 * desktop Compose exposes SkSL `RuntimeEffect`, a different shading language *and* uniform-binding
 * API, and the port is deferred until the embedded player's shader output is understood against the
 * View player on Android (it already diverges ~89% on `ShaderGradientSticker`). Until then the jvm
 * draw path renders every other paint faithfully and simply omits the runtime-shader brush:
 * [buildRuntimeShader] returns null (the caller falls back to the paint's solid colour) and
 * [applyShaderMatrix] does nothing. A document with no `SHADER` paint is wholly unaffected; one that
 * uses AGSL renders without the shader rather than failing — the documented parity limit.
 */

/** No desktop AGSL yet — returns null so the caller keeps the paint's non-shader brush. */
internal fun buildRuntimeShader(
    @Suppress("UNUSED_PARAMETER") shaderId: Int,
    @Suppress("UNUSED_PARAMETER") remoteContext: RemoteContext,
): Shader? = null

/** No desktop AGSL yet — there is no runtime shader to set a local matrix on, so this is a no-op. */
internal fun applyShaderMatrix(
    @Suppress("UNUSED_PARAMETER") paintState: ComposeLocalPaint,
    @Suppress("UNUSED_PARAMETER") matrixWord: Int,
    @Suppress("UNUSED_PARAMETER") read: RemoteContext,
) {}
