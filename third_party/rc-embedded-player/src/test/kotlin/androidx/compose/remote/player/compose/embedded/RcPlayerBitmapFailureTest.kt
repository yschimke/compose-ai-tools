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

import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.Operation
import androidx.compose.remote.core.RemoteContext
import androidx.compose.remote.core.WireBuffer
import androidx.compose.remote.core.operations.BitmapData
import androidx.compose.remote.core.operations.layout.Container
import androidx.compose.remote.player.core.platform.AndroidRemoteContext
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RcPlayerBitmapFailureTest {

  @Test
  fun relativeUriLeavesBitmapSlotEmpty() {
    val context = AndroidRemoteContext()
    val state = SnapshotRemoteComposeState()
    context.mRemoteComposeState = state
    context.putObject(
      IMAGE_ID,
      BitmapData(
        IMAGE_ID,
        BitmapData.TYPE_PNG,
        64,
        BitmapData.ENCODING_URL,
        64,
        "camera/current".toByteArray(),
      ),
    )

    assertNull(resolveBitmap(context, IMAGE_ID))
    // The failed decode is memoized, so another frame remains empty without retrying or throwing.
    assertNull(resolveBitmap(context, IMAGE_ID))
  }

  @Test
  fun nestedRelativeUriIsSkippedDuringSetupTraversal() {
    val context = AndroidRemoteContext()
    val state = SnapshotRemoteComposeState()
    context.mRemoteComposeState = state
    val bitmap = relativeUriBitmap()
    context.putObject(IMAGE_ID, bitmap)
    val operations = arrayListOf<Operation>(TestContainer(arrayListOf(bitmap)))

    CoreDocument().applyOperationsWithoutBitmaps(context, operations)

    assertNull("setup must not decode the nested bitmap", state.getFromId(IMAGE_ID))
  }

  private fun relativeUriBitmap(): BitmapData =
    BitmapData(
      IMAGE_ID,
      BitmapData.TYPE_PNG,
      64,
      BitmapData.ENCODING_URL,
      64,
      "camera/current".toByteArray(),
    )

  private class TestContainer(private val operations: ArrayList<Operation>) :
    Operation(), Container {
    override fun getList(): ArrayList<Operation> = operations

    override fun write(buffer: WireBuffer) = Unit

    override fun apply(context: RemoteContext) = Unit

    override fun deepToString(indent: String): String = "${indent}TestContainer"
  }

  private companion object {
    const val IMAGE_ID = 42
  }
}
