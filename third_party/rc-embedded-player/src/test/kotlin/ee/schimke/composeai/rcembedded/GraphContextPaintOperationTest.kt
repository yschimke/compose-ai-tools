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

import androidx.compose.remote.core.RemoteClock
import androidx.compose.remote.core.operations.ColorAttribute
import androidx.compose.remote.player.compose.embedded.GraphContext
import androidx.compose.remote.player.compose.embedded.SnapshotRemoteComposeState
import androidx.compose.remote.player.compose.embedded.buildComputedOpIndex
import androidx.compose.runtime.mutableFloatStateOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The embedded player's graph must be able to evaluate an operation that publishes its value from
 * `paint(PaintContext)` rather than from `apply(RemoteContext)`.
 *
 * `ColorAttribute` is that operation: it decomposes a colour into one channel, and it does so in
 * `paint`. `PaintOperation.apply` forwards there only during a real draw pass, so a graph that
 * evaluated ops by calling `apply` got nothing back and resolved the channel to `0` — a colour
 * built on that came out fully transparent, which is why a state-driven tint vanished from this
 * player while the literal one beside it drew (compose-ai-tools#3936, defect 1).
 *
 * Asserted at the graph rather than in pixels on purpose: a render would also pass if the shape
 * happened to land on a same-coloured background, and would say nothing about *which* of the two
 * value channels ran.
 */
class GraphContextPaintOperationTest {

  @Test
  fun aColourAttributeResolvesItsChannel() {
    val graph = graphOver(ColorAttribute(CHANNEL_ID, SOURCE_ID, ColorAttribute.COLOR_RED))

    assertEquals(0x3f / 255f, graph.getFloat(CHANNEL_ID), TOLERANCE)
  }

  @Test
  fun everyChannelReadsOffTheSourceColour() {
    // One assertion per channel because the op switches on the type, and a fix that reached only
    // the channel a fixture happens to use would look like a fix.
    val channels =
      listOf(
        ColorAttribute.COLOR_ALPHA to 0xff / 255f,
        ColorAttribute.COLOR_RED to 0x3f / 255f,
        ColorAttribute.COLOR_GREEN to 0x51 / 255f,
        ColorAttribute.COLOR_BLUE to 0xb5 / 255f,
      )
    for ((type, expected) in channels) {
      val graph = graphOver(ColorAttribute(CHANNEL_ID, SOURCE_ID, type))
      assertEquals("channel $type", expected, graph.getFloat(CHANNEL_ID), TOLERANCE)
    }
  }

  /**
   * Evaluating through the paint channel must stay a *read*: the graph captures the op's write as
   * the value of a `derivedStateOf`, and a write that reached the shared store instead would be a
   * snapshot mutation during a snapshot read — and would let one op's evaluation clobber another's
   * value.
   */
  @Test
  fun evaluatingThroughPaintDoesNotWriteToTheStore() {
    val state = SnapshotRemoteComposeState().apply { updateColor(SOURCE_ID, SOURCE_COLOR) }
    val graph = graphOver(ColorAttribute(CHANNEL_ID, SOURCE_ID, ColorAttribute.COLOR_RED), state)

    assertEquals(0x3f / 255f, graph.getFloat(CHANNEL_ID), TOLERANCE)
    assertEquals("store was written during evaluation", 0f, state.getFloat(CHANNEL_ID), TOLERANCE)
  }

  private fun graphOver(
    attribute: ColorAttribute,
    state: SnapshotRemoteComposeState =
      SnapshotRemoteComposeState().apply { updateColor(SOURCE_ID, SOURCE_COLOR) },
  ): GraphContext =
    GraphContext(
      state,
      buildComputedOpIndex(listOf(attribute)),
      mutableFloatStateOf(0f),
      RemoteClock.SYSTEM,
    )

  private companion object {
    /** Distinct in every channel, so a channel read off the wrong byte cannot pass. */
    const val SOURCE_COLOR = 0xff3f51b5.toInt()
    const val SOURCE_ID = 42
    const val CHANNEL_ID = 43
    const val TOLERANCE = 1e-4f
  }
}
