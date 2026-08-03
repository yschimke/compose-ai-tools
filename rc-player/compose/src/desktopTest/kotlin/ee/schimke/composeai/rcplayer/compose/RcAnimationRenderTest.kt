package ee.schimke.composeai.rcplayer.compose

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.rcplayer.protocol.RcAnimationSpec
import ee.schimke.composeai.rcplayer.protocol.RcCanvasLayout
import ee.schimke.composeai.rcplayer.protocol.RcClickModifier
import ee.schimke.composeai.rcplayer.protocol.RcDimensionType
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDraw4
import ee.schimke.composeai.rcplayer.protocol.RcFloatConstant
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcHeightModifier
import ee.schimke.composeai.rcplayer.protocol.RcHostAction
import ee.schimke.composeai.rcplayer.protocol.RcHostNamedAction
import ee.schimke.composeai.rcplayer.protocol.RcHostNamedActionValue
import ee.schimke.composeai.rcplayer.protocol.RcIntegerConstant
import ee.schimke.composeai.rcplayer.protocol.RcLayoutAnimation
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcPaintData
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcRowLayout
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcValueFloatChangeAction
import ee.schimke.composeai.rcplayer.protocol.RcValueIntegerChangeAction
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.rcplayer.protocol.RcVisibilityModifier
import ee.schimke.composeai.rcplayer.protocol.RcWidthModifier
import ee.schimke.composeai.rcplayer.runtime.RcPlayerEvent
import kotlin.test.Test
import kotlin.test.assertTrue

class RcAnimationRenderTest {
  @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
  @Test
  fun boundsAndSiblingPlacementApproachTheAndroidXTargetTogether() =
    runSkikoComposeUiTest(size = Size(60f, 20f), density = Density(1f)) {
      mainClock.autoAdvance = false
      val end = RcNoArg(RcOpcodes.CONTAINER_END)
      val animation =
        RcAnimationSpec(
          animationId = 1,
          motionDurationMillis = RcFloatWord.literal(300f),
          motionEasingType = 4,
          visibilityDurationMillis = RcFloatWord.literal(300f),
          visibilityEasingType = 4,
          enterAnimation = RcLayoutAnimation.FadeIn,
          exitAnimation = RcLayoutAnimation.FadeOut,
        )
      val operations = mutableListOf<ee.schimke.composeai.rcplayer.protocol.RcOperation>()
      operations += RcFloatConstant(20, RcFloatWord.literal(20f))
      operations += RcRootLayout(1)
      operations += RcLayoutContent(2)
      operations += RcRowLayout(3, 30, 1, 4, RcFloatWord.literal(0f))
      operations += RcLayoutContent(4)
      operations += RcCanvasLayout(5, 50)
      operations += RcWidthModifier(RcDimensionType.EXACT, RcFloatWord(0x7fc00014))
      operations += RcHeightModifier(RcDimensionType.EXACT, RcFloatWord.literal(20f))
      operations += animation
      operations += RcClickModifier
      operations += RcValueFloatChangeAction(20, RcFloatWord.literal(40f))
      operations += end
      operations += RcNoArg(RcOpcodes.CANVAS_OPERATIONS)
      operations += RcPaintData(listOf(4, 0xffff0000.toInt()))
      operations +=
        RcDraw4(
          RcOpcodes.DRAW_RECT,
          RcFloatWord.literal(0f),
          RcFloatWord.literal(0f),
          RcFloatWord.literal(60f),
          RcFloatWord.literal(20f),
        )
      operations += end
      operations += end
      operations += RcCanvasLayout(6, 60)
      operations += RcWidthModifier(RcDimensionType.EXACT, RcFloatWord.literal(20f))
      operations += RcHeightModifier(RcDimensionType.EXACT, RcFloatWord.literal(20f))
      operations += animation
      operations += RcNoArg(RcOpcodes.CANVAS_OPERATIONS)
      operations += RcPaintData(listOf(4, 0xff0000ff.toInt()))
      operations +=
        RcDraw4(
          RcOpcodes.DRAW_RECT,
          RcFloatWord.literal(0f),
          RcFloatWord.literal(0f),
          RcFloatWord.literal(20f),
          RcFloatWord.literal(20f),
        )
      operations += end
      operations += end
      repeat(4) { operations += end }
      val document =
        RcDocument(
          RcHeader(RcVersion(1, 0, 0), legacyWidth = 60, legacyHeight = 20, modern = false),
          operations,
        )
      setContent { RcComposePlayer(document) }
      mainClock.advanceTimeByFrame()
      waitForIdle()

      onNode(hasClickAction()).performTouchInput { click() }
      mainClock.advanceTimeBy(150)
      waitForIdle()
      val halfway = onRoot().captureToImage().toPixelMap()
      assertTrue(halfway[25, 10].toArgb() == 0xffff0000.toInt())
      assertTrue(halfway[35, 10].toArgb() == 0xff0000ff.toInt())

      mainClock.advanceTimeBy(200)
      waitForIdle()
      val complete = onRoot().captureToImage().toPixelMap()
      assertTrue(complete[35, 10].toArgb() == 0xffff0000.toInt())
      assertTrue(complete[45, 10].toArgb() == 0xff0000ff.toInt())
    }

