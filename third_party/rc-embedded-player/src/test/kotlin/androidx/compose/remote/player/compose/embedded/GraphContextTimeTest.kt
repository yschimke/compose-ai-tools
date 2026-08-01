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

import androidx.compose.remote.core.RemoteClock
import androidx.compose.remote.core.RemoteContext
import androidx.compose.runtime.mutableFloatStateOf
import org.junit.Assert.assertEquals
import org.junit.Test

class GraphContextTimeTest {
  @Test
  fun continuousSecondsTracksTheComposeFrameClock() {
    val frameTimeMillis = mutableFloatStateOf(1_750f)
    val context =
      GraphContext(
        realState = SnapshotRemoteComposeState(),
        computedOps = emptyMap(),
        timeMillis = frameTimeMillis,
        clock = RemoteClock.SYSTEM,
      )

    assertEquals(1.75f, context.getFloat(RemoteContext.ID_CONTINUOUS_SEC), 0.0001f)

    frameTimeMillis.floatValue = 2_500f

    assertEquals(2.5f, context.getFloat(RemoteContext.ID_CONTINUOUS_SEC), 0.0001f)
  }
}
