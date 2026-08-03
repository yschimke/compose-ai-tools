@file:Suppress("RestrictedApiAndroidX")

package ee.schimke.composeai.rcplayer.compat

import androidx.compose.remote.core.Operations
import androidx.compose.remote.core.PaintOperation
import androidx.compose.remote.core.WireBuffer
import androidx.compose.remote.core.operations.BitmapData
import androidx.compose.remote.core.operations.ClickArea as AndroidxClickArea
import androidx.compose.remote.core.operations.ClipPath
import androidx.compose.remote.core.operations.ColorAttribute
import androidx.compose.remote.core.operations.ColorConstant
import androidx.compose.remote.core.operations.ColorExpression as AndroidxColorExpression
import androidx.compose.remote.core.operations.ColorTheme as AndroidxColorTheme
import androidx.compose.remote.core.operations.DataDynamicListFloat
import androidx.compose.remote.core.operations.DataListFloat
import androidx.compose.remote.core.operations.DataListIds
import androidx.compose.remote.core.operations.DataMapIds
import androidx.compose.remote.core.operations.DataMapLookup
import androidx.compose.remote.core.operations.DrawArc
import androidx.compose.remote.core.operations.DrawBitmap
import androidx.compose.remote.core.operations.DrawBitmapInt as AndroidxDrawBitmapInt
import androidx.compose.remote.core.operations.DrawBitmapScaled as AndroidxDrawBitmapScaled
import androidx.compose.remote.core.operations.DrawCircle
import androidx.compose.remote.core.operations.DrawContent
import androidx.compose.remote.core.operations.DrawLine
import androidx.compose.remote.core.operations.DrawOval
import androidx.compose.remote.core.operations.DrawPath
import androidx.compose.remote.core.operations.DrawRect
import androidx.compose.remote.core.operations.DrawRoundRect
import androidx.compose.remote.core.operations.DrawSector
import androidx.compose.remote.core.operations.DrawText
import androidx.compose.remote.core.operations.DrawTextAnchored as AndroidxDrawTextAnchored
import androidx.compose.remote.core.operations.DrawTextOnPath as AndroidxDrawTextOnPath
import androidx.compose.remote.core.operations.DrawTweenPath
import androidx.compose.remote.core.operations.FloatConstant
import androidx.compose.remote.core.operations.FloatExpression
import androidx.compose.remote.core.operations.FloatFunctionCall as AndroidxFloatFunctionCall
import androidx.compose.remote.core.operations.FloatFunctionDefine as AndroidxFloatFunctionDefine
import androidx.compose.remote.core.operations.HapticFeedback as AndroidxHapticFeedback
import androidx.compose.remote.core.operations.Header
import androidx.compose.remote.core.operations.IdLookup
import androidx.compose.remote.core.operations.ImageAttribute
import androidx.compose.remote.core.operations.IntegerExpression as AndroidxIntegerExpression
import androidx.compose.remote.core.operations.MatrixFromPath
import androidx.compose.remote.core.operations.MatrixRestore
import androidx.compose.remote.core.operations.MatrixRotate
import androidx.compose.remote.core.operations.MatrixSave
import androidx.compose.remote.core.operations.MatrixScale
import androidx.compose.remote.core.operations.MatrixSkew
import androidx.compose.remote.core.operations.MatrixTranslate
import androidx.compose.remote.core.operations.NamedVariable
import androidx.compose.remote.core.operations.PaintData
import androidx.compose.remote.core.operations.PathAppend
import androidx.compose.remote.core.operations.PathCombine
import androidx.compose.remote.core.operations.PathCreate
import androidx.compose.remote.core.operations.PathData
import androidx.compose.remote.core.operations.PathExpression
import androidx.compose.remote.core.operations.PathTween
import androidx.compose.remote.core.operations.RootContentBehavior
import androidx.compose.remote.core.operations.RootContentDescription
import androidx.compose.remote.core.operations.TextAttribute as AndroidxTextAttribute
import androidx.compose.remote.core.operations.TextData
import androidx.compose.remote.core.operations.TextFromFloat
import androidx.compose.remote.core.operations.TextLength
import androidx.compose.remote.core.operations.TextLookup
import androidx.compose.remote.core.operations.TextLookupInt
import androidx.compose.remote.core.operations.TextMeasure
import androidx.compose.remote.core.operations.TextMerge
import androidx.compose.remote.core.operations.TextSubtext
import androidx.compose.remote.core.operations.TextTransform
import androidx.compose.remote.core.operations.Theme
import androidx.compose.remote.core.operations.TimeAttribute as AndroidxTimeAttribute
import androidx.compose.remote.core.operations.TouchExpression
import androidx.compose.remote.core.operations.UpdateDynamicFloatList
import androidx.compose.remote.core.operations.Utils
import androidx.compose.remote.core.operations.WakeIn as AndroidxWakeIn
import androidx.compose.remote.core.operations.layout.CanvasContent
import androidx.compose.remote.core.operations.layout.CanvasOperations
import androidx.compose.remote.core.operations.layout.ClickModifierOperation
import androidx.compose.remote.core.operations.layout.ContainerEnd
import androidx.compose.remote.core.operations.layout.ImpulseOperation as AndroidxImpulseOperation
import androidx.compose.remote.core.operations.layout.ImpulseProcess as AndroidxImpulseProcess
import androidx.compose.remote.core.operations.layout.LayoutComponentContent
import androidx.compose.remote.core.operations.layout.MultiClickModifier
import androidx.compose.remote.core.operations.layout.RootLayoutComponent
import androidx.compose.remote.core.operations.layout.TouchCancelModifierOperation
import androidx.compose.remote.core.operations.layout.TouchDownModifierOperation
import androidx.compose.remote.core.operations.layout.TouchUpModifierOperation
import androidx.compose.remote.core.operations.layout.animation.AnimationSpec as AndroidxAnimationSpec
import androidx.compose.remote.core.operations.layout.managers.BoxLayout
import androidx.compose.remote.core.operations.layout.managers.CanvasLayout
import androidx.compose.remote.core.operations.layout.managers.CollapsibleColumnLayout
import androidx.compose.remote.core.operations.layout.managers.CollapsibleRowLayout
import androidx.compose.remote.core.operations.layout.managers.ColumnLayout
import androidx.compose.remote.core.operations.layout.managers.CoreText
import androidx.compose.remote.core.operations.layout.managers.FitBoxLayout
import androidx.compose.remote.core.operations.layout.managers.FlowLayout
import androidx.compose.remote.core.operations.layout.managers.ImageLayout
import androidx.compose.remote.core.operations.layout.managers.RowLayout
import androidx.compose.remote.core.operations.layout.managers.TextLayout
import androidx.compose.remote.core.operations.layout.managers.TextStyle as AndroidxTextStyle
import androidx.compose.remote.core.operations.layout.modifiers.AlignByModifierOperation
import androidx.compose.remote.core.operations.layout.modifiers.BackgroundModifierOperation
import androidx.compose.remote.core.operations.layout.modifiers.BorderModifierOperation
import androidx.compose.remote.core.operations.layout.modifiers.ClipRectModifierOperation
import androidx.compose.remote.core.operations.layout.modifiers.CollapsiblePriorityModifierOperation
import androidx.compose.remote.core.operations.layout.modifiers.ComponentVisibilityOperation
import androidx.compose.remote.core.operations.layout.modifiers.DimensionConstraintsModifierOperation
import androidx.compose.remote.core.operations.layout.modifiers.DimensionModifierOperation
import androidx.compose.remote.core.operations.layout.modifiers.DrawContentOperation
import androidx.compose.remote.core.operations.layout.modifiers.GraphicsLayerModifierOperation
import androidx.compose.remote.core.operations.layout.modifiers.HeightInModifierOperation
import androidx.compose.remote.core.operations.layout.modifiers.HeightModifierOperation
import androidx.compose.remote.core.operations.layout.modifiers.HostActionMetadataOperation
import androidx.compose.remote.core.operations.layout.modifiers.HostActionOperation
import androidx.compose.remote.core.operations.layout.modifiers.HostNamedActionOperation
import androidx.compose.remote.core.operations.layout.modifiers.LayoutComputeOperation
import androidx.compose.remote.core.operations.layout.modifiers.MarqueeModifierOperation
import androidx.compose.remote.core.operations.layout.modifiers.OffsetModifierOperation
import androidx.compose.remote.core.operations.layout.modifiers.PaddingModifierOperation
import androidx.compose.remote.core.operations.layout.modifiers.RippleModifierOperation
import androidx.compose.remote.core.operations.layout.modifiers.RoundedClipRectModifierOperation
import androidx.compose.remote.core.operations.layout.modifiers.RunActionOperation
import androidx.compose.remote.core.operations.layout.modifiers.ScrollModifierOperation
import androidx.compose.remote.core.operations.layout.modifiers.ValueFloatChangeActionOperation
import androidx.compose.remote.core.operations.layout.modifiers.ValueFloatExpressionChangeActionOperation
import androidx.compose.remote.core.operations.layout.modifiers.ValueIntegerChangeActionOperation
import androidx.compose.remote.core.operations.layout.modifiers.ValueIntegerExpressionChangeActionOperation
import androidx.compose.remote.core.operations.layout.modifiers.ValueStringChangeActionOperation
import androidx.compose.remote.core.operations.layout.modifiers.WidthInModifierOperation
import androidx.compose.remote.core.operations.layout.modifiers.WidthModifierOperation
import androidx.compose.remote.core.operations.layout.modifiers.ZIndexModifierOperation
import androidx.compose.remote.core.operations.matrix.MatrixConstant
import androidx.compose.remote.core.operations.matrix.MatrixExpression
import androidx.compose.remote.core.operations.matrix.MatrixVectorMath
import androidx.compose.remote.core.operations.paint.PaintBundle
import androidx.compose.remote.core.operations.utilities.AnimatedFloatExpression
import androidx.compose.remote.core.operations.utilities.ArrayAccess
import androidx.compose.remote.core.operations.utilities.CollectionsAccess
import androidx.compose.remote.core.operations.utilities.IntegerExpressionEvaluator as AndroidxIntegerExpressionEvaluator
import androidx.compose.remote.core.operations.utilities.MatrixOperations
import androidx.compose.remote.core.operations.utilities.PathGenerator
import androidx.compose.remote.core.operations.utilities.StringUtils
import androidx.compose.remote.core.operations.utilities.easing.Easing
import androidx.compose.remote.core.operations.utilities.easing.FloatAnimation
import androidx.compose.remote.core.operations.utilities.easing.SpringStopEngine
import androidx.compose.remote.core.semantics.CoreSemantics
import androidx.compose.remote.core.types.BooleanConstant
import androidx.compose.remote.core.types.IntegerConstant
import androidx.compose.remote.core.types.LongConstant
import ee.schimke.composeai.rcplayer.protocol.RcAccessibilitySemantics
import ee.schimke.composeai.rcplayer.protocol.RcAlignByModifier
import ee.schimke.composeai.rcplayer.protocol.RcAnimationSpec
import ee.schimke.composeai.rcplayer.protocol.RcBackgroundModifier
import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.composeai.rcplayer.protocol.RcBorderModifier
import ee.schimke.composeai.rcplayer.protocol.RcBoxLayout
import ee.schimke.composeai.rcplayer.protocol.RcCanvasContent
import ee.schimke.composeai.rcplayer.protocol.RcCanvasLayout
import ee.schimke.composeai.rcplayer.protocol.RcClickArea
import ee.schimke.composeai.rcplayer.protocol.RcClickModifier
import ee.schimke.composeai.rcplayer.protocol.RcClipRectModifier
import ee.schimke.composeai.rcplayer.protocol.RcCollapsibleColumnLayout as PlayerCollapsibleColumnLayout
import ee.schimke.composeai.rcplayer.protocol.RcCollapsiblePriorityModifier
import ee.schimke.composeai.rcplayer.protocol.RcCollapsibleRowLayout as PlayerCollapsibleRowLayout
import ee.schimke.composeai.rcplayer.protocol.RcColorAttribute
import ee.schimke.composeai.rcplayer.protocol.RcColorExpression
import ee.schimke.composeai.rcplayer.protocol.RcColorTheme
import ee.schimke.composeai.rcplayer.protocol.RcColumnLayout
import ee.schimke.composeai.rcplayer.protocol.RcCoreText
import ee.schimke.composeai.rcplayer.protocol.RcDimensionConstraintsModifier
import ee.schimke.composeai.rcplayer.protocol.RcDimensionType
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcDraw4
import ee.schimke.composeai.rcplayer.protocol.RcDrawBitmap
import ee.schimke.composeai.rcplayer.protocol.RcDrawBitmapInt
import ee.schimke.composeai.rcplayer.protocol.RcDrawBitmapScaled
import ee.schimke.composeai.rcplayer.protocol.RcDrawTextAnchored
import ee.schimke.composeai.rcplayer.protocol.RcDrawTextOnPath
import ee.schimke.composeai.rcplayer.protocol.RcDynamicFloatList
import ee.schimke.composeai.rcplayer.protocol.RcFitBoxLayout
import ee.schimke.composeai.rcplayer.protocol.RcFloatExpression as PlayerFloatExpression
import ee.schimke.composeai.rcplayer.protocol.RcFloatFunctionCall
import ee.schimke.composeai.rcplayer.protocol.RcFloatFunctionDefine
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcFlowLayout
import ee.schimke.composeai.rcplayer.protocol.RcGraphicsLayerAttribute
import ee.schimke.composeai.rcplayer.protocol.RcGraphicsLayerModifier
import ee.schimke.composeai.rcplayer.protocol.RcHapticFeedback
import ee.schimke.composeai.rcplayer.protocol.RcHapticType
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcHeightInModifier
import ee.schimke.composeai.rcplayer.protocol.RcHeightModifier
import ee.schimke.composeai.rcplayer.protocol.RcHostAction
import ee.schimke.composeai.rcplayer.protocol.RcHostMetadataAction
import ee.schimke.composeai.rcplayer.protocol.RcHostNamedAction
import ee.schimke.composeai.rcplayer.protocol.RcHostNamedActionValue
import ee.schimke.composeai.rcplayer.protocol.RcImageAttribute
import ee.schimke.composeai.rcplayer.protocol.RcImageLayout
import ee.schimke.composeai.rcplayer.protocol.RcImpulseProcess
import ee.schimke.composeai.rcplayer.protocol.RcImpulseStart
import ee.schimke.composeai.rcplayer.protocol.RcIntegerExpression
import ee.schimke.composeai.rcplayer.protocol.RcLayoutAnimation
import ee.schimke.composeai.rcplayer.protocol.RcLayoutCompute
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcMarqueeModifier
import ee.schimke.composeai.rcplayer.protocol.RcMultiClickModifier
import ee.schimke.composeai.rcplayer.protocol.RcMultiClickType
import ee.schimke.composeai.rcplayer.protocol.RcOffsetModifier
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcOperationInventory
import ee.schimke.composeai.rcplayer.protocol.RcPaddingModifier
import ee.schimke.composeai.rcplayer.protocol.RcPathData
import ee.schimke.composeai.rcplayer.protocol.RcPathExpression as PlayerPathExpression
import ee.schimke.composeai.rcplayer.protocol.RcRippleModifier
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcRoundedClipRectModifier
import ee.schimke.composeai.rcplayer.protocol.RcRowLayout
import ee.schimke.composeai.rcplayer.protocol.RcRunAction
import ee.schimke.composeai.rcplayer.protocol.RcScrollModifier
import ee.schimke.composeai.rcplayer.protocol.RcTextAttribute
import ee.schimke.composeai.rcplayer.protocol.RcTextLayout
import ee.schimke.composeai.rcplayer.protocol.RcTextStyle
import ee.schimke.composeai.rcplayer.protocol.RcTextStyleProperty
import ee.schimke.composeai.rcplayer.protocol.RcTimeAttribute
import ee.schimke.composeai.rcplayer.protocol.RcTimeAttributeType
import ee.schimke.composeai.rcplayer.protocol.RcTouchCancelModifier
import ee.schimke.composeai.rcplayer.protocol.RcTouchDownModifier
import ee.schimke.composeai.rcplayer.protocol.RcTouchExpression
import ee.schimke.composeai.rcplayer.protocol.RcTouchUpModifier
import ee.schimke.composeai.rcplayer.protocol.RcUpdateDynamicFloatList
import ee.schimke.composeai.rcplayer.protocol.RcValueFloatChangeAction
import ee.schimke.composeai.rcplayer.protocol.RcValueFloatExpressionChangeAction
import ee.schimke.composeai.rcplayer.protocol.RcValueIntegerChangeAction
import ee.schimke.composeai.rcplayer.protocol.RcValueIntegerExpressionChangeAction
import ee.schimke.composeai.rcplayer.protocol.RcValueStringChangeAction
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.rcplayer.protocol.RcVisibilityModifier
import ee.schimke.composeai.rcplayer.protocol.RcWakeIn
import ee.schimke.composeai.rcplayer.protocol.RcWidthInModifier
import ee.schimke.composeai.rcplayer.protocol.RcWidthModifier
import ee.schimke.composeai.rcplayer.protocol.RcZIndexModifier
import ee.schimke.composeai.rcplayer.runtime.RcAnimationTimeline
import ee.schimke.composeai.rcplayer.runtime.RcFloatExpressionEvaluator
import ee.schimke.composeai.rcplayer.runtime.RcIntegerExpressionEvaluator
import ee.schimke.composeai.rcplayer.runtime.RcPlayerState
import ee.schimke.composeai.rcplayer.runtime.RcTextFormatter
import java.lang.reflect.Modifier
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Conformance bytes are written by AndroidX itself. The TypeScript player is intentionally absent
 * from this test module: AndroidX remote-core/Java player is the protocol authority.
 */