  @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
  @Test
  fun fadeOutUsesTheAndroidXVisibilityDurationOnTheManualFrameClock() =
    runSkikoComposeUiTest(size = Size(20f, 20f), density = Density(1f)) {
      mainClock.autoAdvance = false
      val events = mutableListOf<RcPlayerEvent>()
      val end = RcNoArg(RcOpcodes.CONTAINER_END)
      val document =
        RcDocument(
          RcHeader(RcVersion(1, 0, 0), legacyWidth = 20, legacyHeight = 20, modern = false),
          listOf(
            RcIntegerConstant(10, 1),
            RcTextData(100, "visibility"),
            RcRootLayout(1),
            RcLayoutContent(2),
            RcCanvasLayout(3, 30),
            RcWidthModifier(RcDimensionType.EXACT, RcFloatWord.literal(20f)),
            RcHeightModifier(RcDimensionType.EXACT, RcFloatWord.literal(20f)),
            RcAnimationSpec(
              animationId = 1,
              motionDurationMillis = RcFloatWord.literal(300f),
              motionEasingType = 4,
              visibilityDurationMillis = RcFloatWord.literal(300f),
              visibilityEasingType = 4,
              enterAnimation = RcLayoutAnimation.FadeIn,
              exitAnimation = RcLayoutAnimation.FadeOut,
            ),
            RcVisibilityModifier(10),
            RcClickModifier,
            RcValueIntegerChangeAction(10, 0),
            RcHostAction(77),
            RcHostNamedAction(100, RcHostNamedActionValue.IntegerValue(10)),
            end,
            RcNoArg(RcOpcodes.CANVAS_OPERATIONS),
            RcPaintData(listOf(4, 0xffff0000.toInt())),
            RcDraw4(
              RcOpcodes.DRAW_RECT,
              RcFloatWord.literal(0f),
              RcFloatWord.literal(0f),
              RcFloatWord.literal(20f),
              RcFloatWord.literal(20f),
            ),
            end,
            end,
            end,
            end,
          ),
        )
      setContent { RcComposePlayer(document, onEvent = events::add) }
      // The data operation is applied during the first frame; let its initial enter settle.
      mainClock.advanceTimeByFrame()
      waitForIdle()
      mainClock.advanceTimeBy(350)
      waitForIdle()
      val initialAlpha = onRoot().captureToImage().toPixelMap()[10, 10].alpha
      assertTrue(initialAlpha > .95f, "expected opaque initial frame, got $initialAlpha")

      onNode(hasClickAction()).performTouchInput { click() }
      assertTrue(RcPlayerEvent.HostAction(77) in events, "click action did not execute")
      assertTrue(
        RcPlayerEvent.HostNamedAction(
          "visibility",
          ee.schimke.composeai.rcplayer.runtime.RcHostActionValue.IntegerValue(0),
        ) in events,
        "visibility mutation was not retained: $events",
      )
      mainClock.advanceTimeBy(150)
      waitForIdle()
      val halfwayAlpha = onRoot().captureToImage().toPixelMap()[10, 10].alpha
      assertTrue(halfwayAlpha in .35f..65f, "expected half opacity, got $halfwayAlpha")

      mainClock.advanceTimeBy(200)
      mainClock.advanceTimeByFrame()
      waitForIdle()
      onNode(hasClickAction()).assertDoesNotExist()
      val finalColor = onRoot().captureToImage().toPixelMap()[10, 10]
      assertTrue(
        finalColor.green > .95f || finalColor.red < .05f,
        "expected the red component to be gone, got $finalColor",
      )
    }
}
