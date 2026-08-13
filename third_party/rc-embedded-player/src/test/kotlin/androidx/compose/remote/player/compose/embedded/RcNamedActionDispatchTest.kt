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

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.remote.creation.compose.action.lambdaAction
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.clickable
import androidx.compose.remote.creation.compose.modifier.size
import androidx.compose.remote.creation.compose.state.rdp
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the two halves of host-lambda click dispatch, which are only correct together:
 * `captureSingleRemoteDocument` records the host lambda in [CapturedDocument.lambdas] under an id
 * and encodes a `LambdaAction` *named* action into the document, and the player's
 * `LocalRemoteNamedActionHandler` has to parse that id back out and invoke the matching entry.
 *
 * This is a regression test for a real gap rather than a hypothetical one. Both halves used to be
 * absent from this vendored copy: `LambdaAction` did not exist in the published alpha the module
 * builds against and `CapturedDocument` carried no `lambdas`, so the handler forwarded straight to
 * `onNamedAction` and the `CapturedDocument` overload passed nothing through. A click on such a
 * document silently did nothing. Both are restored (see PROVENANCE.md) and nothing else in the
 * module exercises them — the render lanes only rasterize static documents and never fire an
 * action, so a second regression here would not show up as a pixel diff.
 *
 * Deliberately goes through the `CapturedDocument` overload rather than building a [CoreDocument]
 * by hand: the forwarding of `lambdas` / `pendingIntents` *is* half of what's under test, and the
 * `CoreDocument` overload defaults both to empty.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RcNamedActionDispatchTest {
  @get:Rule val rule = createComposeRule()

  @Test
  fun clickInvokesTheHostLambdaCarriedByTheCapturedDocument() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    var clicks = 0

    val captured =
      captureSingleRemoteDocument(
        context = context,
        content = {
          val onClick = lambdaAction { clicks++ }
          RemoteBox(modifier = RemoteModifier.size(120.rdp, 40.rdp).clickable(onClick))
        },
      )

    // The lambda has to survive capture as a document-side id -> host-lambda entry; if it doesn't,
    // the click below can't resolve to anything and the failure would look like a dispatch bug.
    assert(captured.lambdas.isNotEmpty()) {
      "Expected capture to record the host lambda, but CapturedDocument.lambdas was empty"
    }

    rule.setContent { Box(modifier = Modifier.size(200.dp)) { RcPlayer(capturedDocument = captured) } }

    rule.onNode(hasClickAction()).performClick()
    rule.waitForIdle()

    assert(clicks == 1) { "Expected the captured host lambda to run exactly once, but ran $clicks times" }
  }
}
