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

import androidx.compose.animation.core.Easing as ComposeEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.remote.core.operations.utilities.easing.Easing as RemoteEasing

/*
 * Split out of `RcPlayer.kt`, which is Android-coupled (`SuppressLint`, `PendingIntent`). This
 * mapping is neither: it turns a core easing constant into a Compose one, and both sides are
 * platform-neutral.
 *
 * Its only caller is `state/RcPlayerExpression.kt`'s animation path, so leaving it in `RcPlayer.kt`
 * meant the whole expression evaluator inherited that file's Android coupling for a six-line `when`.
 * Extracting it is what lets `RcPlayerExpression.kt` — and with it `RcPlayerState.kt` — compile for
 * the jvm target. See the CMP section of PROVENANCE.md.
 */

internal fun mapEasing(type: Int): ComposeEasing {
  return when (type) {
    RemoteEasing.CUBIC_LINEAR -> LinearEasing
    RemoteEasing.CUBIC_STANDARD -> FastOutSlowInEasing
    RemoteEasing.CUBIC_ACCELERATE -> FastOutLinearInEasing
    RemoteEasing.CUBIC_DECELERATE -> LinearOutSlowInEasing
    else -> LinearEasing
  }
}
