package ee.schimke.composeai.rcplayer.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RcDocumentCodecTest {
  @Test
  fun foundationalLayoutOperationsRoundTripWithoutLosingFloatReferenceBits() {
    val operations =
      listOf(
        RcRootLayout(1),
        RcLayoutContent(2),
        RcCanvasLayout(3, 30),
        RcBoxLayout(4, 40, 1, 5),
        RcRowLayout(5, 50, 6, 2, RcFloatWord(0x7fc0002a)),
        RcColumnLayout(6, 60, 3, 8, RcFloatWord.literal(12.5f)),
        RcFlowLayout(8, 80, 6, 2, RcFloatWord(0x7fc0002b), 3, 2),
        RcFitBoxLayout(7, 70, 2, 4),
        RcNoArg(RcOpcodes.CONTAINER_END),
        RcNoArg(RcOpcodes.CONTAINER_END),
        RcNoArg(RcOpcodes.CONTAINER_END),
        RcNoArg(RcOpcodes.CONTAINER_END),
        RcNoArg(RcOpcodes.CONTAINER_END),
        RcNoArg(RcOpcodes.CONTAINER_END),
        RcNoArg(RcOpcodes.CONTAINER_END),
        RcNoArg(RcOpcodes.CONTAINER_END),
      )
    val document = RcDocument(RcHeader(RcVersion(1, 0, 0), modern = false), operations)

    val bytes = RcDocumentCodec.encode(document)
    val decoded = RcDocumentCodec.decode(bytes)

    assertEquals(document, decoded)
    assertContentEquals(bytes, RcDocumentCodec.encode(decoded))
    assertEquals(42, assertIs<RcRowLayout>(decoded.operations[4]).spacedBy.referencedId)
    assertEquals(43, assertIs<RcFlowLayout>(decoded.operations[6]).spacedBy.referencedId)
  }

  @Test
  fun foundationalLayoutModifiersRoundTripWithoutLosingFloatReferenceBits() {
    val operations =
      listOf(
        RcWidthModifier(RcDimensionType.EXACT, RcFloatWord.literal(80f)),
        RcHeightModifier(RcDimensionType.WEIGHT, RcFloatWord(0x7fc0002a)),
        RcPaddingModifier(
          RcFloatWord.literal(1f),
          RcFloatWord.literal(2f),
          RcFloatWord(0x7fc0002b),
          RcFloatWord.literal(4f),
        ),
      )
    val document = RcDocument(RcHeader(RcVersion(1, 0, 0), modern = false), operations)

    val bytes = RcDocumentCodec.encode(document)
    val decoded = RcDocumentCodec.decode(bytes)

    assertEquals(document, decoded)
    assertContentEquals(bytes, RcDocumentCodec.encode(decoded))
    assertEquals(42, assertIs<RcHeightModifier>(decoded.operations[1]).value.referencedId)
    assertEquals(43, assertIs<RcPaddingModifier>(decoded.operations[2]).right.referencedId)
  }

  @Test
  fun layoutDimensionModifierRejectsUnknownAndroidXType() {
    val header =
      RcDocumentCodec.encode(RcDocument(RcHeader(RcVersion(1, 0, 0), modern = false), emptyList()))
    val writer = RcWireWriter()
    writer.writeU8(RcOpcodes.MODIFIER_WIDTH)
    writer.writeInt(9)
    writer.writeFloatWord(RcFloatWord.literal(10f))

    val failure =
      assertFailsWith<RcWireException> { RcDocumentCodec.decode(header + writer.toByteArray()) }

    assertEquals("WidthModifierOperation", failure.operationName)
    assertEquals("type", failure.fieldName)
  }

  @Test
  fun textAttributePreservesItsReservedWireField() {
    val document =
      RcDocument(
        RcHeader(RcVersion(0, 1, 0), modern = false),
        listOf(RcTextAttribute(8, 9, RcTextAttribute.TEXT_LENGTH, 0xabcd)),
      )

    val encoded = RcDocumentCodec.encode(document)
    val decoded = RcDocumentCodec.decode(encoded)

    assertEquals(0xabcd, (decoded.operations.single() as RcTextAttribute).reserved)
    assertContentEquals(encoded, RcDocumentCodec.encode(decoded))
  }

  @Test
  fun legacyHeaderAndBaselineOperationsRoundTripExactly() {
    val document =
      RcDocument(
        header =
          RcHeader(
            RcVersion(1, 0, 0),
            legacyWidth = 320,
            legacyHeight = 180,
            legacyCapabilities = 7,
            modern = false,
          ),
        operations =
          listOf(
            RcFloatConstant(42, RcFloatWord.literal(12.5f)),
            RcColorConstant(43, 0xff336699.toInt()),
            RcPaintData(listOf(4, 0xff336699.toInt())),
            RcDraw4(
              RcOpcodes.DRAW_RECT,
              RcFloatWord(0x7fc0002a), // AndroidX Utils.asNan(42), preserved as bits.
              RcFloatWord.literal(4f),
              RcFloatWord.literal(100f),
              RcFloatWord.literal(80f),
            ),
          ),
      )

    val bytes = RcDocumentCodec.encode(document)
    val decoded = RcDocumentCodec.decode(bytes)

    assertEquals(document, decoded)
    assertContentEquals(bytes, RcDocumentCodec.encode(decoded))
    val rect = assertIs<RcDraw4>(decoded.operations.last())
    assertEquals(42, rect.first.referencedId)
  }

  @Test
  fun modernHeaderPropertiesRoundTripExactly() {
    val document =
      RcDocument(
        RcHeader(
          RcVersion(1, 2, 0),
          properties =
            listOf(
              RcHeaderProperty(RcHeader.DOC_WIDTH, RcHeaderValue.IntValue(640)),
              RcHeaderProperty(RcHeader.DOC_HEIGHT, RcHeaderValue.IntValue(480)),
              RcHeaderProperty(
                RcHeader.DOC_DENSITY_AT_GENERATION,
                RcHeaderValue.FloatValue(RcFloatWord.literal(2f)),
              ),
              RcHeaderProperty(11, RcHeaderValue.StringValue("androidx-fixture")),
            ),
          modern = true,
        ),
        emptyList(),
      )

    val bytes = RcDocumentCodec.encode(document)
    val decoded = RcDocumentCodec.decode(bytes)

    assertEquals(640, decoded.header.width)
    assertEquals(480, decoded.header.height)
    assertEquals(2f, decoded.header.density)
    assertContentEquals(bytes, RcDocumentCodec.encode(decoded))
  }

  @Test
  fun unsupportedOpcodeReportsItsExactOffset() {
    val header =
      RcDocumentCodec.encode(RcDocument(RcHeader(RcVersion(1, 0, 0), modern = false), emptyList()))
    val bytes = header + byteArrayOf(99)

    val failure = assertFailsWith<RcWireException> { RcDocumentCodec.decode(bytes) }

    assertEquals(header.size, failure.byteOffset)
    assertEquals(99, failure.operationOpcode)
    assertTrue(failure.message!!.contains("Unsupported operation"))
  }

  @Test
  fun truncatedFieldNamesTheOperationAndField() {
    val header =
      RcDocumentCodec.encode(RcDocument(RcHeader(RcVersion(1, 0, 0), modern = false), emptyList()))
    val bytes = header + byteArrayOf(RcOpcodes.DRAW_RECT.toByte(), 0, 0)

    val failure = assertFailsWith<RcWireException> { RcDocumentCodec.decode(bytes) }

    assertEquals("DrawRect", failure.operationName)
    assertEquals("first", failure.fieldName)
  }

  @Test
  fun pathCommandsPaddingAndVariableWordsPreserveTheirRawBits() {
    val path =
      RcPathData(
        idAndWinding = (1 shl 24) or 77,
        words =
          listOf(
            RcFloatWord(0x7fc0000a),
            RcFloatWord.literal(1f),
            RcFloatWord.literal(2f),
            RcFloatWord(0x7fc0000b),
            RcFloatWord(0),
            RcFloatWord(0),
            RcFloatWord(0x7fc0002a),
            RcFloatWord.literal(9f),
          ),
      )
    val document = RcDocument(RcHeader(RcVersion(1, 0, 0), modern = false), listOf(path))

    val decoded = RcDocumentCodec.decode(RcDocumentCodec.encode(document))
    val decodedPath = assertIs<RcPathData>(decoded.operations.single())

    assertEquals(77, decodedPath.id)
    assertEquals(1, decodedPath.winding)
    assertEquals(path.words.map { it.bits }, decodedPath.words.map { it.bits })
    assertEquals(42, decodedPath.words[6].referencedId)
  }

  @Test
  fun pathWordLimitFailsBeforeAllocating() {
    val header =
      RcDocumentCodec.encode(RcDocument(RcHeader(RcVersion(1, 0, 0), modern = false), emptyList()))
    val writer = RcWireWriter()
    writer.writeU8(RcOpcodes.DATA_PATH)
    writer.writeInt(7)
    writer.writeInt(20_001)

    val failure =
      assertFailsWith<RcWireException> { RcDocumentCodec.decode(header + writer.toByteArray()) }

    assertEquals("PathData", failure.operationName)
    assertEquals("words.count", failure.fieldName)
  }

  @Test
  fun integerExpressionCountLimitFailsBeforeAllocating() {
    val header =
      RcDocumentCodec.encode(RcDocument(RcHeader(RcVersion(1, 0, 0), modern = false), emptyList()))
    val writer = RcWireWriter()
    writer.writeU8(RcOpcodes.INTEGER_EXPRESSION)
    writer.writeInt(7)
    writer.writeInt(0)
    writer.writeInt(321)

    val failure =
      assertFailsWith<RcWireException> { RcDocumentCodec.decode(header + writer.toByteArray()) }

    assertEquals("IntegerExpression", failure.operationName)
    assertEquals("values.count", failure.fieldName)
  }

  @Test
  fun integerExpressionEncoderEnforcesTheAndroidXLimit() {
    val expression = RcIntegerExpression(7, 0, List(321) { it })

    assertFailsWith<IllegalArgumentException> {
      RcDocumentCodec.encode(
        RcDocument(RcHeader(RcVersion(1, 0, 0), modern = false), listOf(expression))
      )
    }
  }

  @Test
  fun dynamicFloatListRejectsAnOversizedLiteralLength() {
    val header =
      RcDocumentCodec.encode(RcDocument(RcHeader(RcVersion(1, 0, 0), modern = false), emptyList()))
    val writer = RcWireWriter()
    writer.writeU8(RcOpcodes.DYNAMIC_FLOAT_LIST)
    writer.writeInt(7)
    writer.writeFloatWord(RcFloatWord.literal(2_001f))

    val failure =
      assertFailsWith<RcWireException> { RcDocumentCodec.decode(header + writer.toByteArray()) }

    assertEquals("DataDynamicListFloat", failure.operationName)
    assertEquals("length", failure.fieldName)
  }

  @Test
  fun floatFunctionDefinitionRejectsTooManyParameters() {
    val header =
      RcDocumentCodec.encode(RcDocument(RcHeader(RcVersion(1, 0, 0), modern = false), emptyList()))
    val writer = RcWireWriter()
    writer.writeU8(RcOpcodes.FUNCTION_DEFINE)
    writer.writeInt(7)
    writer.writeInt(33)

    val failure =
      assertFailsWith<RcWireException> { RcDocumentCodec.decode(header + writer.toByteArray()) }

    assertEquals("FunctionDefine", failure.operationName)
    assertEquals("parameterIds.count", failure.fieldName)
  }

  @Test
  fun floatFunctionCallRejectsTooManyArguments() {
    val header =
      RcDocumentCodec.encode(RcDocument(RcHeader(RcVersion(1, 0, 0), modern = false), emptyList()))
    val writer = RcWireWriter()
    writer.writeU8(RcOpcodes.FUNCTION_CALL)
    writer.writeInt(7)
    writer.writeInt(81)

    val failure =
      assertFailsWith<RcWireException> { RcDocumentCodec.decode(header + writer.toByteArray()) }

    assertEquals("FunctionCall", failure.operationName)
    assertEquals("arguments.count", failure.fieldName)
  }
}
