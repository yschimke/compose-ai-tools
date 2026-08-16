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
import androidx.compose.remote.core.operations.FloatExpression
import androidx.compose.remote.core.operations.Utils
import androidx.compose.remote.core.operations.utilities.AnimatedFloatExpression
import androidx.compose.remote.player.compose.embedded.GraphContext
import androidx.compose.remote.player.compose.embedded.SnapshotRemoteComposeState
import androidx.compose.remote.player.compose.embedded.buildComputedOpIndex
import androidx.compose.runtime.mutableFloatStateOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * An expression over a component's *measured* size must evaluate against that size.
 *
 * `ComponentValue`s are the one class of leaf that never reaches the shared store — they only exist
 * once layout has run, so the dispatch publishes them as Compose state from `onSizeChanged`.
 * Reading such an id directly always worked, because `rememberRemoteFloatAsState` consults that map
 * first. An expression *over* one did not: [GraphContext] resolved its inputs through the store,
 * found nothing, and evaluated against 0.
 *
 * The expression here is the one a switch carries — `min(width, height) / 2`, the clip radius that
 * makes the thumb round. With the inputs unreadable it came out 0 and the thumb rendered square
 * (compose-ai-tools#3992), while the track's literal-radius clip beside it was fine.
 */
class GraphContextComponentValueTest {

  @Test
  fun anExpressionOverMeasuredSizeUsesTheMeasuredSize() {
    val width = mutableFloatStateOf(TRACK_WIDTH)
    val height = mutableFloatStateOf(TRACK_HEIGHT)
    val graph = graphOverCircleRadius(mapOf(WIDTH_ID to width, HEIGHT_ID to height))

    assertEquals(TRACK_HEIGHT / 2f, graph.getFloat(RADIUS_ID), TOLERANCE)
  }

  /**
   * The map is read through Compose state, so a component that is measured again — a resize, or
   * simply the first layout arriving after the initial composition — has to change the result. A
   * radius captured once at 0 would leave the thumb square for the life of the document.
   */
  @Test
  fun aResizeChangesTheResult() {
    val width = mutableFloatStateOf(0f)
    val height = mutableFloatStateOf(0f)
    val graph = graphOverCircleRadius(mapOf(WIDTH_ID to width, HEIGHT_ID to height))
    assertEquals("before layout", 0f, graph.getFloat(RADIUS_ID), TOLERANCE)

    width.floatValue = TRACK_WIDTH
    height.floatValue = TRACK_HEIGHT

    assertEquals("after layout", TRACK_HEIGHT / 2f, graph.getFloat(RADIUS_ID), TOLERANCE)
  }

  /**
   * With no component values wired in, the inputs are unresolvable and the radius is 0 — the
   * behaviour that produced the square thumb. Kept as a test so the wiring in `RcPlayer` cannot be
   * dropped silently: without it, this is what every player gets.
   */
  @Test
  fun withoutTheComponentValuesTheRadiusCollapsesToZero() {
    val graph = graphOverCircleRadius(componentValues = null)

    assertEquals(0f, graph.getFloat(RADIUS_ID), TOLERANCE)
  }

  /** `min([WIDTH_ID], [HEIGHT_ID]) / 2` — the same RPN a switch's clip radius is recorded as. */
  private fun graphOverCircleRadius(
    componentValues: Map<Int, androidx.compose.runtime.State<Float>>?
  ): GraphContext {
    val radius =
      FloatExpression(
        RADIUS_ID,
        floatArrayOf(
          Utils.asNan(WIDTH_ID),
          Utils.asNan(HEIGHT_ID),
          AnimatedFloatExpression.MIN,
          2f,
          AnimatedFloatExpression.DIV,
        ),
        null,
      )
    return GraphContext(
        SnapshotRemoteComposeState(),
        buildComputedOpIndex(listOf(radius)),
        mutableFloatStateOf(0f),
        RemoteClock.SYSTEM,
      )
      .also { it.componentValues = componentValues }
  }

  private companion object {
    const val WIDTH_ID = 44
    const val HEIGHT_ID = 45
    const val RADIUS_ID = 46
    /** The switch track's own dp size, so the expected radius is a number from a real document. */
    const val TRACK_WIDTH = 36f
    const val TRACK_HEIGHT = 22f
    const val TOLERANCE = 1e-4f
  }
}
