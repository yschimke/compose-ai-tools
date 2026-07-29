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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.remote.player.compose.embedded.ExperimentalRemoteDocumentPlayer
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The player's frame loop must not stop the composition reaching idle.
 *
 * `RcPlayer` drives time from a `LaunchedEffect` that never returns for an animated or time-driven
 * document. Requested through `withFrameMillis` that is indistinguishable from ordinary pending
 * work, so `waitForIdle()` blocks forever and every wait-for-idle capture API times out — which is
 * why the render harnesses drive `mainClock` by hand and draw the view directly. Requested through
 * `withInfiniteAnimationFrameMillis` it goes via the `InfiniteAnimationPolicy` the test framework
 * installs, and idle is reachable.
 *
 * This asserts the property directly: compose a real document and call `waitForIdle()`. If the loop
 * regresses to `withFrameMillis` this test hangs and the suite times out rather than failing
 * cleanly — a loud failure either way, which is the point.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "xhdpi")
class RcIdleProbeTest {

  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Test(timeout = 120_000)
  fun compositionReachesIdleWithThePlayerRunning() {
    val bytes =
      checkNotNull(javaClass.getResourceAsStream("/rc-fixtures/TitleCardRemote-640x480.rc"))
        .use { it.readBytes() }

    composeRule.setContent {
      Box(
        Modifier.size(
          with(LocalDensity.current) { 640.toDp() },
          with(LocalDensity.current) { 480.toDp() },
        )
      ) {
        ExperimentalRemoteDocumentPlayer(
          document = RemoteDocument(bytes),
          modifier = Modifier.fillMaxSize(),
        )
      }
    }

    // The assertion is that this returns at all.
    composeRule.waitForIdle()
  }
}
