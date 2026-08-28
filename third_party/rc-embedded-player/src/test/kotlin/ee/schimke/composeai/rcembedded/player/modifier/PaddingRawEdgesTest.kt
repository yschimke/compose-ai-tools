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

package ee.schimke.composeai.rcembedded.player.modifier

import androidx.compose.remote.core.operations.layout.modifiers.PaddingModifierOperation
import ee.schimke.composeai.rcembedded.player.paddingRawEdges
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * The padding edges the player reads must be the operation's SOURCE fields, not the `…Value` ones
 * `getLeft()`..`getBottom()` return.
 *
 * `updateVariables` writes the resolved fields by scaling the source by the display density — on a
 * value that is already in pixels. Reading the resolved field and dividing by density therefore
 * left double the intended inset, which is what squashed `RemoteCompactButton` to half height at
 * density 2 and to nothing at all by density 3 (yschimke/wear-m3-catalog#90).
 */
class PaddingRawEdgesTest {
  @Test
  fun readsTheSourceEdgesNotTheDensityScaledOnes() {
    val op = PaddingModifierOperation(1f, 8f, 3f, 4f)
    // What `updateVariables` does at density 2: source × density, written to the resolved fields.
    setResolved(op, 2f, 16f, 6f, 8f)

    assertArrayEquals(floatArrayOf(1f, 8f, 3f, 4f), paddingRawEdges(op), 0f)
  }

  @Test
  fun theGettersReturnTheScaledEdges() {
    // Pins WHY the accessor exists: the public getters are the doubled values, so a reader that
    // uses them and divides by density lands on the source instead of on dp.
    val op = PaddingModifierOperation(1f, 8f, 3f, 4f)
    setResolved(op, 2f, 16f, 6f, 8f)

    assertArrayEquals(
      floatArrayOf(2f, 16f, 6f, 8f),
      floatArrayOf(op.left, op.top, op.right, op.bottom),
      0f,
    )
  }

  private fun setResolved(
    op: PaddingModifierOperation,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
  ) {
    for ((name, value) in
      listOf(
        "mLeftValue" to left,
        "mTopValue" to top,
        "mRightValue" to right,
        "mBottomValue" to bottom,
      )) {
      PaddingModifierOperation::class
        .java
        .getDeclaredField(name)
        .apply { isAccessible = true }
        .setFloat(op, value)
    }
  }
}
