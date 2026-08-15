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

package ee.schimke.composeai.rcembedded

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Why the render harnesses still rasterize with `View.draw(Canvas(bitmap))` instead of
 * `captureToImage()` — and the tripwire for when they no longer have to.
 *
 * [RcIdleProbeTest] pins the half of the story that #2945 fixed: the composition reaches idle, so
 * `waitForIdle()` works and the harnesses no longer drive `mainClock` by hand. The capture half did
 * *not* follow, and the reason has nothing to do with the player. `captureToImage()` goes through
 * `WindowCapture.forceRedraw`, which registers a `ViewTreeObserver.OnDrawListener`, invalidates,
 * and waits 2s for a draw pass. Robolectric never runs one, so the call times out — for **any**
 * content.
 *
 * This composes a 10dp red `Box` with no Remote Compose document anywhere near it and asserts the
 * timeout, so the constraint is attributed to the environment rather than re-blamed on `RcPlayer`
 * the next time someone reads the harnesses.
 *
 * **When this test fails, that is the good outcome**: Robolectric (or `compose-ui-test`) has grown
 * the draw pass, and [RcEmbeddedRenderHarness], [RcViewPlayerRenderHarness] and
 * [RcFigmaSvgExportTest] can drop the manual `measure`/`layout`/`draw` for a `captureToImage()` —
 * with a fresh md5 sweep, since that changes how the reference pixels are produced.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "xhdpi")
class RobolectricCaptureToImageProbeTest {

  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun captureToImageStillCannotDrawUnderRobolectric() {
    composeRule.setContent { Box(Modifier.testTag(TAG).size(10.dp).background(Color.Red)) }
    composeRule.waitForIdle()

    val failure = runCatching { composeRule.onNodeWithTag(TAG).captureToImage() }.exceptionOrNull()

    assert(failure is ComposeTimeoutException) {
      "captureToImage() no longer times out under Robolectric (got ${failure ?: "a real image"}) — " +
        "the render harnesses can stop drawing the view by hand; see this test's KDoc"
    }
  }

  private companion object {
    const val TAG = "probe"
  }
}
