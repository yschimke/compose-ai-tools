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

package ee.schimke.composeai.rcembedded.player.modifier

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipModifierTest {
  /**
   * The corner arrives from remote-core with the display density already folded in, so the player
   * passes it through whatever the document's density behavior says. This test used to assert the
   * opposite for DP documents — that a 26 corner became 52 at density 2 — which is the bug it now
   * pins down: see [resolveRadius].
   */
  @Test
  fun resolvedCornerIsPassedThroughUnscaled() {
    assertEquals(26f, 26f.resolveRadius(fallback = 42f, minDimension = 84f))
  }

  @Test
  fun nonFiniteCornerFallsBack() {
    assertEquals(42f, Float.NaN.resolveRadius(fallback = 42f, minDimension = 84f))
  }

  @Test
  fun fractionalCornerIsReadAsAProportionOfTheMinDimension() {
    assertEquals(42f, 0.5f.resolveRadius(fallback = 7f, minDimension = 84f))
  }

  /**
   * The regression that motivated the fix, in the shape that actually renders.
   *
   * A 26dp corner on a 195 × 121dp card is `52` px at density 2. It is genuinely smaller than half
   * the box, so [roundedRectRadiusScale] does not clamp it — which is exactly why the old double
   * scaling survived here and was invisible on every stadium-shaped button beside it. Clipping a
   * card to a 104px radius cut the corners off the border its content drew (wear-m3-catalog#89).
   */
  @Test
  fun cardSizedCornerIsNotDoubledAtDensityTwo() {
    val corner = mutableStateOf(52f)
    val shape =
      RemoteRoundedClipShape(
        topStart = corner,
        topEnd = corner,
        bottomEnd = corner,
        bottomStart = corner,
      )

    val outline =
      shape.createOutline(
        size = Size(390f, 242f),
        layoutDirection = LayoutDirection.Ltr,
        density = Density(2f),
      )

    assertTrue(outline is Outline.Rounded)
    assertEquals(52f, (outline as Outline.Rounded).roundRect.topLeftCornerRadius.x)
  }

  /**
   * The clamp that hid the bug, kept explicit. An oversized corner still normalizes to the box, so
   * stadium and circle shapes are unaffected by the fix above.
   */
  @Test
  fun oversizedCornerStillNormalizesToTheBox() {
    val corner = mutableStateOf(64f)
    val shape =
      RemoteRoundedClipShape(
        topStart = corner,
        topEnd = corner,
        bottomEnd = corner,
        bottomStart = corner,
      )

    val outline =
      shape.createOutline(
        size = Size(268f, 84f),
        layoutDirection = LayoutDirection.Ltr,
        density = Density(2f),
      )

    assertTrue(outline is Outline.Rounded)
    assertEquals(42f, (outline as Outline.Rounded).roundRect.topLeftCornerRadius.x)
  }
}
