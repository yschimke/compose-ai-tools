package ee.schimke.composeai.rcplayer.compose

import androidx.compose.runtime.mutableStateMapOf
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
import ee.schimke.composeai.rcplayer.protocol.RcLayoutAnimation
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcOperation
import ee.schimke.composeai.rcplayer.protocol.RcPaintData
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcRowLayout
import ee.schimke.composeai.rcplayer.protocol.RcValueFloatChangeAction
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.rcplayer.protocol.RcWidthModifier
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Named values are the API a host uses to *drive* a live document, and they used to be the API that
 * *reset* it: the map was a key of the player's `remember`, so any change — a caller rebuilding an
 * equal map in a parent recomposition included — constructed a fresh `RcPlayerState` and discarded
 * running animation timelines, mid-drag touch state, and every variable a document action had
 * already changed.
 *
 * The document here is the one `RcAnimationRenderTest` uses for layout animation, plus a named
 * variable that has nothing to do with it. That separation is the point: changing a value the
 * animation does not read must not disturb the animation at all.
 */
class RcNamedValueRenderTest {

  @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
  @Test
  fun changingANamedValueMidAnimationLeavesTheTimelineRunning() =
    runSkikoComposeUiTest(size = Size(60f, 20f), density = Density(1f)) {
      mainClock.autoAdvance = false
      val namedValues = mutableStateMapOf<String, RcNamedValue>()
      setContent { RcComposePlayer(document(), namedValues = namedValues) }
      mainClock.advanceTimeByFrame()
      waitForIdle()

      // Click starts a 300ms layout animation and sets float 20 to 40 — a variable a *document
      // action* changed, which a rebuilt state would also lose.
      onNode(hasClickAction()).performTouchInput { click() }
      mainClock.advanceTimeBy(150)
      waitForIdle()
      val halfway = onRoot().captureToImage().toPixelMap()
      assertEquals(RED, halfway[25, 10].toArgb(), "the animation did not reach halfway")
      assertEquals(BLUE, halfway[35, 10].toArgb())

      // The mutation under test, exactly mid-flight.
      namedValues["USER:tint"] = RcNamedValue.FloatValue(7f)
      mainClock.advanceTimeBy(200)
      waitForIdle()

      val complete = onRoot().captureToImage().toPixelMap()
      // A rebuilt state would have restarted from float 20 = 0, putting the boundary back at 25
      // rather than 35 — the animation would run again from the beginning, on every frame of a
      // host's slider drag.
      assertEquals(RED, complete[35, 10].toArgb(), "the animation restarted or lost its variable")
      assertEquals(BLUE, complete[45, 10].toArgb())
    }

  @OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
  @Test
  fun settingAndThenRemovingANamedValueDrivesTheLiveDocumentBothWays() =
    runSkikoComposeUiTest(size = Size(60f, 20f), density = Density(1f)) {
      mainClock.autoAdvance = false
      val namedValues = mutableStateMapOf<String, RcNamedValue>()
      setContent { RcComposePlayer(widthDrivenDocument(), namedValues = namedValues) }
      mainClock.advanceTimeByFrame()
      waitForIdle()

      val recorded = onRoot().captureToImage().toPixelMap()
      assertEquals(RED, recorded[10, 10].toArgb())
      assertEquals(0, recorded[40, 10].toArgb(), "the document should start at its recorded 20")

      // Setting through the snapshot map has to reach the live state at all — this is the half that
      // used to work only because the whole state was thrown away and rebuilt.
      namedValues["USER:width"] = RcNamedValue.FloatValue(50f)
      mainClock.advanceTimeByFrame()
      waitForIdle()
      val overridden = onRoot().captureToImage().toPixelMap()
      assertEquals(RED, overridden[40, 10].toArgb(), "the host override did not reach the document")

      // `setNamedValue` has no inverse; a removal only means anything because the state captured
      // what the document recorded *before* the override was seeded. Without that, this stays 50.
      namedValues.remove("USER:width")
      mainClock.advanceTimeByFrame()
      waitForIdle()

      val restored = onRoot().captureToImage().toPixelMap()
      assertEquals(RED, restored[10, 10].toArgb())
      assertEquals(0, restored[40, 10].toArgb(), "the document's recorded 20 did not come back")
    }

  /** One canvas whose width *is* the named variable, so a host edit is directly visible. */
  private fun widthDrivenDocument(): RcDocument {
    val end = RcNoArg(RcOpcodes.CONTAINER_END)
    val operations = mutableListOf<RcOperation>()
    operations += RcFloatConstant(21, RcFloatWord.literal(20f))
    operations += RcNamedVariable(21, RcNamedVariable.FLOAT_TYPE, "USER:width")
    operations += RcRootLayout(1)
    operations += RcLayoutContent(2)
    operations += RcCanvasLayout(5, 50)
    operations += RcWidthModifier(RcDimensionType.EXACT, RcFloatWord.literal(60f))
    operations += RcHeightModifier(RcDimensionType.EXACT, RcFloatWord.literal(20f))
    operations += RcNoArg(RcOpcodes.CANVAS_OPERATIONS)
    operations += RcPaintData(listOf(4, RED))
    // The drawn width *is* the named variable, so a host edit and its removal are both directly
    // visible as ink rather than as a layout bound the canvas would not clip anyway.
    operations +=
      RcDraw4(
        RcOpcodes.DRAW_RECT,
        RcFloatWord.literal(0f),
        RcFloatWord.literal(0f),
        RcFloatWord(0x7fc00015),
        RcFloatWord.literal(20f),
      )
    operations += end
    operations += end
    repeat(2) { operations += end }
    return RcDocument(
      RcHeader(RcVersion(1, 0, 0), legacyWidth = 60, legacyHeight = 20, modern = false),
      operations,
    )
  }

  private fun document(): RcDocument {
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
    val operations = mutableListOf<RcOperation>()
    operations += RcFloatConstant(20, RcFloatWord.literal(20f))
    // Deliberately unrelated to anything drawn: the animation must not care that it changed.
    operations += RcFloatConstant(21, RcFloatWord.literal(1f))
    operations += RcNamedVariable(21, RcNamedVariable.FLOAT_TYPE, "USER:tint")
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
    operations += RcPaintData(listOf(4, RED))
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
    operations += RcPaintData(listOf(4, BLUE))
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
    return RcDocument(
      RcHeader(RcVersion(1, 0, 0), legacyWidth = 60, legacyHeight = 20, modern = false),
      operations,
    )
  }

  private companion object {
    val RED = 0xffff0000.toInt()
    val BLUE = 0xff0000ff.toInt()
  }
}
