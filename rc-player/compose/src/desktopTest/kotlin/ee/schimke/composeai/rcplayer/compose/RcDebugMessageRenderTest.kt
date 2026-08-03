package ee.schimke.composeai.rcplayer.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runSkikoComposeUiTest
import ee.schimke.composeai.rcplayer.protocol.RcDebugMessage
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcFloatConstant
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.rcplayer.runtime.RcPlayerEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class RcDebugMessageRenderTest {
  @OptIn(ExperimentalTestApi::class)
  @Test
  fun rootDiagnosticIsDeliveredForALayoutDocument() =
    runSkikoComposeUiTest(size = Size(20f, 20f)) {
      val events = mutableListOf<RcPlayerEvent>()
      val end = RcNoArg(RcOpcodes.CONTAINER_END)
      val document =
        RcDocument(
          RcHeader(RcVersion(1, 0, 0), legacyWidth = 20, legacyHeight = 20, modern = false),
          listOf(
            RcTextData(10, "layout"),
            RcFloatConstant(11, RcFloatWord.literal(20f)),
            RcDebugMessage(10, RcFloatWord(0x7fc00000 or 11), 0),
            RcRootLayout(1),
            RcLayoutContent(2),
            end,
            end,
          ),
        )

      setContent { RcComposePlayer(document, onEvent = events::add) }
      waitForIdle()

      assertEquals(listOf<RcPlayerEvent>(RcPlayerEvent.DebugMessage("layout", 20f, 0)), events)
    }
}
