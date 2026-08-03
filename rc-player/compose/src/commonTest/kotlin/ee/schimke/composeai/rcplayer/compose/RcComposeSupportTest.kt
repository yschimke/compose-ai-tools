package ee.schimke.composeai.rcplayer.compose

import ee.schimke.composeai.rcplayer.protocol.RcBitmapData
import ee.schimke.composeai.rcplayer.protocol.RcCanvasLayout
import ee.schimke.composeai.rcplayer.protocol.RcColorAttribute
import ee.schimke.composeai.rcplayer.protocol.RcColorExpression
import ee.schimke.composeai.rcplayer.protocol.RcDimensionType
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcFloatFunctionCall
import ee.schimke.composeai.rcplayer.protocol.RcFloatFunctionDefine
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcImageAttribute
import ee.schimke.composeai.rcplayer.protocol.RcIntegerExpression
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcPaintData
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcTextAttribute
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.rcplayer.protocol.RcWidthModifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RcComposeSupportTest {
  private val header = RcHeader(RcVersion(0, 1, 0), modern = false)

  @Test
  fun reportsPaintSubcommandsThatTheRendererCannotHonor() {
    val document = RcDocument(header, listOf(RcPaintData(listOf(11))))

    val support = document.composeSupportReport()

    assertFalse(support.fullyRenderable)
    assertEquals("paint command 11 is not implemented", support.issues.single().detail)
  }

  @Test
  fun reportsParseOnlyContainerOperations() {
    val document =
      RcDocument(
        header,
        listOf(
          ee.schimke.composeai.rcplayer.protocol.RcIdOperation(RcOpcodes.LAYOUT_CANVAS_CONTENT, 3),
          RcNoArg(RcOpcodes.CONTAINER_END),
        ),
      )

    val support = document.composeSupportReport()

    assertFalse(support.fullyRenderable)
    assertEquals("LayoutCanvasContent", support.issues.single().operation)
  }

  @Test
  fun acceptsTheImplementedBaselinePaintDelta() {
    val colorCommand = 4
    val strokeStyleCommand = 8 or (1 shl 16)
    val document =
      RcDocument(
        header,
        listOf(RcPaintData(listOf(colorCommand, 0xff123456.toInt(), strokeStyleCommand))),
      )

    assertTrue(document.composeSupportReport().fullyRenderable)
  }

  @Test
  fun acceptsRootContentCanvasLayoutWithImplementedDimensions() {
    val document =
      RcDocument(
        header,
        listOf(
          RcRootLayout(1),
          RcLayoutContent(2),
          RcCanvasLayout(3, 30),
          RcWidthModifier(RcDimensionType.EXACT, RcFloatWord.literal(100f)),
          RcNoArg(RcOpcodes.CANVAS_OPERATIONS),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
          RcNoArg(RcOpcodes.CONTAINER_END),
        ),
      )

    assertTrue(document.composeSupportReport().fullyRenderable)
  }

  @Test
  fun rejectsDimensionModesWithoutComposeSemantics() {
    val issue =
      RcDocument(header, listOf(RcWidthModifier(RcDimensionType.WEIGHT, RcFloatWord.literal(1f))))
        .composeSupportReport()
        .issues
        .single()

    assertEquals("WidthModifier", issue.operation)
    assertEquals("dimension type 3 is not implemented", issue.detail)
  }

  @Test
  fun acceptsBuiltInTypefaceAndRejectsUnmappedAndroidFontIds() {
    val typeface = 16 or (600 shl 16)

    assertTrue(
      RcDocument(header, listOf(RcPaintData(listOf(typeface, 3))))
        .composeSupportReport()
        .fullyRenderable
    )
    val issue =
      RcDocument(header, listOf(RcPaintData(listOf(typeface, 42))))
        .composeSupportReport()
        .issues
        .single()
    assertEquals("font id 42 is not implemented", issue.detail)
  }

  @Test
  fun rejectsUnknownTextAttributeModeBeforeRendering() {
    val issue =
      RcDocument(header, listOf(RcTextAttribute(3, 4, 99))).composeSupportReport().issues.single()

    assertEquals("TextMeasurement", issue.operation)
    assertEquals("type 99 is not implemented", issue.detail)
  }

  @Test
  fun rejectsBitmapSourcesThatNeedAnUnconfiguredHost() {
    val issue =
      RcDocument(header, listOf(RcBitmapData(1, 1, 1, RcBitmapData.TYPE_PNG, 1, byteArrayOf(1))))
        .composeSupportReport()
        .issues
        .single()

    assertEquals("encoding 1 requires an image host", issue.detail)
  }

  @Test
  fun rejectsTruncatedRawBitmapBeforeRendering() {
    val issue =
      RcDocument(header, listOf(RcBitmapData(1, 2, 2, RcBitmapData.TYPE_RAW8888, 0, ByteArray(15))))
        .composeSupportReport()
        .issues
        .single()

    assertEquals("raw RGBA payload is truncated", issue.detail)
  }

  @Test
  fun rejectsUnknownImageAttributeMode() {
    val issue =
      RcDocument(header, listOf(RcImageAttribute(2, 1, 7, emptyList())))
        .composeSupportReport()
        .issues
        .single()

    assertEquals("type 7 is not implemented", issue.detail)
  }

  @Test
  fun rejectsUnknownColorAttributeMode() {
    val issue =
      RcDocument(header, listOf(RcColorAttribute(2, 1, 9))).composeSupportReport().issues.single()

    assertEquals("type 9 is not implemented", issue.detail)
  }

  @Test
  fun rejectsUnknownColorExpressionMode() {
    val issue =
      RcDocument(header, listOf(RcColorExpression(2, 9, 0, 0, 0)))
        .composeSupportReport()
        .issues
        .single()

    assertEquals("mode 9 is not implemented", issue.detail)
  }

  @Test
  fun rejectsIntegerVariablesThatRequireCallArguments() {
    val issue =
      RcDocument(header, listOf(RcIntegerExpression(2, 1, listOf(RcIntegerExpression.VAR1))))
        .composeSupportReport()
        .issues
        .single()

    assertEquals("IntegerExpression", issue.operation)
    assertEquals("variable token 24 has no standalone arguments", issue.detail)
  }

  @Test
  fun rejectsMalformedIntegerExpressionStacks() {
    val issue =
      RcDocument(header, listOf(RcIntegerExpression(2, 1, listOf(RcIntegerExpression.ADD))))
        .composeSupportReport()
        .issues
        .single()

    assertEquals("stack underflow at value 0", issue.detail)
  }

  @Test
  fun rejectsMissingAndOverAppliedFloatFunctions() {
    val missing =
      RcDocument(header, listOf(RcFloatFunctionCall(40, emptyList())))
        .composeSupportReport()
        .issues
        .single()
    assertEquals("function 40 is not defined", missing.detail)

    val overApplied =
      RcDocument(
          header,
          listOf(
            RcFloatFunctionDefine(40, listOf(7)),
            RcNoArg(RcOpcodes.CONTAINER_END),
            RcFloatFunctionCall(40, listOf(RcFloatWord.literal(1f), RcFloatWord.literal(2f))),
          ),
        )
        .composeSupportReport()
        .issues
        .single()
    assertEquals("2 arguments exceed 1 parameters", overApplied.detail)
  }
}
