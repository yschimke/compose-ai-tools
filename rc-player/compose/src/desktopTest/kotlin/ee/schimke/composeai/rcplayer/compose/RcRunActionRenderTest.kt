package ee.schimke.composeai.rcplayer.compose

import androidx.compose.ui.ImageComposeScene
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
import ee.schimke.composeai.rcplayer.protocol.RcWidthModifier
import ee.schimke.composeai.rcplayer.runtime.RcPlayerEvent
import kotlin.test.Test
import kotlin.test.assertTrue

class RcRunActionRenderTest {
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
