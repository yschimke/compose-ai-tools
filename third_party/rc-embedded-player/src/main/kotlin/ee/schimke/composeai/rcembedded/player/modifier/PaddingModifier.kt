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

import androidx.compose.foundation.layout.padding
import androidx.compose.remote.core.operations.layout.modifiers.PaddingModifierOperation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.rcembedded.player.paddingRawEdges
import ee.schimke.composeai.rcembedded.player.state.rememberRemoteFloatAsState

@Composable
internal fun Modifier.padding(op: PaddingModifierOperation): Modifier {
  // Padding values arrive in pixels (authoring stores RemoteDp via toPx()), so convert back to dp
  // by dividing by density — consistent with WidthModifier/BorderModifier/OffsetModifier. Without
  // this the padding was ~density× too large, shrinking the content so FILL children collapsed.
  //
  // Read from the SOURCE fields, not the `getLeft()`..`getBottom()` accessors. Those return the
  // `…Value` fields, and `updateVariables` writes those by scaling the source by the display
  // density — on a value that is already in pixels. Measured on a real document, one edge of
  // `RemoteCompactButton`'s 8dp inset:
  //
  //   density 1.0   mTop = 8    mTopValue = 8     (×1 hides it)
  //   density 2.0   mTop = 16   mTopValue = 32    (×2 again)
  //
  // Dividing the resolved field by density therefore left DOUBLE the intended inset. The compact
  // button is a 48dp touch target minus that inset around a 32dp pill, so it came out
  // 96 − 32 − 32 = 32px where 64px was meant: right at density 1.0, half at 2.0, and nothing at
  // all by 3.0, where 144 − 72 − 72 leaves zero (yschimke/wear-m3-catalog#90).
  //
  // Same shape as the rounded-clip doubling (#4710) and the same reason it hid: at density 1.0 the
  // extra multiply is the identity, and density 1.0 is where the tests ran.
  val density = LocalDensity.current.density
  val raw = paddingRawEdges(op)
  val left = rememberRemoteFloatAsState(raw[0]).value
  val top = rememberRemoteFloatAsState(raw[1]).value
  val right = rememberRemoteFloatAsState(raw[2]).value
  val bottom = rememberRemoteFloatAsState(raw[3]).value

  return this.padding(
    start = (left / density).dp,
    top = (top / density).dp,
    end = (right / density).dp,
    bottom = (bottom / density).dp,
  )
}
