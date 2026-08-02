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

package androidx.compose.remote.player.compose.embedded.modifier

import androidx.compose.remote.core.CoreDocument
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipModifierTest {
  @Test
  fun resolvedCornerMatchesRemoteCoreDensityBehavior() {
    val radius = 26f

    assertEquals(
      radius,
      radius.resolveRadius(
        fallback = 42f,
        minDimension = 84f,
        densityBehavior = CoreDocument.DENSITY_BEHAVIOR_LEGACY,
        density = 2f,
      ),
    )
    assertEquals(
      radius,
      radius.resolveRadius(
        fallback = 42f,
        minDimension = 84f,
        densityBehavior = CoreDocument.DENSITY_BEHAVIOR_PIXELS,
        density = 2f,
      ),
    )
    assertEquals(
      52f,
      radius.resolveRadius(
        fallback = 42f,
        minDimension = 84f,
        densityBehavior = CoreDocument.DENSITY_BEHAVIOR_DP,
        density = 2f,
      ),
    )
  }

  @Test
  fun shapeAppliesDpBehaviorAfterReactiveResolution() {
    val resolvedCorner = mutableStateOf(32f)
    val shape =
      RemoteRoundedClipShape(
        topStart = resolvedCorner,
        topEnd = resolvedCorner,
        bottomEnd = resolvedCorner,
        bottomStart = resolvedCorner,
        densityBehavior = CoreDocument.DENSITY_BEHAVIOR_DP,
      )

    val outline =
      shape.createOutline(
        size = Size(268f, 84f),
        layoutDirection = LayoutDirection.Ltr,
        density = Density(2f),
      )

    assertTrue(outline is Outline.Rounded)
    // remote-core passes 64 px to Android's rounded rect path, which normalizes the two vertical
    // radii to the available 84 px height. Keep that normalization explicit for Compose clipping.
    assertEquals(42f, (outline as Outline.Rounded).roundRect.topLeftCornerRadius.x)
  }
}
