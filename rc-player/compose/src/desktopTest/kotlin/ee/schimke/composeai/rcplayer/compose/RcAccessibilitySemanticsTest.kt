package ee.schimke.composeai.rcplayer.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.rcplayer.protocol.RcAccessibilitySemantics
import ee.schimke.composeai.rcplayer.protocol.RcCanvasLayout
import ee.schimke.composeai.rcplayer.protocol.RcClickArea
import ee.schimke.composeai.rcplayer.protocol.RcClickModifier
import ee.schimke.composeai.rcplayer.protocol.RcDimensionType
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcHapticType
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcHeightModifier
import ee.schimke.composeai.rcplayer.protocol.RcHostAction
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcMultiClickModifier
import ee.schimke.composeai.rcplayer.protocol.RcMultiClickType
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcRippleModifier
import ee.schimke.composeai.rcplayer.protocol.RcRootContentBehavior
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcTouchCancelModifier
import ee.schimke.composeai.rcplayer.protocol.RcTouchDownModifier
import ee.schimke.composeai.rcplayer.protocol.RcTouchUpModifier
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.rcplayer.protocol.RcWidthModifier
import ee.schimke.composeai.rcplayer.runtime.RcPlayerEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class RcAccessibilitySemanticsTest {
  @Test
  fun mapsEveryAndroidXHapticFamilyToPortableCmpFeedback() {
    val performed = mutableListOf<HapticFeedbackType>()
    val haptics =
      object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
          performed += hapticFeedbackType
        }
      }

    (0..20).forEach { haptics.performAndroidXHaptic(RcHapticType(it)) }
    haptics.performAndroidXHaptic(RcHapticType(42))

    assertEquals(
      listOf(
        HapticFeedbackType.LongPress,
        HapticFeedbackType.VirtualKey,
        HapticFeedbackType.KeyboardTap,
        HapticFeedbackType.SegmentTick,
        HapticFeedbackType.ContextClick,
        HapticFeedbackType.KeyboardTap,
        HapticFeedbackType.KeyboardTap,
        HapticFeedbackType.VirtualKey,
        HapticFeedbackType.TextHandleMove,
        HapticFeedbackType.GestureThresholdActivate,
        HapticFeedbackType.GestureEnd,
        HapticFeedbackType.Confirm,
        HapticFeedbackType.Reject,
        HapticFeedbackType.ToggleOn,
        HapticFeedbackType.ToggleOff,
        HapticFeedbackType.GestureThresholdActivate,
        HapticFeedbackType.GestureEnd,
        HapticFeedbackType.GestureThresholdActivate,
        HapticFeedbackType.SegmentTick,
        HapticFeedbackType.SegmentFrequentTick,
      ),
      performed,
    )
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun dispatchesLegacyClickAreaFromRealComposeInput() =
    runSkikoComposeUiTest(size = Size(80f, 80f), density = Density(1f)) {
      val events = mutableListOf<RcPlayerEvent>()
      val document =
        RcDocument(
          RcHeader(RcVersion(1, 0, 0), legacyWidth = 40, legacyHeight = 40, modern = false),
          listOf(
            RcTextData(10, "Legacy area"),
            RcTextData(11, "legacy-meta"),
            RcRootContentBehavior(
              scroll = 0,
              alignment =
                RcRootContentBehavior.ALIGNMENT_TOP or RcRootContentBehavior.ALIGNMENT_START,
              sizing = RcRootContentBehavior.SIZING_SCALE,
              mode = RcRootContentBehavior.SCALE_FIT,
            ),
            RcClickArea(
              55,
              10,
              RcFloatWord.literal(0f),
              RcFloatWord.literal(0f),
              RcFloatWord.literal(20f),
              RcFloatWord.literal(20f),
              11,
            ),
          ),
        )
      setContent { RcComposePlayer(document, Modifier.fillMaxSize(), onEvent = events::add) }

      onNodeWithContentDescription("Legacy area").performTouchInput { click(Offset(30f, 20f)) }
      mainClock.advanceTimeBy(1_000)
      waitForIdle()
      onRoot().performTouchInput { click(Offset(40f, 20f)) }
      mainClock.advanceTimeBy(1_000)
      waitForIdle()
      onRoot().performTouchInput { down(Offset(30f, 20f)) }
      mainClock.advanceTimeBy(1_000)
      waitForIdle()
      onRoot().performTouchInput { up() }
      waitForIdle()
      onRoot().performTouchInput { click(Offset(30f, 20f)) }
      onRoot().performTouchInput { click(Offset(30f, 20f)) }
      mainClock.advanceTimeBy(1_000)
      waitForIdle()

      assertEquals(
        listOf<RcPlayerEvent>(RcPlayerEvent.HostActionMetadata(55, "legacy-meta")),
        events,
      )
    }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun dispatchesSingleLongAndDoubleMultiClickContainersFromRealComposeInput() =
    runSkikoComposeUiTest(size = Size(40f, 40f), density = Density(1f)) {
      val events = mutableListOf<RcPlayerEvent>()
      val end = RcNoArg(RcOpcodes.CONTAINER_END)
      val document =
        RcDocument(
          RcHeader(RcVersion(1, 0, 0), legacyWidth = 40, legacyHeight = 40, modern = false),
          listOf(
            RcRootLayout(1),
            RcLayoutContent(2),
            RcCanvasLayout(3, 30),
            RcWidthModifier(RcDimensionType.EXACT, RcFloatWord.literal(40f)),
            RcHeightModifier(RcDimensionType.EXACT, RcFloatWord.literal(40f)),
            RcMultiClickModifier(RcMultiClickType.SINGLE),
            RcHostAction(71),
            end,
            RcMultiClickModifier(RcMultiClickType.LONG),
            RcHostAction(72),
            end,
            RcMultiClickModifier(RcMultiClickType.DOUBLE),
            RcHostAction(73),
            end,
            end,
            end,
            end,
          ),
        )
      setContent { RcComposePlayer(document, onEvent = events::add) }

      onRoot().performTouchInput { click() }
      mainClock.advanceTimeBy(1_000)
      waitForIdle()
      onRoot().performTouchInput { down(center) }
      mainClock.advanceTimeBy(1_000)
      waitForIdle()
      onRoot().performTouchInput { up() }
      waitForIdle()
      onRoot().performTouchInput { doubleClick() }
      waitForIdle()

      assertEquals(
        listOf<RcPlayerEvent>(
          RcPlayerEvent.HostAction(71),
          RcPlayerEvent.HostAction(72),
          RcPlayerEvent.HostAction(73),
        ),
        events,
      )
    }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun dispatchesTouchDownAndUpContainersFromRealComposeInput() =
    runSkikoComposeUiTest(size = Size(40f, 40f), density = Density(1f)) {
      val events = mutableListOf<RcPlayerEvent>()
      val end = RcNoArg(RcOpcodes.CONTAINER_END)
      val document =
        RcDocument(
          RcHeader(RcVersion(1, 0, 0), legacyWidth = 40, legacyHeight = 40, modern = false),
          listOf(
            RcRootLayout(1),
            RcLayoutContent(2),
            RcCanvasLayout(3, 30),
            RcWidthModifier(RcDimensionType.EXACT, RcFloatWord.literal(40f)),
            RcHeightModifier(RcDimensionType.EXACT, RcFloatWord.literal(40f)),
            RcTouchDownModifier,
            RcHostAction(71),
            end,
            RcTouchUpModifier,
            RcHostAction(72),
            end,
            RcTouchCancelModifier,
            RcHostAction(73),
            end,
            end,
            end,
            end,
          ),
        )
      setContent { RcComposePlayer(document, onEvent = events::add) }

      onRoot().performTouchInput { click() }

      assertEquals(
        listOf<RcPlayerEvent>(RcPlayerEvent.HostAction(71), RcPlayerEvent.HostAction(72)),
        events,
      )
    }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun exposesAndroidXSemanticsThroughTheComposeTree() =
    runSkikoComposeUiTest(size = Size(80f, 80f), density = Density(1f)) {
      val events = mutableListOf<RcPlayerEvent>()
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
              clickable = true,
            ),
            RcRippleModifier,
            RcClickModifier,
            RcHostAction(77),
            RcNoArg(RcOpcodes.CONTAINER_END),
            RcNoArg(RcOpcodes.CONTAINER_END),
            RcNoArg(RcOpcodes.CONTAINER_END),
            RcNoArg(RcOpcodes.CONTAINER_END),
          ),
        )
      setContent { RcComposePlayer(document, onEvent = events::add) }

      val node =
        onNodeWithContentDescription("Submit")
          .assertTextEquals("Send")
          .assertIsNotEnabled()
          .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
          .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unavailable"))
      node.performTouchInput { click() }
      assertEquals(listOf<RcPlayerEvent>(RcPlayerEvent.HostAction(77)), events)
    }
}
