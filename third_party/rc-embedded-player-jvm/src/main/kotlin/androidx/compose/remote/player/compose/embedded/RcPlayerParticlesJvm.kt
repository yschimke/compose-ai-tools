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
import androidx.compose.remote.core.operations.ParticlesCompare
import androidx.compose.remote.core.operations.ParticlesLoop
import androidx.compose.ui.graphics.drawscope.DrawScope

/*
 * The jvm counterpart of `RcPlayerParticles.kt` — a no-op for now.
 *
 * The Android side replays particle ops through the View player's core `AndroidPaintContext`, bound
 * to the Compose draw pass's native canvas. That core paint pipeline is the one substantial piece of
 * the player that has no multiplatform form (1510 lines, reached only from here), so — as the
 * sequencing in `PROVENANCE.md` records — particles are deliberately deferred rather than forcing a
 * Skia `PaintContext` port. A document with no particles is wholly unaffected; one with a particle
 * loop renders everything else and simply omits the particles, the documented parity limit.
 */

/** No desktop particle pipeline yet — omit the particle loop rather than port a Skia PaintContext. */
internal fun DrawScope.drawParticles(
    @Suppress("UNUSED_PARAMETER") loop: ParticlesLoop,
    @Suppress("UNUSED_PARAMETER") remoteContext: RemoteContext,
    @Suppress("UNUSED_PARAMETER") paintState: ComposeLocalPaint,
    @Suppress("UNUSED_PARAMETER") graph: GraphContext,
) {}

/** No desktop particle pipeline yet — the interaction pass is likewise omitted. */
internal fun DrawScope.drawParticlesCompare(
    @Suppress("UNUSED_PARAMETER") op: ParticlesCompare,
    @Suppress("UNUSED_PARAMETER") remoteContext: RemoteContext,
    @Suppress("UNUSED_PARAMETER") paintState: ComposeLocalPaint,
    @Suppress("UNUSED_PARAMETER") graph: GraphContext,
) {}