class AndroidxWireCompatibilityTest {
  @Test
  fun androidXAnimationSpecRoundTripsExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 120, 60, 1f, 0L)
    AndroidxAnimationSpec.apply(
      buffer,
      42,
      750f,
      Easing.CUBIC_OVERSHOOT,
      450f,
      Easing.CUBIC_DECELERATE,
      AndroidxAnimationSpec.animationToInt(AndroidxAnimationSpec.ANIMATION.SLIDE_TOP),
      AndroidxAnimationSpec.animationToInt(AndroidxAnimationSpec.ANIMATION.ROTATE),
    )
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)
    val spec = assertIs<RcAnimationSpec>(document.operations.single())
    assertEquals(42, spec.animationId)
    assertEquals(750f, spec.motionDurationMillis.value)
    assertEquals(Easing.CUBIC_OVERSHOOT, spec.motionEasingType)
    assertEquals(RcLayoutAnimation.SlideTop, spec.enterAnimation)
    assertEquals(RcLayoutAnimation.Rotate, spec.exitAnimation)
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun animationSpecTimelineMatchesAndroidXMillisecondConversionAtEveryFrame() {
    val spec =
      RcAnimationSpec(
        animationId = 1,
        motionDurationMillis = RcFloatWord.literal(750f),
        motionEasingType = Easing.CUBIC_OVERSHOOT,
        visibilityDurationMillis = RcFloatWord.literal(450f),
        visibilityEasingType = Easing.CUBIC_DECELERATE,
        enterAnimation = RcLayoutAnimation.FadeIn,
        exitAnimation = RcLayoutAnimation.FadeOut,
      )
    val expectedMotion =
      FloatAnimation(Easing.CUBIC_OVERSHOOT, .75f, null, 0f, Float.NaN).also {
        it.setTargetValue(1f)
      }
    val expectedVisibility =
      FloatAnimation(Easing.CUBIC_DECELERATE, .45f, null, 0f, Float.NaN).also {
        it.setTargetValue(1f)
      }
    val actual = RcAnimationTimeline(spec)

    (0..900 step 15).forEach { millis ->
      val progress = actual.progress(millis.toFloat())
      assertEquals(expectedMotion.get(millis / 1_000f), progress.motion, .0001f)
      assertEquals(expectedVisibility.get(millis / 1_000f), progress.visibility, .0001f)
    }
  }

  @Test
  fun androidXImpulseContainersRoundTripExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 120, 60, 1f, 0L)
    AndroidxImpulseOperation.apply(buffer, Utils.asNan(40), 1.5f)
    AndroidxImpulseProcess.apply(buffer)
    ContainerEnd.apply(buffer)
    ContainerEnd.apply(buffer)
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)
    val impulse = assertIs<RcImpulseStart>(document.operations[0])
    assertEquals(40, impulse.duration.referencedId)
    assertEquals(1.5f, impulse.startAt.value)
    assertIs<RcImpulseProcess>(document.operations[1])
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXTimeAttributeAndWakeInRoundTripExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 120, 60, 1f, 0L)
    AndroidxTimeAttribute.apply(
      buffer,
      20,
      21,
      AndroidxTimeAttribute.TIME_FROM_ARG_MIN,
      intArrayOf(22, 23),
    )
    AndroidxTimeAttribute.apply(buffer, 24, 0, (-1).toShort(), IntArray(32) { it + 100 })
    AndroidxWakeIn.apply(buffer, Utils.asNan(42))
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)
    val times = document.operations.filterIsInstance<RcTimeAttribute>()
    assertEquals(RcTimeAttributeType.FromArgumentMinutes, times[0].type)
    assertEquals(listOf(22, 23), times[0].argumentIds)
    assertEquals(-1, times[1].type.wireValue)
    assertEquals(32, times[1].argumentIds.size)
    assertEquals(42, document.operations.filterIsInstance<RcWakeIn>().single().seconds.referencedId)
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXClickAreaRoundTripsExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 120, 60, 1f, 0L)
    AndroidxClickArea.apply(buffer, 55, 10, Utils.asNan(42), 2f, 30f, 40f, 11)
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)
    val area = assertIs<RcClickArea>(document.operations.single())

    assertEquals(55, area.id)
    assertEquals(42, area.left.referencedId)
    assertEquals(2f, area.top.value)
    assertEquals(11, area.metadataId)
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXMultiClickModifiersRoundTripExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 120, 60, 1f, 0L)
    listOf(0 to 71, 1 to 72, 2 to 73).forEach { (type, actionId) ->
      MultiClickModifier.apply(buffer, type)
      HostActionOperation.apply(buffer, actionId)
      ContainerEnd.apply(buffer)
    }
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)

    assertEquals(
      listOf(RcMultiClickType.SINGLE, RcMultiClickType.LONG, RcMultiClickType.DOUBLE),
      document.operations.filterIsInstance<RcMultiClickModifier>().map { it.type },
    )
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXHapticFeedbackRoundTripsEveryRawIntExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 120, 60, 1f, 0L)
    listOf(0, 1, 20, 42, -1).forEach { AndroidxHapticFeedback.apply(buffer, it) }
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)

    assertEquals(
      listOf(0, 1, 20, 42, -1),
      document.operations.filterIsInstance<RcHapticFeedback>().map { it.type.wireValue },
    )
    assertEquals(
      RcHapticType.LongPress,
      document.operations.filterIsInstance<RcHapticFeedback>()[1].type,
    )
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXTouchActionModifiersRoundTripExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 120, 60, 1f, 0L)
    TouchDownModifierOperation.apply(buffer)
    HostActionOperation.apply(buffer, 71)
    ContainerEnd.apply(buffer)
    TouchUpModifierOperation.apply(buffer)
    HostActionOperation.apply(buffer, 72)
    ContainerEnd.apply(buffer)
    TouchCancelModifierOperation.apply(buffer)
    HostActionOperation.apply(buffer, 73)
    ContainerEnd.apply(buffer)
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)

    assertIs<RcTouchDownModifier>(document.operations[0])
    assertIs<RcTouchUpModifier>(document.operations[3])
    assertIs<RcTouchCancelModifier>(document.operations[6])
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXMarqueeModifierRoundTripsExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 120, 60, 1f, 0L)
    MarqueeModifierOperation.apply(buffer, 3, 1, 250f, 500f, 12f, 40f)
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)
    val marquee = assertIs<RcMarqueeModifier>(document.operations.single())

    assertEquals(3, marquee.iterations)
    assertEquals(1, marquee.animationMode)
    assertEquals(250f, marquee.repeatDelayMillis.value)
    assertEquals(500f, marquee.initialDelayMillis.value)
    assertEquals(12f, marquee.spacing.value)
    assertEquals(40f, marquee.velocity.value)
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXScrollAndTouchExpressionRoundTripExactly() {
    assertEquals(0, RcScrollModifier.VERTICAL)
    assertEquals(1, RcScrollModifier.HORIZONTAL)
    val buffer = WireBuffer()
    Header.apply(buffer, 120, 60, 1f, 0L)
    ScrollModifierOperation.apply(buffer, RcScrollModifier.VERTICAL, Utils.asNan(41), 120f, 4f)
    TouchExpression.apply(
      buffer,
      41,
      2f,
      0f,
      120f,
      Utils.asNan(42),
      3,
      floatArrayOf(Utils.asNan(43)),
      TouchExpression.STOP_NOTCHES_EVEN,
      floatArrayOf(4f),
      floatArrayOf(0f, 1f, 5f, 7f),
    )
    ContainerEnd.apply(buffer)
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)

    val scroll = assertIs<RcScrollModifier>(document.operations[0])
    assertEquals(RcScrollModifier.VERTICAL, scroll.direction)
    val touch = assertIs<RcTouchExpression>(document.operations[1])
    assertEquals(41, touch.id)
    assertEquals(TouchExpression.STOP_NOTCHES_EVEN, touch.stopMode)
    assertEquals(listOf(4f), touch.stopSpec.map { it.value })
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXRippleModifierRoundTripsExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 120, 60, 1f, 0L)
    RippleModifierOperation.apply(buffer)
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)

    assertIs<RcRippleModifier>(document.operations.single())
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXRunActionContainerRoundTripsExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 120, 60, 1f, 0L)
    RunActionOperation.apply(buffer)
    HostActionOperation.apply(buffer, 77)
    ContainerEnd.apply(buffer)
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)

    assertIs<RcRunAction>(document.operations[0])
    assertEquals(77, assertIs<RcHostAction>(document.operations[1]).actionId)
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXClickActionsRoundTripExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 120, 60, 1f, 0L)
    ClickModifierOperation.apply(buffer)
    HostActionOperation.apply(buffer, 77)
    HostNamedActionOperation.apply(buffer, 12, HostNamedActionOperation.INT_TYPE, 20)
    HostActionMetadataOperation.apply(buffer, 78, 13)
    ValueIntegerChangeActionOperation.apply(buffer, 20, 4)
    ValueIntegerExpressionChangeActionOperation.apply(buffer, 23L, 31L)
    ValueStringChangeActionOperation.apply(buffer, 21, 11)
    ValueFloatChangeActionOperation.apply(buffer, 22, Utils.asNan(42))
    ValueFloatExpressionChangeActionOperation.apply(buffer, 24, 32)
    ContainerEnd.apply(buffer)
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)

    assertIs<RcClickModifier>(document.operations[0])
    assertEquals(77, assertIs<RcHostAction>(document.operations[1]).actionId)
    assertEquals(
      RcHostNamedActionValue.IntegerValue(20),
      assertIs<RcHostNamedAction>(document.operations[2]).value,
    )
    assertEquals(13, assertIs<RcHostMetadataAction>(document.operations[3]).metadataTextId)
    assertEquals(4, assertIs<RcValueIntegerChangeAction>(document.operations[4]).value)
    assertEquals(
      31L,
      assertIs<RcValueIntegerExpressionChangeAction>(document.operations[5]).expressionId,
    )
    assertEquals(11, assertIs<RcValueStringChangeAction>(document.operations[6]).valueId)
    assertEquals(42, assertIs<RcValueFloatChangeAction>(document.operations[7]).value.referencedId)
    assertEquals(
      32,
      assertIs<RcValueFloatExpressionChangeAction>(document.operations[8]).expressionId,
    )
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXAccessibilitySemanticsRoundTripsExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 120, 60, 1f, 0L)
    CoreSemantics.apply(
      buffer,
      10,
      RcAccessibilitySemantics.ROLE_SWITCH.toByte(),
      11,
      12,
      RcAccessibilitySemantics.MODE_MERGE,
      false,
      true,
    )
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)
    val semantics = assertIs<RcAccessibilitySemantics>(document.operations.single())

    assertEquals(RcAccessibilitySemantics.ROLE_SWITCH, semantics.role)
    assertEquals(RcAccessibilitySemantics.MODE_MERGE, semantics.mode)
    assertFalse(semantics.enabled)
    assertTrue(semantics.clickable)
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXLayoutComputeRoundTripsExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 120, 60, 1f, 0L)
    LayoutComputeOperation.apply(buffer, LayoutComputeOperation.TYPE_POSITION, 42, false)
    ContainerEnd.apply(buffer)
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)
    val compute = assertIs<RcLayoutCompute>(document.operations[0])

    assertEquals(RcLayoutCompute.POSITION, compute.type)
    assertEquals(42, compute.boundsId)
    assertFalse(compute.animateChanges)
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXAlignByRoundTripsExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 120, 60, 1f, 0L)
    AlignByModifierOperation.apply(buffer, AlignByModifierOperation.FIRST_BASELINE, 0x1234)
    AlignByModifierOperation.apply(buffer, Utils.asNan(42), 7)
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)

    assertEquals(
      RcAlignByModifier.FIRST_BASELINE_ID,
      assertIs<RcAlignByModifier>(document.operations[0]).line.referencedId,
    )
    assertEquals(0x1234, assertIs<RcAlignByModifier>(document.operations[0]).flags)
    assertEquals(42, assertIs<RcAlignByModifier>(document.operations[1]).line.referencedId)
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXCollapsibleLayoutsAndPriorityRoundTripExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 180, 80, 1f, 0L)
    CollapsibleRowLayout.apply(
      buffer,
      3,
      30,
      RowLayout.SPACE_BETWEEN,
      RowLayout.CENTER,
      Utils.asNan(42),
    )
    CollapsiblePriorityModifierOperation.apply(buffer, 0, Utils.asNan(43))
    CollapsibleColumnLayout.apply(buffer, 4, 40, ColumnLayout.END, ColumnLayout.SPACE_AROUND, 6.5f)
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)

    assertEquals(
      42,
      assertIs<PlayerCollapsibleRowLayout>(document.operations[0]).spacedBy.referencedId,
    )
    assertEquals(
      43,
      assertIs<RcCollapsiblePriorityModifier>(document.operations[1]).priority.referencedId,
    )
    assertEquals(
      6.5f,
      assertIs<PlayerCollapsibleColumnLayout>(document.operations[2]).spacedBy.value,
    )
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  private val onePixelPng =
    Base64.getDecoder()
      .decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACAQMAAABIeJ9nAAAAIGNIUk0AAHomAACAhAAA+gAAAIDoAAB1MAAA6mAAADqYAAAXcJy6UTwAAAAGUExURf8Abv///5rktCEAAAABYktHRAH/Ai3eAAAAB3RJTUUH6ggCFygueZAHngAAACV0RVh0ZGF0ZTpjcmVhdGUAMjAyNi0wOC0wMlQyMzo0MDo0NiswMDowMPuL5LUAAAAldEVYdGRhdGU6bW9kaWZ5ADIwMjYtMDgtMDJUMjM6NDA6NDYrMDA6MDCK1lwJAAAAKHRFWHRkYXRlOnRpbWVzdGFtcAAyMDI2LTA4LTAyVDIzOjQwOjQ2KzAwOjAw3cN91gAAAAxJREFUCNdjYGBgAAAABAABJzQnCgAAAABJRU5ErkJggg=="
      )

  @Test
  fun checkedInOperationInventoryExactlyMatchesAndroidXJavaConstants() {
    val androidX =
      Operations::class
        .java
        .fields
        .filter { it.type == Int::class.javaPrimitiveType && Modifier.isStatic(it.modifiers) }
        .associate { it.name to it.getInt(null) }
    val manifest = RcOperationInventory.entries.associate { it.constantName to it.opcode }

    assertEquals(androidX, manifest)
  }

  @Test
  fun androidXModifierDrawContentHasNoExecutablePaintContract() {
    assertFalse(PaintOperation::class.java.isAssignableFrom(DrawContentOperation::class.java))
    val apply =
      DrawContentOperation::class.java.declaredMethods.single {
        it.name == "apply" && it.parameterCount == 1 && !Modifier.isStatic(it.modifiers)
      }
    assertEquals(null, apply.invoke(DrawContentOperation(), null))
  }

  @Test
  fun androidXFoundationalLayoutOperationsRoundTripExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 320, 180, 1f, 0L)
    RootLayoutComponent.apply(buffer, 1)
    LayoutComponentContent.apply(buffer, 2)
    CanvasLayout.apply(buffer, 3, 30)
    BoxLayout.apply(buffer, 4, 40, BoxLayout.START, BoxLayout.BOTTOM)
    RowLayout.apply(buffer, 5, 50, RowLayout.SPACE_BETWEEN, RowLayout.CENTER, Utils.asNan(42))
    ColumnLayout.apply(buffer, 6, 60, ColumnLayout.END, ColumnLayout.SPACE_AROUND, 12.5f)
    FlowLayout.apply(
      buffer,
      10,
      100,
      FlowLayout.SPACE_EVENLY,
      FlowLayout.CENTER,
      Utils.asNan(44),
      3,
      2,
    )
    FitBoxLayout.apply(buffer, 7, 70, FitBoxLayout.CENTER, FitBoxLayout.TOP)
    ImageLayout.apply(buffer, 9, 90, 101, 4, Utils.asNan(43))
    CanvasContent.apply(buffer, 8)
    repeat(10) { ContainerEnd.apply(buffer) }
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)

    assertIs<RcRootLayout>(document.operations[0])
    assertIs<RcLayoutContent>(document.operations[1])
    assertIs<RcCanvasLayout>(document.operations[2])
    assertIs<RcBoxLayout>(document.operations[3])
    assertEquals(42, assertIs<RcRowLayout>(document.operations[4]).spacedBy.referencedId)
    assertEquals(12.5f, assertIs<RcColumnLayout>(document.operations[5]).spacedBy.value)
    assertEquals(44, assertIs<RcFlowLayout>(document.operations[6]).spacedBy.referencedId)
    assertEquals(3, assertIs<RcFlowLayout>(document.operations[6]).maxItemsInEachRow)
    assertIs<RcFitBoxLayout>(document.operations[7])
    assertEquals(101, assertIs<RcImageLayout>(document.operations[8]).bitmapId)
    assertEquals(43, assertIs<RcImageLayout>(document.operations[8]).alpha.referencedId)
    assertEquals(8, assertIs<RcCanvasContent>(document.operations[9]).componentId)
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXTextLayoutRoundTripsExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 240, 80, 1f, 0L)
    TextData.apply(buffer, 41, "AndroidX text layout")
    TextLayout.apply(
      buffer,
      7,
      70,
      41,
      0xff123456.toInt(),
      Utils.asNan(42),
      PaintBundle.FONT_BOLD_ITALIC,
      Utils.asNan(43),
      -1,
      TextLayout.TEXT_ALIGN_CENTER or (TextLayout.FLAG_IS_DYNAMIC_COLOR shl 16),
      TextLayout.OVERFLOW_ELLIPSIS,
      2,
    )
    ContainerEnd.apply(buffer)
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)
    val text = assertIs<RcTextLayout>(document.operations[1])

    assertEquals(42, text.fontSize.referencedId)
    assertEquals(43, text.fontWeight.referencedId)
    assertEquals(RcTextLayout.ALIGN_CENTER, text.textAlign)
    assertEquals(RcTextLayout.FLAG_DYNAMIC_COLOR, text.flags)
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXCoreTextAndSparseStyleRoundTripExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 240, 80, 1f, 0L)
    TextData.apply(buffer, 41, "Inherited AndroidX style")
    AndroidxTextStyle.apply(
      buffer,
      700,
      0xff345678.toInt(),
      null,
      18f,
      null,
      null,
      PaintBundle.FONT_ITALIC,
      500f,
      null,
      TextLayout.TEXT_ALIGN_CENTER,
      TextLayout.OVERFLOW_ELLIPSIS,
      2,
      1f,
      null,
      null,
      null,
      null,
      null,
      true,
      false,
      null,
      null,
      false,
      null,
    )
    RootLayoutComponent.apply(buffer, 1)
    LayoutComponentContent.apply(buffer, 2)
    CoreText.apply(
      buffer,
      3,
      30,
      41,
      0xff000000.toInt(),
      -1,
      36f,
      -1f,
      -1f,
      PaintBundle.FONT_NORMAL,
      400f,
      -1,
      CoreText.TEXT_ALIGN_LEFT,
      CoreText.OVERFLOW_CLIP,
      Int.MAX_VALUE,
      0f,
      0f,
      1f,
      CoreText.BREAK_STRATEGY_SIMPLE,
      CoreText.HYPHENATION_FREQUENCY_NONE,
      CoreText.JUSTIFICATION_MODE_NONE,
      false,
      false,
      null,
      null,
      false,
      0,
      700,
    )
    WidthModifierOperation.apply(buffer, DimensionModifierOperation.Type.EXACT.ordinal, 200f)
    HeightModifierOperation.apply(buffer, DimensionModifierOperation.Type.EXACT.ordinal, 60f)
    repeat(3) { ContainerEnd.apply(buffer) }
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)
    val style = assertIs<RcTextStyle>(document.operations[1])
    val core = assertIs<RcCoreText>(document.operations[4])

    assertEquals(700, style.styleId)
    assertEquals(700, core.textStyleId)
    assertEquals(
      18f,
      style.properties
        .filterIsInstance<RcTextStyleProperty.FloatValue>()
        .single { it.id == 5 }
        .value
        .value,
    )
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXFoundationalLayoutModifiersRoundTripExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 320, 180, 1f, 0L)
    WidthModifierOperation.apply(buffer, DimensionModifierOperation.Type.EXACT.ordinal, 80f)
    HeightModifierOperation.apply(
      buffer,
      DimensionModifierOperation.Type.WEIGHT.ordinal,
      Utils.asNan(42),
    )
    PaddingModifierOperation.apply(buffer, 1f, 2f, Utils.asNan(43), 4f)
    OffsetModifierOperation.apply(buffer, Utils.asNan(44), 6f)
    ZIndexModifierOperation.apply(buffer, Utils.asNan(45))
    WidthInModifierOperation.apply(buffer, 10f, -1f)
    HeightInModifierOperation.apply(buffer, Utils.asNan(46), 70f)
    DimensionConstraintsModifierOperation.apply(buffer, 2, 15f, Utils.asNan(47))
    ComponentVisibilityOperation.apply(buffer, 48)
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)

    assertEquals(RcDimensionType.EXACT, assertIs<RcWidthModifier>(document.operations[0]).type)
    assertEquals(42, assertIs<RcHeightModifier>(document.operations[1]).value.referencedId)
    assertEquals(43, assertIs<RcPaddingModifier>(document.operations[2]).right.referencedId)
    assertEquals(44, assertIs<RcOffsetModifier>(document.operations[3]).x.referencedId)
    assertEquals(45, assertIs<RcZIndexModifier>(document.operations[4]).value.referencedId)
    assertEquals(-1f, assertIs<RcWidthInModifier>(document.operations[5]).maximum.value)
    assertEquals(46, assertIs<RcHeightInModifier>(document.operations[6]).minimum.referencedId)
    val constraints = assertIs<RcDimensionConstraintsModifier>(document.operations[7])
    assertEquals(RcDimensionConstraintsModifier.REQUIRED_HORIZONTAL, constraints.type)
    assertEquals(47, constraints.maximum.referencedId)
    assertEquals(48, assertIs<RcVisibilityModifier>(document.operations[8]).visibilityId)
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXPaintDecoratorsRoundTripExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 320, 180, 1f, 0L)
    BackgroundModifierOperation.apply(buffer, 2, 91, 12, 13, .1f, .2f, .3f, .4f, 1)
    BorderModifierOperation.apply(buffer, 2, 92, 1, 14, 5f, 6f, .1f, .2f, .3f, .4f, 2)
    RoundedClipRectModifierOperation.apply(buffer, 1f, Utils.asNan(42), 3f, 4f)
    ClipRectModifierOperation.apply(buffer)
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)

    val background = assertIs<RcBackgroundModifier>(document.operations[0])
    assertTrue(background.usesColorId)
    assertEquals(91, background.colorId)
    assertEquals(12, background.reserved1)
    val border = assertIs<RcBorderModifier>(document.operations[1])
    assertEquals(1, border.wireVersion)
    assertEquals(92, border.colorId)
    assertEquals(
      42,
      assertIs<RcRoundedClipRectModifier>(document.operations[2]).topEnd.referencedId,
    )
    assertIs<RcClipRectModifier>(document.operations[3])
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXGraphicsLayerAttributesRoundTripExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 100, 100, 1f, 0L)
    GraphicsLayerModifierOperation.apply(
      buffer,
      hashMapOf(
        GraphicsLayerModifierOperation.SCALE_X to 1.5f,
        GraphicsLayerModifierOperation.TRANSLATION_X to Utils.asNan(42),
        GraphicsLayerModifierOperation.TRANSLATION_Y to 10f,
        GraphicsLayerModifierOperation.ALPHA to .5f,
      ),
    )
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)

    val layer = assertIs<RcGraphicsLayerModifier>(document.operations.single())
    val translation =
      assertIs<RcGraphicsLayerAttribute.FloatValue>(
        layer.attributes.single { it.index == RcGraphicsLayerModifier.TRANSLATION_X }
      )
    assertEquals(42, translation.value.referencedId)
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXBaselineCanvasDocumentDecodesAndReencodesExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 320, 180, 1f, 0L)
    FloatConstant.apply(buffer, 42, 12.5f)
    ColorConstant.apply(buffer, 43, 0xff336699.toInt())
    TextData.apply(buffer, 44, "AndroidX authority")
    TextData.apply(buffer, 45, " player")
    TextMerge.apply(buffer, 46, 44, 45)
    TextLength.apply(buffer, 47, 46)
    TextSubtext.apply(buffer, 48, 46, 0f, 8f)
    TextTransform.apply(buffer, 49, 46, 0f, -1f, TextTransform.TEXT_TO_UPPERCASE)
    TextFromFloat.apply(buffer, 50, 12345.678f, 10, 2, TextFromFloat.GROUPING_BY3)
    val paint =
      PaintBundle().apply {
        setColor(0xff336699.toInt())
        setStyle(PaintBundle.STYLE_FILL)
      }
    PaintData.apply(buffer, paint)
    DrawRect.apply(buffer, Utils.asNan(42), 4f, 100f, 80f)
    DrawCircle.apply(buffer, 50f, 50f, 12f)
    DrawLine.apply(buffer, 0f, 0f, 320f, 180f)
    DrawOval.apply(buffer, 4f, 8f, 40f, 44f)
    DrawRoundRect.apply(buffer, 10f, 10f, 90f, 60f, 8f, 8f)
    DrawArc.apply(buffer, 20f, 20f, 100f, 100f, 30f, 120f)
    DrawSector.apply(buffer, 20f, 20f, 100f, 100f, 30f, 120f)
    MatrixSave.apply(buffer)
    MatrixTranslate.apply(buffer, 3f, 4f)
    MatrixScale.apply(buffer, 2f, 2f, 0f, 0f)
    MatrixRotate.apply(buffer, 45f, 10f, 10f)
    MatrixSkew.apply(buffer, .1f, .2f)
    MatrixRestore.apply(buffer)
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)

    assertEquals(320, document.header.width)
    assertEquals(180, document.header.height)
    val rect = assertIs<RcDraw4>(document.operations.first { it.opcode == RcOpcodes.DRAW_RECT })
    assertEquals(42, rect.first.referencedId)
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXModernPropertyHeaderRoundTripsExactly() {
    val buffer = WireBuffer()
    Header.apply(
      buffer,
      8,
      shortArrayOf(
        Header.DOC_WIDTH,
        Header.DOC_HEIGHT,
        Header.DOC_DENSITY_AT_GENERATION,
        Header.DOC_SOURCE,
      ),
      arrayOf<Any>(640, 480, 2f, "androidx-alpha16"),
    )
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)

    assertEquals(640, document.header.width)
    assertEquals(480, document.header.height)
    assertEquals(2f, document.header.density)
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXMetadataContainersAndPaddedPathRoundTripExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 200, 120, 1f, 0L)
    Theme.apply(buffer, Theme.DARK)
    RootContentBehavior.apply(buffer, 0, 34, 0, 0)
    TextData.apply(buffer, 61, "Root description")
    RootContentDescription.apply(buffer, 61)
    NamedVariable.apply(buffer, 62, 1, "USER:progress")
    IntegerConstant.apply(buffer, 63, -1234567)
    BooleanConstant.apply(buffer, 64, true)
    LongConstant.apply(buffer, 65, 0x123456789abcdefL)
    val pathId = 77
    val words =
      floatArrayOf(
        PathData.MOVE_NAN,
        10f,
        10f,
        PathData.LINE_NAN,
        0f,
        0f,
        100f,
        10f,
        PathData.QUADRATIC_NAN,
        0f,
        0f,
        120f,
        20f,
        100f,
        50f,
        PathData.CONIC_NAN,
        0f,
        0f,
        80f,
        60f,
        60f,
        70f,
        0.75f,
        PathData.CUBIC_NAN,
        0f,
        0f,
        80f,
        80f,
        20f,
        80f,
        10f,
        10f,
        PathData.CLOSE_NAN,
        PathData.DONE_NAN,
      )
    PathData.apply(buffer, pathId or (1 shl 24), words)
    PathData.apply(buffer, 78, words.copyOf().also { it[2] = 20f })
    PathTween.apply(buffer, 79, pathId, 78, 0.25f)
    DrawTweenPath.apply(buffer, pathId, 78, 0.5f, 0.1f, 0.9f)
    PathCreate.apply(buffer, 80, 4f, 5f)
    PathAppend.apply(
      buffer,
      80,
      floatArrayOf(PathData.LINE_NAN, 0f, 0f, 30f, 40f, PathData.DONE_NAN),
    )
    PathCombine.apply(buffer, 81, pathId, 78, PathCombine.OP_INTERSECT)
    MatrixFromPath.apply(
      buffer,
      pathId,
      0.5f,
      3f,
      MatrixFromPath.POSITION_MATRIX_FLAG or MatrixFromPath.TANGENT_MATRIX_FLAG,
    )
    MatrixConstant.apply(
      buffer,
      82,
      0,
      floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 2f, 3f, 0f, 1f),
    )
    MatrixVectorMath.apply(buffer, 0, intArrayOf(83, 84), 82, floatArrayOf(4f, 5f))
    MatrixExpression.apply(
      buffer,
      85,
      0,
      floatArrayOf(3f, 4f, MatrixOperations.TRANSLATE2, 2f, MatrixOperations.SCALE_X),
    )
    CanvasContent.apply(buffer, 70)
    CanvasOperations.apply(buffer)
    DrawPath.apply(buffer, pathId)
    ClipPath.apply(buffer, pathId)
    DrawContent.apply(buffer)
    ContainerEnd.apply(buffer)
    ContainerEnd.apply(buffer)
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)
    val path = assertIs<RcPathData>(document.operations.first { it.opcode == RcOpcodes.DATA_PATH })

    assertEquals(pathId, path.id)
    assertEquals(1, path.winding)
    assertEquals(words.map(Float::toRawBits), path.words.map { it.bits })
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXCollectionsAndLookupsRoundTripExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 200, 120, 1f, 0L)
    TextData.apply(buffer, 10, "zero")
    TextData.apply(buffer, 11, "one")
    TextData.apply(buffer, 12, "answer")
    IntegerConstant.apply(buffer, 13, 1)
    FloatConstant.apply(buffer, 14, 2.5f)
    LongConstant.apply(buffer, 15, 0x1_0000_0001L)
    BooleanConstant.apply(buffer, 16, true)
    DataListIds.apply(buffer, 20, intArrayOf(10, 11))
    DataListFloat.apply(buffer, 21, floatArrayOf(1f, Utils.asNan(14), -3f))
    DataMapIds.apply(
      buffer,
      22,
      arrayOf("text", "int", "float", "long", "boolean"),
      byteArrayOf(0, 1, 2, 3, 4),
      intArrayOf(11, 13, 14, 15, 16),
    )
    TextLookup.apply(buffer, 30, 20, 1f)
    TextLookupInt.apply(buffer, 31, 20, 13)
    IdLookup.apply(buffer, 32, 20, 0f)
    DataMapLookup.apply(buffer, 33, 22, 12)
    val textPaint =
      PaintBundle().apply {
        setTextSize(18f)
        setTextStyle(3, 600, true)
        setColor(0xff123456.toInt())
      }
    PaintData.apply(buffer, textPaint)
    TextMeasure.apply(
      buffer,
      34,
      11,
      TextMeasure.MEASURE_WIDTH or TextMeasure.MEASURE_MAX_HEIGHT_FLAG,
    )
    DrawText.apply(buffer, 11, 0, -1, 0, -1, 8f, 20f, false)
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)

    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun floatTextFormattingMatchesAndroidXJavaUtility() {
    val flags =
      TextFromFloat.PAD_AFTER_ZERO or
        TextFromFloat.PAD_PRE_NONE or
        TextFromFloat.GROUPING_BY3 or
        TextFromFloat.OPTIONS_ROUNDING

    val expected =
      StringUtils.floatToString(
        -12345.678f,
        10,
        2,
        0.toChar(),
        '0',
        StringUtils.SEPARATOR_COMMA_PERIOD,
        StringUtils.GROUPING_BY3,
        StringUtils.ROUNDING,
      )

    assertEquals(expected, RcTextFormatter.format(-12345.678f, 10, 2, flags))
  }

  @Test
  fun matrixExpressionEvaluatorMatchesAndroidXJavaForEveryDefinedOperator() {
    val cases =
      listOf(
        floatArrayOf(MatrixOperations.IDENTITY, MatrixOperations.MUL),
        floatArrayOf(30f, MatrixOperations.ROT_X),
        floatArrayOf(40f, MatrixOperations.ROT_Y),
        floatArrayOf(50f, MatrixOperations.ROT_Z),
        floatArrayOf(3f, MatrixOperations.TRANSLATE_X),
        floatArrayOf(4f, MatrixOperations.TRANSLATE_Y),
        floatArrayOf(5f, MatrixOperations.TRANSLATE_Z),
        floatArrayOf(3f, 4f, MatrixOperations.TRANSLATE2),
        floatArrayOf(3f, 4f, 5f, MatrixOperations.TRANSLATE3),
        floatArrayOf(2f, MatrixOperations.SCALE_X),
        floatArrayOf(3f, MatrixOperations.SCALE_Y),
        floatArrayOf(4f, MatrixOperations.SCALE_Z),
        floatArrayOf(2f, 3f, MatrixOperations.SCALE2),
        floatArrayOf(2f, 3f, 4f, MatrixOperations.SCALE3),
        floatArrayOf(
          3f,
          4f,
          MatrixOperations.TRANSLATE2,
          MatrixOperations.IDENTITY,
          2f,
          3f,
          4f,
          MatrixOperations.SCALE3,
          MatrixOperations.MUL,
        ),
        floatArrayOf(45f, 10f, 20f, MatrixOperations.ROT_PZ),
        floatArrayOf(60f, 1f, 2f, 3f, MatrixOperations.ROT_AXIS),
        floatArrayOf(60f, 1.5f, 0.1f, 100f, MatrixOperations.PROJECTION),
      )

    cases.forEachIndexed { caseIndex, expression ->
      val expected = FloatArray(16)
      MatrixOperations().eval(expression).putValues(expected)
      val actual =
        ee.schimke.composeai.rcplayer.runtime.RcMatrixEvaluator.evaluate(
          expression.map { ee.schimke.composeai.rcplayer.protocol.RcFloatWord(it.toRawBits()) }
        ) {
          it.value
        }

      expected.indices.forEach { index ->
        assertTrue(
          kotlin.math.abs(expected[index] - actual[index]) < 0.0001f,
          "case $caseIndex matrix[$index]: expected ${expected[index]}, actual ${actual[index]}",
        )
      }
    }
  }

  @Test
  fun androidXPathExpressionWireRoundTripsExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 100, 100, 1f, 0L)
    PathExpression.apply(
      buffer,
      91,
      floatArrayOf(AnimatedFloatExpression.VAR1, 2f, AnimatedFloatExpression.MUL),
      floatArrayOf(AnimatedFloatExpression.VAR1, AnimatedFloatExpression.SQUARE),
      -2f,
      2f,
      9f,
      PathExpression.MONOTONIC or (1 shl 24),
    )
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)
    val operation = assertIs<PlayerPathExpression>(document.operations.single())

    assertEquals(91, operation.id)
    assertEquals(1, (operation.flags and PlayerPathExpression.WINDING_MASK) shr 24)
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun scalarFloatExpressionOperatorsMatchAndroidXJava() {
    val op = { number: Int ->
      AnimatedFloatExpression.asNan(AnimatedFloatExpression.OFFSET + number)
    }
    val cases =
      listOf(
        floatArrayOf(5f, 2f, op(1)),
        floatArrayOf(5f, 2f, op(2)),
        floatArrayOf(5f, 2f, op(3)),
        floatArrayOf(5f, 2f, op(4)),
        floatArrayOf(5f, 2f, op(5)),
        floatArrayOf(5f, 2f, op(6)),
        floatArrayOf(5f, 2f, op(7)),
        floatArrayOf(5f, 2f, op(8)),
        floatArrayOf(9f, op(9)),
        floatArrayOf(-3f, op(10)),
        floatArrayOf(-3f, op(11)),
        floatArrayOf(3f, -1f, op(12)),
        floatArrayOf(1.2f, op(13)),
        floatArrayOf(-1.2f, op(14)),
        floatArrayOf(100f, op(15)),
        floatArrayOf(2f, op(16)),
        floatArrayOf(-1.5f, op(17)),
        floatArrayOf(.4f, op(18)),
        floatArrayOf(.4f, op(19)),
        floatArrayOf(.4f, op(20)),
        floatArrayOf(.4f, op(21)),
        floatArrayOf(.4f, op(22)),
        floatArrayOf(.4f, op(23)),
        floatArrayOf(.4f, .8f, op(24)),
        floatArrayOf(2f, 3f, 4f, op(25)),
        floatArrayOf(2f, 3f, 1f, op(26)),
        floatArrayOf(5f, 8f, 2f, op(27)),
        floatArrayOf(8f, op(28)),
        floatArrayOf(180f, op(29)),
        floatArrayOf(3.1415927f, op(30)),
        floatArrayOf(1.2f, op(31)),
        floatArrayOf(3f, 4f, op(43)),
        floatArrayOf(3f, 4f, op(44)),
        floatArrayOf(3f, op(45)),
        floatArrayOf(3f, op(46), op(1)),
        floatArrayOf(3f, 4f, op(47)),
        floatArrayOf(3f, 4f, op(48), op(2)),
        floatArrayOf(3f, 7f, .25f, op(49)),
        floatArrayOf(.5f, 1f, 0f, op(50)),
        floatArrayOf(8f, op(51)),
        floatArrayOf(4f, op(52)),
        floatArrayOf(-1.25f, op(53)),
        floatArrayOf(7f, 3f, op(54)),
        floatArrayOf(3f, op(55)),
        floatArrayOf(9f, op(56), op(60)),
        floatArrayOf(2f, op(73)),
        floatArrayOf(.4f, 0f, .2f, 1f, .6f, op(74)),
        floatArrayOf(AnimatedFloatExpression.VAR1, 2f, op(3)),
      )
    cases.forEachIndexed { index, expression ->
      val expected = AnimatedFloatExpression().eval(expression, expression.size, 4f)
      val actual =
        RcFloatExpressionEvaluator()
          .evaluate(expression.map { RcFloatWord(it.toRawBits()) }, floatArrayOf(4f))
      assertFloatCompatible(expected, actual, "float expression case $index")
    }
  }

  @Test
  fun pathExpressionGenerationMatchesAndroidXPathGeneratorInEveryMode() {
    val expressionX =
      floatArrayOf(
        AnimatedFloatExpression.VAR1,
        20f,
        AnimatedFloatExpression.MUL,
        50f,
        AnimatedFloatExpression.ADD,
      )
    val expressionY =
      floatArrayOf(
        AnimatedFloatExpression.VAR1,
        AnimatedFloatExpression.SQUARE,
        8f,
        AnimatedFloatExpression.MUL,
        20f,
        AnimatedFloatExpression.ADD,
      )
    val modes = listOf(0, PathExpression.MONOTONIC, PathExpression.LINEAR)
    modes.forEach { mode ->
      val flags = mode or (1 shl 24)
      val expected = FloatArray(PathGenerator().getReturnLength(7, false))
      PathGenerator().getPath(expected, expressionX, expressionY, -1f, 1f, 7, mode, false, null)
      val operation =
        PlayerPathExpression(
          93,
          flags,
          RcFloatWord.literal(-1f),
          RcFloatWord.literal(1f),
          RcFloatWord.literal(7f),
          expressionX.map { RcFloatWord(it.toRawBits()) },
          expressionY.map { RcFloatWord(it.toRawBits()) },
        )
      val state = RcPlayerState(RcDocument(RcHeader(RcVersion(0, 1, 0)), emptyList()))
      state.applyPathExpression(operation)
      val actual = requireNotNull(state.path(93)).words
      assertEquals(expected.size, actual.size)
      expected.indices.forEach { wordIndex ->
        if (expected[wordIndex].isNaN()) {
          assertEquals(
            expected[wordIndex].toRawBits(),
            actual[wordIndex].bits,
            "mode $mode word $wordIndex",
          )
        } else {
          assertFloatCompatible(
            expected[wordIndex],
            actual[wordIndex].value,
            "mode $mode word $wordIndex",
          )
        }
      }
    }
  }

  @Test
  fun collectionAndRandomFloatExpressionOperatorsMatchAndroidXJava() {
    val firstId = 0x20002a
    val secondId = 0x20002b
    val values =
      mapOf(firstId to floatArrayOf(1f, 3f, 2f, 8f), secondId to floatArrayOf(2f, 4f, 6f, 8f))
    val access =
      object : CollectionsAccess {
        override fun getFloatValue(id: Int, index: Int): Float = values.getValue(id)[index]

        override fun getFloats(id: Int): FloatArray? = values[id]

        override fun getDynamicFloats(id: Int): FloatArray? = values[id]

        override fun getArray(id: Int): ArrayAccess? = null

        override fun getListLength(id: Int): Int = values[id]?.size ?: 0

        override fun getId(listId: Int, index: Int): Int = error("not an id list")
      }
    val op = { number: Int ->
      AnimatedFloatExpression.asNan(AnimatedFloatExpression.OFFSET + number)
    }
    val first = Utils.asNan(firstId)
    val second = Utils.asNan(secondId)
    val cases =
      listOf(
        floatArrayOf(first, 2f, op(32)),
        floatArrayOf(first, op(33)),
        floatArrayOf(first, op(34)),
        floatArrayOf(first, op(35)),
        floatArrayOf(first, op(36)),
        floatArrayOf(first, op(37)),
        floatArrayOf(first, .35f, op(38)),
        floatArrayOf(123.5f, op(40), op(39)),
        floatArrayOf(123.5f, op(41)),
        floatArrayOf(123.5f, op(40), 2f, 5f, op(42)),
        floatArrayOf(first, 1.35f, op(75)),
        floatArrayOf(first, 2f, op(76)),
        floatArrayOf(first, second, op(77)),
        floatArrayOf(first, op(78)),
        floatArrayOf(first, .6f, op(79)),
      )
    cases.forEachIndexed { index, expression ->
      val expected = AnimatedFloatExpression().eval(access, expression, expression.size)
      val actual =
        RcFloatExpressionEvaluator { values[it] }
          .evaluate(expression.map { RcFloatWord(it.toRawBits()) })
      assertFloatCompatible(expected, actual, "collection expression case $index")
    }
  }

  @Test
  fun androidXFloatExpressionWireRoundTripsExactly() {
    val animation =
      FloatAnimation.packToFloatArray(
        2f,
        Easing.CUBIC_CUSTOM,
        floatArrayOf(.1f, .2f, .8f, .9f),
        0f,
        Float.NaN,
      )
    val buffer = WireBuffer()
    Header.apply(buffer, 100, 100, 1f, 0L)
    FloatExpression.apply(buffer, 95, floatArrayOf(3f, 4f, AnimatedFloatExpression.ADD), animation)
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)
    val operation = assertIs<PlayerFloatExpression>(document.operations.single())

    assertEquals(95, operation.id)
    assertEquals(animation.map(Float::toRawBits), operation.animation?.map(RcFloatWord::bits))
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXAnchoredTextWireRoundTripsExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 100, 100, 1f, 0L)
    AndroidxDrawTextAnchored.apply(
      buffer,
      7,
      Utils.asNan(8),
      30f,
      0f,
      Float.NaN,
      AndroidxDrawTextAnchored.ANCHOR_TEXT_RTL or AndroidxDrawTextAnchored.BASELINE_RELATIVE,
    )
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)
    val operation = assertIs<RcDrawTextAnchored>(document.operations.single())

    assertEquals(8, operation.x.referencedId)
    assertEquals(Float.NaN.toRawBits(), operation.panY.bits)
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXTextOnPathWireRoundTripsExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 100, 100, 1f, 0L)
    AndroidxDrawTextOnPath.apply(buffer, 7, 8, Utils.asNan(9), -4f)
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)
    val operation = assertIs<RcDrawTextOnPath>(document.operations.single())

    assertEquals(9, operation.horizontalOffset.referencedId)
    assertEquals(-4f, operation.verticalOffset.value)
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXTextAttributeWireRoundTripsExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 100, 100, 1f, 0L)
    AndroidxTextAttribute.apply(buffer, 81, 7, AndroidxTextAttribute.TEXT_LENGTH)
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)
    val operation = assertIs<RcTextAttribute>(document.operations.single())

    assertEquals(81, operation.outId)
    assertEquals(7, operation.textId)
    assertEquals(6, operation.type)
    assertEquals(0, operation.reserved)
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXInlineBitmapAndDrawWireRoundTripExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 100, 100, 1f, 0L)
    BitmapData.apply(buffer, 71, 2, 2, onePixelPng)
    DrawBitmap.apply(buffer, 71, Utils.asNan(8), 3f, 23f, 27f, 9)
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)
    val data = assertIs<RcBitmapData>(document.operations[0])
    val draw = assertIs<RcDrawBitmap>(document.operations[1])

    assertEquals(2, data.width)
    assertEquals(2, data.height)
    assertContentEquals(onePixelPng, data.data)
    assertEquals(8, draw.left.referencedId)
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXBitmapCropAndScaleWireRoundTripExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 100, 100, 1f, 0L)
    AndroidxDrawBitmapInt.apply(buffer, 71, 1, 2, 21, 22, 3, 4, 43, 44, 9)
    AndroidxDrawBitmapScaled.apply(
      buffer,
      72,
      0f,
      1f,
      20f,
      11f,
      Utils.asNan(8),
      3f,
      100f,
      53f,
      AndroidxDrawBitmapScaled.SCALE_FIT,
      1f,
      10,
    )
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)
    val crop = assertIs<RcDrawBitmapInt>(document.operations[0])
    val scale = assertIs<RcDrawBitmapScaled>(document.operations[1])
    assertEquals(21, crop.srcRight)
    assertEquals(8, scale.dstLeft.referencedId)
    assertEquals(4, scale.scaleType)
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXRawBitmapMetadataAndPayloadRoundTripExactly() {
    val rgba = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    val buffer = WireBuffer()
    Header.apply(buffer, 100, 100, 1f, 0L)
    BitmapData.apply(
      buffer,
      73,
      BitmapData.TYPE_RAW8888,
      2.toShort(),
      BitmapData.ENCODING_INLINE,
      1.toShort(),
      rgba,
    )
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)
    val bitmap = assertIs<RcBitmapData>(document.operations.single())
    assertEquals(RcBitmapData.TYPE_RAW8888, bitmap.type)
    assertEquals(RcBitmapData.ENCODING_INLINE, bitmap.encoding)
    assertContentEquals(rgba, bitmap.data)
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXImageAttributeWireRoundTripsExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 100, 100, 1f, 0L)
    ImageAttribute.apply(buffer, 82, 73, ImageAttribute.IMAGE_HEIGHT, intArrayOf(9, 10))
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)
    val attribute = assertIs<RcImageAttribute>(document.operations.single())
    assertEquals(82, attribute.outId)
    assertEquals(73, attribute.imageId)
    assertEquals(RcImageAttribute.IMAGE_HEIGHT, attribute.type)
    assertEquals(listOf(9, 10), attribute.args)
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXColorAttributeWireRoundTripsExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 100, 100, 1f, 0L)
    ColorAttribute.apply(buffer, 83, 74, ColorAttribute.COLOR_SATURATION)
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)
    val attribute = assertIs<RcColorAttribute>(document.operations.single())
    assertEquals(83, attribute.outId)
    assertEquals(74, attribute.colorId)
    assertEquals(RcColorAttribute.COLOR_SATURATION, attribute.type)
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun everyAndroidXColorExpressionWireModeRoundTripsExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 100, 100, 1f, 0L)
    for (mode in 0..3) {
      AndroidxColorExpression.apply(buffer, 90 + mode, mode, 7, 8, .25f)
    }
    AndroidxColorExpression(94, AndroidxColorExpression.HSV_MODE, 192, .2f, .5f, .75f).write(buffer)
    AndroidxColorExpression.apply(buffer, 95, .5f, .1f, .2f, .3f)
    AndroidxColorExpression.apply(buffer, 96, Utils.asNan(77), .4f, .5f, .6f)
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)
    val expressions = document.operations.filterIsInstance<RcColorExpression>()
    assertEquals((0..6).toList(), expressions.map { it.mode })
    assertEquals(192, expressions[4].modeAndAlpha shr 16)
    assertEquals(77, expressions[6].modeAndAlpha ushr 16)
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXColorThemeWireRoundTripsExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 100, 100, 1f, 0L)
    AndroidxColorTheme.apply(
      buffer,
      97,
      12,
      3.toShort(),
      4.toShort(),
      0xffeeeeee.toInt(),
      0xff111111.toInt(),
    )
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)
    val theme = assertIs<RcColorTheme>(document.operations.single())
    assertEquals(97, theme.outId)
    assertEquals(12, theme.colorGroupId)
    assertEquals(3, theme.lightModeIndex)
    assertEquals(4, theme.darkModeIndex)
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXIntegerExpressionWireRoundTripsExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 100, 100, 1f, 0L)
    val mask = (1 shl 0) or (1 shl 2)
    AndroidxIntegerExpression.apply(buffer, 101, mask, intArrayOf(7, 5, RcIntegerExpression.ADD))
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)
    val expression = assertIs<RcIntegerExpression>(document.operations.single())
    assertEquals(101, expression.outId)
    assertEquals(mask, expression.mask)
    assertEquals(listOf(7, 5, RcIntegerExpression.ADD), expression.values)
    assertEquals(16, RcIntegerExpressionEvaluator.evaluate(expression) { if (it == 7) 11 else 0 })
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXDynamicFloatListWireRoundTripsExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 100, 100, 1f, 0L)
    DataDynamicListFloat.apply(buffer, 0x200070, Utils.asNan(7))
    UpdateDynamicFloatList.apply(buffer, 0x200070, Utils.asNan(8), Utils.asNan(9))
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)
    val list = assertIs<RcDynamicFloatList>(document.operations[0])
    val update = assertIs<RcUpdateDynamicFloatList>(document.operations[1])
    assertEquals(7, list.length.referencedId)
    assertEquals(8, update.index.referencedId)
    assertEquals(9, update.value.referencedId)
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun androidXFloatFunctionWireRoundTripsAndLinksExactly() {
    val buffer = WireBuffer()
    Header.apply(buffer, 100, 100, 1f, 0L)
    AndroidxFloatFunctionDefine.apply(buffer, 0x400001, intArrayOf(120, 121))
    FloatExpression.apply(
      buffer,
      122,
      floatArrayOf(Utils.asNan(120), Utils.asNan(121), AnimatedFloatExpression.ADD),
      null,
    )
    ContainerEnd.apply(buffer)
    AndroidxFloatFunctionCall.apply(buffer, 0x400001, floatArrayOf(Utils.asNan(123), 4f))
    val bytes = buffer.buffer.copyOf(buffer.size())

    val document = RcDocumentCodec.decode(bytes)
    val definition = assertIs<RcFloatFunctionDefine>(document.operations[0])
    val call = assertIs<RcFloatFunctionCall>(document.operations[3])
    assertEquals(listOf(120, 121), definition.parameterIds)
    assertEquals(123, call.arguments[0].referencedId)
    assertEquals(4f, call.arguments[1].value)
    assertContentEquals(bytes, RcDocumentCodec.encode(document))
  }

  @Test
  fun everyStandaloneAndroidXIntegerOperatorMatches() {
    val cases =
      listOf(
        intArrayOf(7, 3, RcIntegerExpression.ADD),
        intArrayOf(7, 3, RcIntegerExpression.SUB),
        intArrayOf(7, 3, RcIntegerExpression.MUL),
        intArrayOf(7, 3, RcIntegerExpression.DIV),
        intArrayOf(7, 0, RcIntegerExpression.DIV),
        intArrayOf(7, 3, RcIntegerExpression.MOD),
        intArrayOf(7, 0, RcIntegerExpression.MOD),
        intArrayOf(1, 4, RcIntegerExpression.SHL),
        intArrayOf(-16, 2, RcIntegerExpression.SHR),
        intArrayOf(-16, 2, RcIntegerExpression.USHR),
        intArrayOf(0x50, 0x0f, RcIntegerExpression.OR),
        intArrayOf(0x5f, 0x0f, RcIntegerExpression.AND),
        intArrayOf(0x50, 0x0f, RcIntegerExpression.XOR),
        intArrayOf(7, -2, RcIntegerExpression.COPY_SIGN),
        intArrayOf(7, 3, RcIntegerExpression.MIN),
        intArrayOf(7, 3, RcIntegerExpression.MAX),
        intArrayOf(-3, RcIntegerExpression.NEG),
        intArrayOf(-7, RcIntegerExpression.ABS),
        intArrayOf(7, RcIntegerExpression.INCR),
        intArrayOf(7, RcIntegerExpression.DECR),
        intArrayOf(7, RcIntegerExpression.NOT),
        intArrayOf(-3, RcIntegerExpression.SIGN),
        intArrayOf(5, 10, 7, RcIntegerExpression.CLAMP),
        intArrayOf(10, 20, 1, RcIntegerExpression.IFELSE),
        intArrayOf(2, 3, 4, RcIntegerExpression.MAD),
      )

    cases.forEach { values ->
      val operatorIndex = values.lastIndex
      val mask = 1 shl operatorIndex
      val expected = AndroidxIntegerExpressionEvaluator().eval(mask, values.copyOf())
      val actual =
        RcIntegerExpressionEvaluator.evaluate(RcIntegerExpression(1, mask, values.toList())) { 0 }
      assertEquals(expected, actual, "operator ${values.last()}")
    }
  }

  @Test
  fun everyAndroidXFloatAnimationCurveMatchesAcrossFrameTimes() {
    val descriptions =
      listOf(
        Easing.CUBIC_STANDARD to null,
        Easing.CUBIC_ACCELERATE to null,
        Easing.CUBIC_DECELERATE to null,
        Easing.CUBIC_LINEAR to null,
        Easing.CUBIC_ANTICIPATE to null,
        Easing.CUBIC_OVERSHOOT to null,
        Easing.CUBIC_CUSTOM to floatArrayOf(.1f, .2f, .8f, .9f),
        Easing.SPLINE_CUSTOM to floatArrayOf(0f, .15f, .7f, 1f),
        Easing.EASE_OUT_BOUNCE to null,
        Easing.EASE_OUT_ELASTIC to null,
      )
    descriptions.forEach { (type, parameters) ->
      val description = FloatAnimation.packToFloatArray(1f, type, parameters, 0f, Float.NaN)
      val expected = FloatAnimation(*description).also { it.setTargetValue(10f) }
      val operation =
        PlayerFloatExpression(
          96,
          listOf(RcFloatWord.literal(10f)),
          description.map { RcFloatWord(it.toRawBits()) },
        )
      val state = RcPlayerState(RcDocument(RcHeader(RcVersion(0, 1, 0)), listOf(operation)))
      listOf(0f, .125f, .25f, .5f, .75f, 1f, 1.25f).forEach { time ->
        state.beginFrame(time)
        state.applyFloatExpression(operation)
        val actual = state.resolve(RcFloatWord(0x7fc00000 or 96))
        assertFloatCompatible(expected.get(time), actual, "animation type $type at $time")
      }
    }
  }

  @Test
  fun androidXSpringFloatAnimationMatchesAcrossFrameTimes() {
    val description = floatArrayOf(0f, 40f, 8f, .001f, Float.fromBits(0))
    val expected = SpringStopEngine(description).also { it.setTargetValue(1f) }
    val operation =
      PlayerFloatExpression(
        97,
        listOf(RcFloatWord.literal(1f)),
        description.map { RcFloatWord(it.toRawBits()) },
      )
    val state = RcPlayerState(RcDocument(RcHeader(RcVersion(0, 1, 0)), listOf(operation)))
    listOf(0f, .016f, .032f, .064f, .125f, .25f, .5f, 1f).forEach { time ->
      state.beginFrame(time)
      state.applyFloatExpression(operation)
      val actual = state.resolve(RcFloatWord(0x7fc00000 or 97))
      assertFloatCompatible(expected.get(time), actual, "spring at $time")
    }
  }

  @Test
  fun floatAnimationRetargetAndWrapMatchAndroidX() {
    val description = FloatAnimation.packToFloatArray(2f, Easing.CUBIC_STANDARD, null, 350f, 360f)
    val expected = FloatAnimation(*description).also { it.setTargetValue(10f) }
    val targetId = 98
    val operation =
      PlayerFloatExpression(
        99,
        listOf(RcFloatWord(0x7fc00000 or targetId)),
        description.map { RcFloatWord(it.toRawBits()) },
      )
    val state = RcPlayerState(RcDocument(RcHeader(RcVersion(0, 1, 0)), listOf(operation)))
    state.setFloat(targetId, 10f)
    listOf(0f, .5f, 1f).forEach { time ->
      state.beginFrame(time)
      state.applyFloatExpression(operation)
      assertFloatCompatible(
        expected.get(time),
        state.resolve(RcFloatWord(0x7fc00000 or 99)),
        "wrapped animation at $time",
      )
    }

    expected.setInitialValue(expected.targetValue)
    expected.setTargetValue(40f)
    state.setFloat(targetId, 40f)
    listOf(1.25f, 1.5f, 2f).forEach { absoluteTime ->
      state.beginFrame(absoluteTime)
      state.applyFloatExpression(operation)
      assertFloatCompatible(
        expected.get(absoluteTime - 1.25f),
        state.resolve(RcFloatWord(0x7fc00000 or 99)),
        "retargeted animation at $absoluteTime",
      )
    }
  }

  private fun assertFloatCompatible(expected: Float, actual: Float, label: String) {
    if (expected.isNaN()) {
      assertTrue(actual.isNaN(), "$label: expected NaN, actual $actual")
    } else {
      assertTrue(
        kotlin.math.abs(expected - actual) <= 0.0001f,
        "$label: expected $expected, actual $actual",
      )
    }
  }
}
