package ee.schimke.composeai.rcplayer.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.rcplayer.protocol.RcAccessibilitySemantics
import ee.schimke.composeai.rcplayer.protocol.RcCanvasLayout
import ee.schimke.composeai.rcplayer.protocol.RcDimensionType
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcHeightModifier
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.rcplayer.protocol.RcWidthModifier
import kotlin.test.Test

class RcAccessibilitySemanticsTest {
  @OptIn(ExperimentalTestApi::class)
  @Test
  fun exposesAndroidXSemanticsThroughTheComposeTree() =
    runSkikoComposeUiTest(size = Size(80f, 80f), density = Density(1f)) {
      val document =
        RcDocument(
          RcHeader(RcVersion(1, 0, 0), legacyWidth = 80, legacyHeight = 80, modern = false),
          listOf(
            RcTextData(10, "Submit"),
            RcTextData(11, "Send"),
            RcTextData(12, "Unavailable"),
            RcRootLayout(1),
            RcLayoutContent(2),
            RcCanvasLayout(3, 30),
            RcWidthModifier(RcDimensionType.EXACT, RcFloatWord.literal(40f)),
            RcHeightModifier(RcDimensionType.EXACT, RcFloatWord.literal(40f)),
            RcAccessibilitySemantics(
              contentDescriptionId = 10,
              role = RcAccessibilitySemantics.ROLE_BUTTON,
              textId = 11,
              stateDescriptionId = 12,
              mode = RcAccessibilitySemantics.MODE_SET,
              enabled = false,
              clickable = false,
            ),
            RcNoArg(RcOpcodes.CONTAINER_END),
            RcNoArg(RcOpcodes.CONTAINER_END),
            RcNoArg(RcOpcodes.CONTAINER_END),
          ),
        )
      setContent { RcComposePlayer(document) }

      onNodeWithContentDescription("Submit")
        .assertTextEquals("Send")
        .assertIsNotEnabled()
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unavailable"))
    }
}
