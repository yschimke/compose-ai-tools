package ee.schimke.composeai.rcplayer.compose

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.rcplayer.protocol.RcCanvasLayout
import ee.schimke.composeai.rcplayer.protocol.RcDimensionType
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcHeightModifier
import ee.schimke.composeai.rcplayer.protocol.RcHostAction
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcRunAction
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.rcplayer.protocol.RcWakeIn
import ee.schimke.composeai.rcplayer.protocol.RcWidthModifier
import ee.schimke.composeai.rcplayer.runtime.RcPlayerEvent
import kotlin.test.Test
import kotlin.test.assertTrue

class RcRunActionRenderTest {
  @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
  @Test
  fun wakeInSchedulesARepeatingComposeRepaint() =
    runSkikoComposeUiTest(size = Size(20f, 20f), density = Density(1f)) {
      mainClock.autoAdvance = false
      val events = mutableListOf<RcPlayerEvent>()
      val end = RcNoArg(RcOpcodes.CONTAINER_END)
      val document =
        RcDocument(
          RcHeader(RcVersion(1, 0, 0), legacyWidth = 20, legacyHeight = 20, modern = false),
          listOf(
            RcRootLayout(1),
            RcLayoutContent(2),
            RcCanvasLayout(3, 30),
            RcWidthModifier(RcDimensionType.EXACT, RcFloatWord.literal(20f)),
            RcHeightModifier(RcDimensionType.EXACT, RcFloatWord.literal(20f)),
            RcNoArg(RcOpcodes.CANVAS_OPERATIONS),
            RcWakeIn(RcFloatWord.literal(0f)),
            RcRunAction,
            RcHostAction(78),
            end,
            end,
            end,
            end,
            end,
          ),
        )
      setContent { RcComposePlayer(document, onEvent = events::add) }
      mainClock.advanceTimeByFrame()
      waitForIdle()
      mainClock.advanceTimeByFrame() // Apply the WakeIn discovered during the first draw.
      waitForIdle()
      val initiallyRendered = events.count { it == RcPlayerEvent.HostAction(78) }
      assertTrue(initiallyRendered > 0)

      val beforeWake = events.count { it == RcPlayerEvent.HostAction(78) }

      mainClock.advanceTimeByFrame()
      mainClock.advanceTimeByFrame()
      waitForIdle()
      assertTrue(events.count { it == RcPlayerEvent.HostAction(78) } > beforeWake)
    }

  @Test
  fun composePaintingDispatchesRunActionChildren() {
    val events = mutableListOf<RcPlayerEvent>()
    val end = RcNoArg(RcOpcodes.CONTAINER_END)
    val document =
      RcDocument(
        RcHeader(RcVersion(1, 0, 0), legacyWidth = 20, legacyHeight = 20, modern = false),
        listOf(
          RcRootLayout(1),
          RcLayoutContent(2),
          RcCanvasLayout(3, 30),
          RcWidthModifier(RcDimensionType.EXACT, RcFloatWord.literal(20f)),
          RcHeightModifier(RcDimensionType.EXACT, RcFloatWord.literal(20f)),
          RcNoArg(RcOpcodes.CANVAS_OPERATIONS),
          RcRunAction,
          RcHostAction(77),
          end,
          end,
          end,
          end,
          end,
        ),
      )
    val scene =
      ImageComposeScene(width = 20, height = 20, density = Density(1f)) {
        RcComposePlayer(document, onEvent = events::add)
      }
    try {
      scene.render()

      assertTrue(RcPlayerEvent.HostAction(77) in events)
    } finally {
      scene.close()
    }
  }
}
