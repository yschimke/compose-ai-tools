package ee.schimke.composeai.rcplayer.protocol

private const val HEADER_MAGIC: Int = 0x048c0000

public data class RcOperationSpec(val opcode: Int, val name: String)

/** Symmetric operation codec. Wire layout comes from AndroidX alpha16 Java sources. */
public interface RcOperationCodec<T : RcOperation> {
  public val spec: RcOperationSpec

  public fun decode(input: RcWireReader): T

  public fun encode(output: RcWireWriter, value: T)
}

public object RcDocumentCodec {
  private val codecs: Map<Int, RcOperationCodec<out RcOperation>> =
    listOf(
        HeaderCodec,
        TextDataCodec,
        BitmapDataCodec,
        FloatConstantCodec,
        FloatExpressionCodec,
        ColorConstantCodec,
        ColorExpressionCodec,
        ColorThemeCodec,
        IntegerConstantCodec,
        IntegerExpressionCodec,
        FloatFunctionDefineCodec,
        FloatFunctionCallCodec,
        BooleanConstantCodec,
        LongConstantCodec,
        IdMapCodec,
        IdListCodec,
        FloatListCodec,
        DynamicFloatListCodec,
        UpdateDynamicFloatListCodec,
        DataMapLookupCodec,
        IdLookupCodec,
        PaintDataCodec,
        ThemeCodec,
        RootContentBehaviorCodec,
        RootContentDescriptionCodec,
        NamedVariableCodec,
        PathDataCodec,
        id(RcOpcodes.DRAW_PATH, "DrawPath"),
        DrawTweenPathCodec,
        id(RcOpcodes.CLIP_PATH, "ClipPath"),
        CanvasContentCodec,
        noArg(RcOpcodes.CANVAS_OPERATIONS, "CanvasOperations"),
        noArg(RcOpcodes.DRAW_CONTENT, "DrawContent"),
        noArg(RcOpcodes.CONTAINER_END, "ContainerEnd"),
        four(RcOpcodes.DRAW_RECT, "DrawRect"),
        three(RcOpcodes.DRAW_CIRCLE, "DrawCircle"),
        four(RcOpcodes.DRAW_LINE, "DrawLine"),
        four(RcOpcodes.DRAW_OVAL, "DrawOval"),
        six(RcOpcodes.DRAW_ROUND_RECT, "DrawRoundRect"),
        six(RcOpcodes.DRAW_ARC, "DrawArc"),
        six(RcOpcodes.DRAW_SECTOR, "DrawSector"),
        PathTweenCodec,
        PathCreateCodec,
        PathAppendCodec,
        PathCombineCodec,
        PathExpressionCodec,
        MatrixFromPathCodec,
        MatrixConstantCodec,
        MatrixExpressionCodec,
        MatrixVectorMathCodec,
        TextMergeCodec,
        TextFromFloatCodec,
        TextLengthCodec,
        TextSubtextCodec,
        TextTransformCodec,
        TextLookupCodec,
        TextLookupIntCodec,
        DrawTextCodec,
        DrawBitmapCodec,
        DrawBitmapIntCodec,
        DrawBitmapScaledCodec,
        DrawTextAnchoredCodec,
        DrawTextOnPathCodec,
        TextMeasureCodec,
        TextAttributeCodec,
        ImageAttributeCodec,
        ColorAttributeCodec,
        RootLayoutCodec,
        LayoutContentCodec,
        CanvasLayoutCodec,
        BoxLayoutCodec,
        RowLayoutCodec,
        ColumnLayoutCodec,
        FlowLayoutCodec,
        CollapsibleRowLayoutCodec,
        CollapsibleColumnLayoutCodec,
        FitBoxLayoutCodec,
        ImageLayoutCodec,
        TextLayoutCodec,
        CoreTextCodec,
        TextStyleCodec,
        WidthModifierCodec,
        HeightModifierCodec,
        PaddingModifierCodec,
        RoundedClipRectModifierCodec,
        BackgroundModifierCodec,
        BorderModifierCodec,
        ClipRectModifierCodec,
        OffsetModifierCodec,
        ZIndexModifierCodec,
        WidthInModifierCodec,
        HeightInModifierCodec,
        DimensionConstraintsModifierCodec,
        CollapsiblePriorityModifierCodec,
        AlignByModifierCodec,
        LayoutComputeCodec,
        VisibilityModifierCodec,
        GraphicsLayerModifierCodec,
        four(RcOpcodes.CLIP_RECT, "ClipRect"),
        four(RcOpcodes.MATRIX_SCALE, "MatrixScale"),
        two(RcOpcodes.MATRIX_TRANSLATE, "MatrixTranslate"),
        two(RcOpcodes.MATRIX_SKEW, "MatrixSkew"),
        three(RcOpcodes.MATRIX_ROTATE, "MatrixRotate"),
        noArg(RcOpcodes.MATRIX_SAVE, "MatrixSave"),
        noArg(RcOpcodes.MATRIX_RESTORE, "MatrixRestore"),
      )
      .associateBy { it.spec.opcode }

  public val supportedOperations: List<RcOperationSpec> =
    codecs.values.map { it.spec }.sortedBy { it.opcode }

  public fun decode(bytes: ByteArray, limits: RcWireLimits = RcWireLimits()): RcDocument {
    if (bytes.size > limits.maxDocumentBytes) {
      throw RcWireException(0, message = "Document exceeds ${limits.maxDocumentBytes} bytes")
    }
    val input = RcWireReader(bytes, limits)
    val operations = mutableListOf<RcOperation>()
    while (input.remaining > 0) {
      val opcodeOffset = input.offset
      val opcode = input.readU8("opcode")
      val codec =
        codecs[opcode]
          ?: throw RcWireException(
            opcodeOffset,
            opcode,
            fieldName = "opcode",
            message = "Unsupported operation",
          )
      val operation = input.inOperation(opcode, codec.spec.name) { decodeUnchecked(codec, this) }
      operations += operation
    }
    val header =
      operations.firstOrNull() as? RcHeader
        ?: throw RcWireException(0, message = "Document must start with Header")
    if (operations.drop(1).any { it is RcHeader }) {
      throw RcWireException(0, RcOpcodes.HEADER, "Header", message = "Header may only appear once")
    }
    return RcDocument(header, operations.drop(1))
  }

  public fun encode(document: RcDocument): ByteArray {
    val output = RcWireWriter()
    encodeOperation(output, document.header)
    document.operations.forEach { encodeOperation(output, it) }
    return output.toByteArray()
  }

  public fun encodeOperation(output: RcWireWriter, operation: RcOperation) {
    val codec = codecs[operation.opcode] ?: error("No codec for opcode ${operation.opcode}")
    output.writeU8(operation.opcode)
    encodeUnchecked(codec, output, operation)
  }

  @Suppress("UNCHECKED_CAST")
  private fun decodeUnchecked(
    codec: RcOperationCodec<out RcOperation>,
    input: RcWireReader,
  ): RcOperation = (codec as RcOperationCodec<RcOperation>).decode(input)

  @Suppress("UNCHECKED_CAST")
  private fun encodeUnchecked(
    codec: RcOperationCodec<out RcOperation>,
    output: RcWireWriter,
    operation: RcOperation,
  ) {
    (codec as RcOperationCodec<RcOperation>).encode(output, operation)
  }
}

private object RootLayoutCodec : RcOperationCodec<RcRootLayout> {
  override val spec = RcOperationSpec(RcOpcodes.LAYOUT_ROOT, "RootLayoutComponent")

  override fun decode(input: RcWireReader) = RcRootLayout(input.readInt("componentId"))

  override fun encode(output: RcWireWriter, value: RcRootLayout) =
    output.writeInt(value.componentId)
}

private object LayoutContentCodec : RcOperationCodec<RcLayoutContent> {
  override val spec = RcOperationSpec(RcOpcodes.LAYOUT_CONTENT, "LayoutComponentContent")

  override fun decode(input: RcWireReader) = RcLayoutContent(input.readInt("componentId"))

  override fun encode(output: RcWireWriter, value: RcLayoutContent) =
    output.writeInt(value.componentId)
}

private object CanvasLayoutCodec : RcOperationCodec<RcCanvasLayout> {
  override val spec = RcOperationSpec(RcOpcodes.LAYOUT_CANVAS, "CanvasLayout")

  override fun decode(input: RcWireReader) =
    RcCanvasLayout(input.readInt("componentId"), input.readInt("animationId"))

  override fun encode(output: RcWireWriter, value: RcCanvasLayout) {
    output.writeInt(value.componentId)
    output.writeInt(value.animationId)
  }
}

private object CanvasContentCodec : RcOperationCodec<RcCanvasContent> {
  override val spec = RcOperationSpec(RcOpcodes.LAYOUT_CANVAS_CONTENT, "CanvasContent")

  override fun decode(input: RcWireReader) = RcCanvasContent(input.readInt("componentId"))

  override fun encode(output: RcWireWriter, value: RcCanvasContent) =
    output.writeInt(value.componentId)
}

private object BoxLayoutCodec : RcOperationCodec<RcBoxLayout> {
  override val spec = RcOperationSpec(RcOpcodes.LAYOUT_BOX, "BoxLayout")

  override fun decode(input: RcWireReader) =
    RcBoxLayout(
      input.readInt("componentId"),
      input.readInt("animationId"),
      input.readInt("horizontalPositioning"),
      input.readInt("verticalPositioning"),
    )

  override fun encode(output: RcWireWriter, value: RcBoxLayout) {
    output.writeInt(value.componentId)
    output.writeInt(value.animationId)
    output.writeInt(value.horizontalPositioning)
    output.writeInt(value.verticalPositioning)
  }
}

private object RowLayoutCodec : RcOperationCodec<RcRowLayout> {
  override val spec = RcOperationSpec(RcOpcodes.LAYOUT_ROW, "RowLayout")

  override fun decode(input: RcWireReader) =
    RcRowLayout(
      input.readInt("componentId"),
      input.readInt("animationId"),
      input.readInt("horizontalPositioning"),
      input.readInt("verticalPositioning"),
      input.readFloatWord("spacedBy"),
    )

  override fun encode(output: RcWireWriter, value: RcRowLayout) {
    output.writeInt(value.componentId)
    output.writeInt(value.animationId)
    output.writeInt(value.horizontalPositioning)
    output.writeInt(value.verticalPositioning)
    output.writeFloatWord(value.spacedBy)
  }
}

private object ColumnLayoutCodec : RcOperationCodec<RcColumnLayout> {
  override val spec = RcOperationSpec(RcOpcodes.LAYOUT_COLUMN, "ColumnLayout")

  override fun decode(input: RcWireReader) =
    RcColumnLayout(
      input.readInt("componentId"),
      input.readInt("animationId"),
      input.readInt("horizontalPositioning"),
      input.readInt("verticalPositioning"),
      input.readFloatWord("spacedBy"),
    )

  override fun encode(output: RcWireWriter, value: RcColumnLayout) {
    output.writeInt(value.componentId)
    output.writeInt(value.animationId)
    output.writeInt(value.horizontalPositioning)
    output.writeInt(value.verticalPositioning)
    output.writeFloatWord(value.spacedBy)
  }
}

private object FlowLayoutCodec : RcOperationCodec<RcFlowLayout> {
  override val spec = RcOperationSpec(RcOpcodes.LAYOUT_FLOW, "FlowLayout")

  override fun decode(input: RcWireReader) =
    RcFlowLayout(
      input.readInt("componentId"),
      input.readInt("animationId"),
      input.readInt("horizontalPositioning"),
      input.readInt("verticalPositioning"),
      input.readFloatWord("spacedBy"),
      input.readInt("maxItemsInEachRow"),
      input.readInt("maxLines"),
    )

  override fun encode(output: RcWireWriter, value: RcFlowLayout) {
    output.writeInt(value.componentId)
    output.writeInt(value.animationId)
    output.writeInt(value.horizontalPositioning)
    output.writeInt(value.verticalPositioning)
    output.writeFloatWord(value.spacedBy)
    output.writeInt(value.maxItemsInEachRow)
    output.writeInt(value.maxLines)
  }
}

private object CollapsibleRowLayoutCodec : RcOperationCodec<RcCollapsibleRowLayout> {
  override val spec = RcOperationSpec(RcOpcodes.LAYOUT_COLLAPSIBLE_ROW, "CollapsibleRowLayout")

  override fun decode(input: RcWireReader) =
    RcCollapsibleRowLayout(
      input.readInt("componentId"),
      input.readInt("animationId"),
      input.readInt("horizontalPositioning"),
      input.readInt("verticalPositioning"),
      input.readFloatWord("spacedBy"),
    )

  override fun encode(output: RcWireWriter, value: RcCollapsibleRowLayout) {
    output.writeInt(value.componentId)
    output.writeInt(value.animationId)
    output.writeInt(value.horizontalPositioning)
    output.writeInt(value.verticalPositioning)
    output.writeFloatWord(value.spacedBy)
  }
}

private object CollapsibleColumnLayoutCodec : RcOperationCodec<RcCollapsibleColumnLayout> {
  override val spec =
    RcOperationSpec(RcOpcodes.LAYOUT_COLLAPSIBLE_COLUMN, "CollapsibleColumnLayout")

  override fun decode(input: RcWireReader) =
    RcCollapsibleColumnLayout(
      input.readInt("componentId"),
      input.readInt("animationId"),
      input.readInt("horizontalPositioning"),
      input.readInt("verticalPositioning"),
      input.readFloatWord("spacedBy"),
    )

  override fun encode(output: RcWireWriter, value: RcCollapsibleColumnLayout) {
    output.writeInt(value.componentId)
    output.writeInt(value.animationId)
    output.writeInt(value.horizontalPositioning)
    output.writeInt(value.verticalPositioning)
    output.writeFloatWord(value.spacedBy)
  }
}

private object FitBoxLayoutCodec : RcOperationCodec<RcFitBoxLayout> {
  override val spec = RcOperationSpec(RcOpcodes.LAYOUT_FIT_BOX, "FitBoxLayout")

  override fun decode(input: RcWireReader) =
    RcFitBoxLayout(
      input.readInt("componentId"),
      input.readInt("animationId"),
      input.readInt("horizontalPositioning"),
      input.readInt("verticalPositioning"),
    )

  override fun encode(output: RcWireWriter, value: RcFitBoxLayout) {
    output.writeInt(value.componentId)
    output.writeInt(value.animationId)
    output.writeInt(value.horizontalPositioning)
    output.writeInt(value.verticalPositioning)
  }
}

private object ImageLayoutCodec : RcOperationCodec<RcImageLayout> {
  override val spec = RcOperationSpec(RcOpcodes.LAYOUT_IMAGE, "ImageLayout")

  override fun decode(input: RcWireReader) =
    RcImageLayout(
      input.readInt("componentId"),
      input.readInt("animationId"),
      input.readInt("bitmapId"),
      input.readInt("scaleType"),
      input.readFloatWord("alpha"),
    )

  override fun encode(output: RcWireWriter, value: RcImageLayout) {
    output.writeInt(value.componentId)
    output.writeInt(value.animationId)
    output.writeInt(value.bitmapId)
    output.writeInt(value.scaleType)
    output.writeFloatWord(value.alpha)
  }
}

private object TextLayoutCodec : RcOperationCodec<RcTextLayout> {
  override val spec = RcOperationSpec(RcOpcodes.LAYOUT_TEXT, "TextLayout")

  override fun decode(input: RcWireReader) =
    RcTextLayout(
      componentId = input.readInt("componentId"),
      animationId = input.readInt("animationId"),
      textId = input.readInt("textId"),
      color = input.readInt("color"),
      fontSize = input.readFloatWord("fontSize"),
      fontStyle = input.readInt("fontStyle"),
      fontWeight = input.readFloatWord("fontWeight"),
      fontFamilyId = input.readInt("fontFamilyId"),
      textAlignAndFlags = input.readInt("textAlignAndFlags"),
      overflow = input.readInt("overflow"),
      maxLines = input.readInt("maxLines"),
    )

  override fun encode(output: RcWireWriter, value: RcTextLayout) {
    output.writeInt(value.componentId)
    output.writeInt(value.animationId)
    output.writeInt(value.textId)
    output.writeInt(value.color)
    output.writeFloatWord(value.fontSize)
    output.writeInt(value.fontStyle)
    output.writeFloatWord(value.fontWeight)
    output.writeInt(value.fontFamilyId)
    output.writeInt(value.textAlignAndFlags)
    output.writeInt(value.overflow)
    output.writeInt(value.maxLines)
  }
}

private object CoreTextCodec : RcOperationCodec<RcCoreText> {
  override val spec = RcOperationSpec(RcOpcodes.CORE_TEXT, "CoreText")

  override fun decode(input: RcWireReader) =
    RcCoreText(input.readInt("textId"), input.readTextStyleProperties("properties"))

  override fun encode(output: RcWireWriter, value: RcCoreText) {
    output.writeInt(value.textId)
    output.writeTextStyleProperties(value.properties)
  }
}

private object TextStyleCodec : RcOperationCodec<RcTextStyle> {
  override val spec = RcOperationSpec(RcOpcodes.TEXT_STYLE, "TextStyle")

  override fun decode(input: RcWireReader) =
    RcTextStyle(input.readTextStyleProperties("properties"))

  override fun encode(output: RcWireWriter, value: RcTextStyle) {
    output.writeTextStyleProperties(value.properties)
  }
}

private val textStyleIntProperties: Set<Int> =
  setOf(1, 2, 3, 4, 6, 8, 9, 10, 11, 15, 16, 17, 23, 24)
private val textStyleFloatProperties: Set<Int> = setOf(5, 7, 12, 13, 14, 25, 26)
private val textStyleBooleanProperties: Set<Int> = setOf(18, 19, 22)

private fun RcWireReader.readTextStyleProperties(field: String): List<RcTextStyleProperty> {
  val count = readU16("$field.count")
  if (count > 26) fail("$field.count", "Invalid text property count $count")
  return List(count) { index ->
    val propertyField = "$field[$index]"
    when (val id = readU8("$propertyField.id")) {
      in textStyleIntProperties -> RcTextStyleProperty.IntValue(id, readInt("$propertyField.value"))
      in textStyleFloatProperties ->
        RcTextStyleProperty.FloatValue(id, readFloatWord("$propertyField.value"))
      in textStyleBooleanProperties ->
        RcTextStyleProperty.BooleanValue(id, readBoolean("$propertyField.value"))
      20 -> {
        val size = readU16("$propertyField.values.count")
        if (size > limits.maxCollectionEntries) {
          fail("$propertyField.values.count", "Invalid font-axis count $size")
        }
        RcTextStyleProperty.IntArrayValue(
          id,
          List(size) { item -> readInt("$propertyField.values[$item]") },
        )
      }
      21 -> {
        val size = readU16("$propertyField.values.count")
        if (size > limits.maxCollectionEntries) {
          fail("$propertyField.values.count", "Invalid font-axis value count $size")
        }
        RcTextStyleProperty.FloatArrayValue(
          id,
          List(size) { item -> readFloatWord("$propertyField.values[$item]") },
        )
      }
      else -> fail("$propertyField.id", "Unknown AndroidX text property $id")
    }
  }
}

private fun RcWireWriter.writeTextStyleProperties(properties: List<RcTextStyleProperty>) {
  require(properties.size <= 26) { "Too many AndroidX text properties" }
  writeU16(properties.size)
  properties.forEach { property ->
    writeU8(property.id)
    when (property) {
      is RcTextStyleProperty.IntValue -> {
        require(property.id in textStyleIntProperties)
        writeInt(property.value)
      }
      is RcTextStyleProperty.FloatValue -> {
        require(property.id in textStyleFloatProperties)
        writeFloatWord(property.value)
      }
      is RcTextStyleProperty.BooleanValue -> {
        require(property.id in textStyleBooleanProperties)
        writeBoolean(property.value)
      }
      is RcTextStyleProperty.IntArrayValue -> {
        require(property.id == 20 && property.values.size <= 0xffff)
        writeU16(property.values.size)
        property.values.forEach(::writeInt)
      }
      is RcTextStyleProperty.FloatArrayValue -> {
        require(property.id == 21 && property.values.size <= 0xffff)
        writeU16(property.values.size)
        property.values.forEach(::writeFloatWord)
      }
    }
  }
}

private object WidthModifierCodec : RcOperationCodec<RcWidthModifier> {
  override val spec = RcOperationSpec(RcOpcodes.MODIFIER_WIDTH, "WidthModifierOperation")

  override fun decode(input: RcWireReader) =
    RcWidthModifier(input.readDimensionType(), input.readFloatWord("value"))

  override fun encode(output: RcWireWriter, value: RcWidthModifier) {
    requireDimensionType(value.type)
    output.writeInt(value.type)
    output.writeFloatWord(value.value)
  }
}

private object HeightModifierCodec : RcOperationCodec<RcHeightModifier> {
  override val spec = RcOperationSpec(RcOpcodes.MODIFIER_HEIGHT, "HeightModifierOperation")

  override fun decode(input: RcWireReader) =
    RcHeightModifier(input.readDimensionType(), input.readFloatWord("value"))

  override fun encode(output: RcWireWriter, value: RcHeightModifier) {
    requireDimensionType(value.type)
    output.writeInt(value.type)
    output.writeFloatWord(value.value)
  }
}

private fun RcWireReader.readDimensionType(): Int =
  readInt("type").also { type ->
    if (type !in RcDimensionType.EXACT..RcDimensionType.FILL_PARENT_MAX_HEIGHT) {
      fail("type", "Unknown AndroidX dimension type $type")
    }
  }

private fun requireDimensionType(type: Int) {
  require(type in RcDimensionType.EXACT..RcDimensionType.FILL_PARENT_MAX_HEIGHT) {
    "Unknown AndroidX dimension type $type"
  }
}

private object PaddingModifierCodec : RcOperationCodec<RcPaddingModifier> {
  override val spec = RcOperationSpec(RcOpcodes.MODIFIER_PADDING, "PaddingModifierOperation")

  override fun decode(input: RcWireReader) =
    RcPaddingModifier(
      input.readFloatWord("left"),
      input.readFloatWord("top"),
      input.readFloatWord("right"),
      input.readFloatWord("bottom"),
    )

  override fun encode(output: RcWireWriter, value: RcPaddingModifier) {
    output.writeFloatWord(value.left)
    output.writeFloatWord(value.top)
    output.writeFloatWord(value.right)
    output.writeFloatWord(value.bottom)
  }
}

private object ClipRectModifierCodec : RcOperationCodec<RcClipRectModifier> {
  override val spec = RcOperationSpec(RcOpcodes.MODIFIER_CLIP_RECT, "ClipRectModifierOperation")

  override fun decode(input: RcWireReader): RcClipRectModifier = RcClipRectModifier

  override fun encode(output: RcWireWriter, value: RcClipRectModifier) = Unit
}

private object RoundedClipRectModifierCodec : RcOperationCodec<RcRoundedClipRectModifier> {
  override val spec =
    RcOperationSpec(RcOpcodes.MODIFIER_ROUNDED_CLIP_RECT, "RoundedClipRectModifierOperation")

  override fun decode(input: RcWireReader) =
    RcRoundedClipRectModifier(
      input.readFloatWord("topStart"),
      input.readFloatWord("topEnd"),
      input.readFloatWord("bottomStart"),
      input.readFloatWord("bottomEnd"),
    )

  override fun encode(output: RcWireWriter, value: RcRoundedClipRectModifier) {
    output.writeFloatWord(value.topStart)
    output.writeFloatWord(value.topEnd)
    output.writeFloatWord(value.bottomStart)
    output.writeFloatWord(value.bottomEnd)
  }
}

private object BackgroundModifierCodec : RcOperationCodec<RcBackgroundModifier> {
  override val spec = RcOperationSpec(RcOpcodes.MODIFIER_BACKGROUND, "BackgroundModifierOperation")

  override fun decode(input: RcWireReader) =
    RcBackgroundModifier(
      input.readInt("flags"),
      input.readInt("colorId"),
      input.readInt("reserved1"),
      input.readInt("reserved2"),
      input.readFloatWord("red"),
      input.readFloatWord("green"),
      input.readFloatWord("blue"),
      input.readFloatWord("alpha"),
      input.readInt("shapeType"),
    )

  override fun encode(output: RcWireWriter, value: RcBackgroundModifier) {
    output.writeInt(value.flags)
    output.writeInt(value.colorId)
    output.writeInt(value.reserved1)
    output.writeInt(value.reserved2)
    output.writeFloatWord(value.red)
    output.writeFloatWord(value.green)
    output.writeFloatWord(value.blue)
    output.writeFloatWord(value.alpha)
    output.writeInt(value.shapeType)
  }
}

private object BorderModifierCodec : RcOperationCodec<RcBorderModifier> {
  override val spec = RcOperationSpec(RcOpcodes.MODIFIER_BORDER, "BorderModifierOperation")

  override fun decode(input: RcWireReader) =
    RcBorderModifier(
      input.readInt("flags"),
      input.readInt("colorId"),
      input.readInt("wireVersion"),
      input.readInt("reserved"),
      input.readFloatWord("borderWidth"),
      input.readFloatWord("roundedCorner"),
      input.readFloatWord("red"),
      input.readFloatWord("green"),
      input.readFloatWord("blue"),
      input.readFloatWord("alpha"),
      input.readInt("shapeType"),
    )

  override fun encode(output: RcWireWriter, value: RcBorderModifier) {
    output.writeInt(value.flags)
    output.writeInt(value.colorId)
    output.writeInt(value.wireVersion)
    output.writeInt(value.reserved)
    output.writeFloatWord(value.borderWidth)
    output.writeFloatWord(value.roundedCorner)
    output.writeFloatWord(value.red)
    output.writeFloatWord(value.green)
    output.writeFloatWord(value.blue)
    output.writeFloatWord(value.alpha)
    output.writeInt(value.shapeType)
  }
}

private object OffsetModifierCodec : RcOperationCodec<RcOffsetModifier> {
  override val spec = RcOperationSpec(RcOpcodes.MODIFIER_OFFSET, "OffsetModifierOperation")

  override fun decode(input: RcWireReader) =
    RcOffsetModifier(input.readFloatWord("x"), input.readFloatWord("y"))

  override fun encode(output: RcWireWriter, value: RcOffsetModifier) {
    output.writeFloatWord(value.x)
    output.writeFloatWord(value.y)
  }
}

private object ZIndexModifierCodec : RcOperationCodec<RcZIndexModifier> {
  override val spec = RcOperationSpec(RcOpcodes.MODIFIER_ZINDEX, "ZIndexModifierOperation")

  override fun decode(input: RcWireReader) = RcZIndexModifier(input.readFloatWord("value"))

  override fun encode(output: RcWireWriter, value: RcZIndexModifier) =
    output.writeFloatWord(value.value)
}

private object WidthInModifierCodec : RcOperationCodec<RcWidthInModifier> {
  override val spec = RcOperationSpec(RcOpcodes.MODIFIER_WIDTH_IN, "WidthInModifierOperation")

  override fun decode(input: RcWireReader) =
    RcWidthInModifier(input.readFloatWord("minimum"), input.readFloatWord("maximum"))

  override fun encode(output: RcWireWriter, value: RcWidthInModifier) {
    output.writeFloatWord(value.minimum)
    output.writeFloatWord(value.maximum)
  }
}

private object HeightInModifierCodec : RcOperationCodec<RcHeightInModifier> {
  override val spec = RcOperationSpec(RcOpcodes.MODIFIER_HEIGHT_IN, "HeightInModifierOperation")

  override fun decode(input: RcWireReader) =
    RcHeightInModifier(input.readFloatWord("minimum"), input.readFloatWord("maximum"))

  override fun encode(output: RcWireWriter, value: RcHeightInModifier) {
    output.writeFloatWord(value.minimum)
    output.writeFloatWord(value.maximum)
  }
}

private object DimensionConstraintsModifierCodec :
  RcOperationCodec<RcDimensionConstraintsModifier> {
  override val spec =
    RcOperationSpec(
      RcOpcodes.MODIFIER_DIMENSION_CONSTRAINTS,
      "DimensionConstraintsModifierOperation",
    )

  override fun decode(input: RcWireReader): RcDimensionConstraintsModifier {
    val type = input.readU8("type")
    if (type !in 0..3) input.fail("type", "Unknown AndroidX dimension constraint type $type")
    return RcDimensionConstraintsModifier(
      type,
      input.readFloatWord("minimum"),
      input.readFloatWord("maximum"),
    )
  }

  override fun encode(output: RcWireWriter, value: RcDimensionConstraintsModifier) {
    require(value.type in 0..3) { "Unknown AndroidX dimension constraint type ${value.type}" }
    output.writeU8(value.type)
    output.writeFloatWord(value.minimum)
    output.writeFloatWord(value.maximum)
  }
}

private object CollapsiblePriorityModifierCodec : RcOperationCodec<RcCollapsiblePriorityModifier> {
  override val spec =
    RcOperationSpec(RcOpcodes.MODIFIER_COLLAPSIBLE_PRIORITY, "CollapsiblePriorityModifierOperation")

  override fun decode(input: RcWireReader) =
    RcCollapsiblePriorityModifier(input.readInt("orientation"), input.readFloatWord("priority"))

  override fun encode(output: RcWireWriter, value: RcCollapsiblePriorityModifier) {
    output.writeInt(value.orientation)
    output.writeFloatWord(value.priority)
  }
}

private object AlignByModifierCodec : RcOperationCodec<RcAlignByModifier> {
  override val spec = RcOperationSpec(RcOpcodes.MODIFIER_ALIGN_BY, "AlignByModifierOperation")

  override fun decode(input: RcWireReader) =
    RcAlignByModifier(input.readFloatWord("line"), input.readInt("flags"))

  override fun encode(output: RcWireWriter, value: RcAlignByModifier) {
    output.writeFloatWord(value.line)
    output.writeInt(value.flags)
  }
}

private object LayoutComputeCodec : RcOperationCodec<RcLayoutCompute> {
  override val spec = RcOperationSpec(RcOpcodes.LAYOUT_COMPUTE, "LayoutComputeOperation")

  override fun decode(input: RcWireReader) =
    RcLayoutCompute(
      input.readInt("type"),
      input.readInt("boundsId"),
      input.readBoolean("animateChanges"),
    )

  override fun encode(output: RcWireWriter, value: RcLayoutCompute) {
    output.writeInt(value.type)
    output.writeInt(value.boundsId)
    output.writeBoolean(value.animateChanges)
  }
}

private object VisibilityModifierCodec : RcOperationCodec<RcVisibilityModifier> {
  override val spec = RcOperationSpec(RcOpcodes.MODIFIER_VISIBILITY, "ComponentVisibilityOperation")

  override fun decode(input: RcWireReader) = RcVisibilityModifier(input.readInt("visibilityId"))

  override fun encode(output: RcWireWriter, value: RcVisibilityModifier) =
    output.writeInt(value.visibilityId)
}

private object GraphicsLayerModifierCodec : RcOperationCodec<RcGraphicsLayerModifier> {
  override val spec =
    RcOperationSpec(RcOpcodes.MODIFIER_GRAPHICS_LAYER, "GraphicsLayerModifierOperation")

  override fun decode(input: RcWireReader): RcGraphicsLayerModifier {
    val count = input.readCount("attributes.count", RcGraphicsLayerModifier.ATTRIBUTE_COUNT)
    return RcGraphicsLayerModifier(
      List(count) { index ->
        val key = input.readInt("attributes[$index].key")
        val type = key ushr 10
        val attributeIndex = key and 63
        if (attributeIndex !in 0 until RcGraphicsLayerModifier.ATTRIBUTE_COUNT) {
          input.fail("attributes[$index].key", "Unknown graphics-layer attribute $attributeIndex")
        }
        when (type) {
          0 ->
            RcGraphicsLayerAttribute.IntValue(
              attributeIndex,
              input.readInt("attributes[$index].intValue"),
            )
          1 ->
            RcGraphicsLayerAttribute.FloatValue(
              attributeIndex,
              input.readFloatWord("attributes[$index].floatValue"),
            )
          else -> input.fail("attributes[$index].key", "Unknown graphics-layer value type $type")
        }
      }
    )
  }

  override fun encode(output: RcWireWriter, value: RcGraphicsLayerModifier) {
    require(value.attributes.size <= RcGraphicsLayerModifier.ATTRIBUTE_COUNT)
    output.writeInt(value.attributes.size)
    value.attributes.forEach { attribute ->
      require(attribute.index in 0 until RcGraphicsLayerModifier.ATTRIBUTE_COUNT)
      when (attribute) {
        is RcGraphicsLayerAttribute.IntValue -> {
          output.writeInt(attribute.index)
          output.writeInt(attribute.value)
        }
        is RcGraphicsLayerAttribute.FloatValue -> {
          output.writeInt(attribute.index or 1024)
          output.writeFloatWord(attribute.value)
        }
      }
    }
  }
}

private object HeaderCodec : RcOperationCodec<RcHeader> {
  override val spec: RcOperationSpec = RcOperationSpec(RcOpcodes.HEADER, "Header")

  override fun decode(input: RcWireReader): RcHeader {
    val encodedMajor = input.readInt("majorVersion")
    val minor = input.readInt("minorVersion")
    val patch = input.readInt("patchVersion")
    if (encodedMajor < 0x10000) {
      return RcHeader(
        RcVersion(encodedMajor, minor, patch),
        legacyWidth = input.readInt("width"),
        legacyHeight = input.readInt("height"),
        legacyCapabilities = input.readLong("capabilities"),
        modern = false,
      )
    }
    if (encodedMajor and -0x10000 != HEADER_MAGIC) {
      input.fail(
        "majorVersion",
        "Invalid header magic 0x${(encodedMajor and -0x10000).toString(16)}",
      )
    }
    val count = input.readCount("properties.count", input.limits.maxTableEntries)
    val properties = ArrayList<RcHeaderProperty>(count)
    repeat(count) { index ->
      val tag = input.readU16("properties[$index].tag")
      val itemLength = input.readU16("properties[$index].itemLength")
      val type = tag ushr 10
      val key = tag and 0x3f // Intentionally mirrors AndroidX Header.readMap.
      val value =
        when (type) {
          0 -> RcHeaderValue.IntValue(input.readInt("properties[$index].int"))
          1 -> RcHeaderValue.FloatValue(input.readFloatWord("properties[$index].float"))
          2 -> RcHeaderValue.LongValue(input.readLong("properties[$index].long"))
          3 -> RcHeaderValue.StringValue(input.readUtf8("properties[$index].string"))
          else -> input.fail("properties[$index].tag", "Unknown header property type $type")
        }
      val expectedLength =
        when (value) {
          is RcHeaderValue.IntValue,
          is RcHeaderValue.FloatValue -> 4
          is RcHeaderValue.LongValue -> 8
          is RcHeaderValue.StringValue -> value.value.encodeToByteArray().size + 4
        }
      if (itemLength != expectedLength) {
        input.fail(
          "properties[$index].itemLength",
          "Invalid item length $itemLength; expected $expectedLength",
        )
      }
      properties += RcHeaderProperty(key, value)
    }
    return RcHeader(RcVersion(encodedMajor and 0xffff, minor, patch), properties, modern = true)
  }

  override fun encode(output: RcWireWriter, value: RcHeader) {
    if (!value.modern) {
      output.writeInt(value.version.major)
      output.writeInt(value.version.minor)
      output.writeInt(value.version.patch)
      output.writeInt(value.legacyWidth)
      output.writeInt(value.legacyHeight)
      output.writeLong(value.legacyCapabilities)
      return
    }
    output.writeInt(HEADER_MAGIC or value.version.major)
    output.writeInt(value.version.minor)
    output.writeInt(value.version.patch)
    output.writeInt(value.properties.size)
    for (property in value.properties) {
      val type =
        when (property.value) {
          is RcHeaderValue.IntValue -> 0
          is RcHeaderValue.FloatValue -> 1
          is RcHeaderValue.LongValue -> 2
          is RcHeaderValue.StringValue -> 3
        }
      output.writeU16(property.key or (type shl 10))
      when (val propertyValue = property.value) {
        is RcHeaderValue.IntValue -> {
          output.writeU16(4)
          output.writeInt(propertyValue.value)
        }
        is RcHeaderValue.FloatValue -> {
          output.writeU16(4)
          output.writeFloatWord(propertyValue.value)
        }
        is RcHeaderValue.LongValue -> {
          output.writeU16(8)
          output.writeLong(propertyValue.value)
        }
        is RcHeaderValue.StringValue -> {
          val encoded = propertyValue.value.encodeToByteArray()
          output.writeU16(encoded.size + 4)
          output.writeByteArray(encoded)
        }
      }
    }
  }
}

private object TextDataCodec : RcOperationCodec<RcTextData> {
  override val spec = RcOperationSpec(RcOpcodes.DATA_TEXT, "TextData")

  override fun decode(input: RcWireReader) = RcTextData(input.readInt("id"), input.readUtf8("text"))

  override fun encode(output: RcWireWriter, value: RcTextData) {
    output.writeInt(value.id)
    output.writeUtf8(value.text)
  }
}

private object FloatConstantCodec : RcOperationCodec<RcFloatConstant> {
  override val spec = RcOperationSpec(RcOpcodes.DATA_FLOAT, "FloatConstant")

  override fun decode(input: RcWireReader) =
    RcFloatConstant(input.readInt("id"), input.readFloatWord("value"))

  override fun encode(output: RcWireWriter, value: RcFloatConstant) {
    output.writeInt(value.id)
    output.writeFloatWord(value.value)
  }
}

private object FloatExpressionCodec : RcOperationCodec<RcFloatExpression> {
  override val spec = RcOperationSpec(RcOpcodes.ANIMATED_FLOAT, "FloatExpression")

  override fun decode(input: RcWireReader): RcFloatExpression {
    val id = input.readInt("id")
    val lengths = input.readInt("lengths")
    val expressionCount = lengths and 0xffff
    val animationCount = lengths ushr 16
    if (expressionCount > 32) input.fail("expression.count", "Float expression too long")
    val expression = List(expressionCount) { input.readFloatWord("expression[$it]") }
    val animation =
      if (animationCount == 0) null
      else List(animationCount) { input.readFloatWord("animation[$it]") }
    return RcFloatExpression(id, expression, animation)
  }

  override fun encode(output: RcWireWriter, value: RcFloatExpression) {
    require(value.expression.size <= 32) { "FloatExpression exceeds AndroidX's 32-word limit" }
    val animationCount = value.animation?.size ?: 0
    require(animationCount <= 0xffff) { "FloatExpression animation exceeds the 16-bit wire length" }
    output.writeInt(value.id)
    output.writeInt(value.expression.size or (animationCount shl 16))
    value.expression.forEach(output::writeFloatWord)
    value.animation?.forEach(output::writeFloatWord)
  }
}

private object ColorConstantCodec : RcOperationCodec<RcColorConstant> {
  override val spec = RcOperationSpec(RcOpcodes.COLOR_CONSTANT, "ColorConstant")

  override fun decode(input: RcWireReader) =
    RcColorConstant(input.readInt("id"), input.readInt("argb"))

  override fun encode(output: RcWireWriter, value: RcColorConstant) {
    output.writeInt(value.id)
    output.writeInt(value.argb)
  }
}

private object ColorExpressionCodec : RcOperationCodec<RcColorExpression> {
  override val spec = RcOperationSpec(RcOpcodes.COLOR_EXPRESSIONS, "ColorExpression")

  override fun decode(input: RcWireReader): RcColorExpression =
    RcColorExpression(
      input.readInt("outId"),
      input.readInt("modeAndAlpha"),
      input.readInt("first"),
      input.readInt("second"),
      input.readInt("third"),
    )

  override fun encode(output: RcWireWriter, value: RcColorExpression) {
    output.writeInt(value.outId)
    output.writeInt(value.modeAndAlpha)
    output.writeInt(value.first)
    output.writeInt(value.second)
    output.writeInt(value.third)
  }
}

private object ColorThemeCodec : RcOperationCodec<RcColorTheme> {
  override val spec = RcOperationSpec(RcOpcodes.COLOR_THEME, "ColorTheme")

  override fun decode(input: RcWireReader): RcColorTheme =
    RcColorTheme(
      input.readInt("outId"),
      input.readInt("colorGroupId"),
      input.readU16("lightModeIndex").toShort().toInt(),
      input.readU16("darkModeIndex").toShort().toInt(),
      input.readInt("lightModeFallback"),
      input.readInt("darkModeFallback"),
    )

  override fun encode(output: RcWireWriter, value: RcColorTheme) {
    require(value.lightModeIndex in Short.MIN_VALUE..Short.MAX_VALUE)
    require(value.darkModeIndex in Short.MIN_VALUE..Short.MAX_VALUE)
    output.writeInt(value.outId)
    output.writeInt(value.colorGroupId)
    output.writeU16(value.lightModeIndex)
    output.writeU16(value.darkModeIndex)
    output.writeInt(value.lightModeFallback)
    output.writeInt(value.darkModeFallback)
  }
}

private object IntegerConstantCodec : RcOperationCodec<RcIntegerConstant> {
  override val spec = RcOperationSpec(RcOpcodes.DATA_INT, "IntegerConstant")

  override fun decode(input: RcWireReader) =
    RcIntegerConstant(input.readInt("id"), input.readInt("value"))

  override fun encode(output: RcWireWriter, value: RcIntegerConstant) {
    output.writeInt(value.id)
    output.writeInt(value.value)
  }
}

private object IntegerExpressionCodec : RcOperationCodec<RcIntegerExpression> {
  override val spec = RcOperationSpec(RcOpcodes.INTEGER_EXPRESSION, "IntegerExpression")

  override fun decode(input: RcWireReader): RcIntegerExpression {
    val outId = input.readInt("outId")
    val mask = input.readInt("mask")
    val count = input.readInt("values.count")
    if (count < 0) input.fail("values.count", "Negative integer-expression size $count")
    if (count > 320)
      input.fail("values.count", "Integer expression exceeds AndroidX's 320-entry limit")
    if (count > input.limits.maxCollectionEntries) {
      input.fail("values.count", "Integer expression exceeds configured collection limit")
    }
    return RcIntegerExpression(outId, mask, List(count) { input.readInt("values[$it]") })
  }

  override fun encode(output: RcWireWriter, value: RcIntegerExpression) {
    require(value.values.size <= 320) { "Integer expression exceeds AndroidX's 320-entry limit" }
    output.writeInt(value.outId)
    output.writeInt(value.mask)
    output.writeInt(value.values.size)
    value.values.forEach(output::writeInt)
  }
}

private object FloatFunctionDefineCodec : RcOperationCodec<RcFloatFunctionDefine> {
  override val spec = RcOperationSpec(RcOpcodes.FUNCTION_DEFINE, "FunctionDefine")

  override fun decode(input: RcWireReader): RcFloatFunctionDefine {
    val id = input.readInt("id")
    val count = input.readCount("parameterIds.count", 32)
    return RcFloatFunctionDefine(id, List(count) { input.readInt("parameterIds[$it]") })
  }

  override fun encode(output: RcWireWriter, value: RcFloatFunctionDefine) {
    require(value.parameterIds.size <= 32) { "FunctionDefine exceeds AndroidX's 32-argument limit" }
    output.writeInt(value.id)
    output.writeInt(value.parameterIds.size)
    value.parameterIds.forEach(output::writeInt)
  }
}

private object FloatFunctionCallCodec : RcOperationCodec<RcFloatFunctionCall> {
  override val spec = RcOperationSpec(RcOpcodes.FUNCTION_CALL, "FunctionCall")

  override fun decode(input: RcWireReader): RcFloatFunctionCall {
    val id = input.readInt("functionId")
    val count = input.readCount("arguments.count", 80)
    return RcFloatFunctionCall(id, List(count) { input.readFloatWord("arguments[$it]") })
  }

  override fun encode(output: RcWireWriter, value: RcFloatFunctionCall) {
    require(value.arguments.size <= 80) { "FunctionCall exceeds AndroidX's 80-argument limit" }
    output.writeInt(value.functionId)
    output.writeInt(value.arguments.size)
    value.arguments.forEach(output::writeFloatWord)
  }
}

private object BooleanConstantCodec : RcOperationCodec<RcBooleanConstant> {
  override val spec = RcOperationSpec(RcOpcodes.DATA_BOOLEAN, "BooleanConstant")

  override fun decode(input: RcWireReader) =
    RcBooleanConstant(input.readInt("id"), input.readBoolean("value"))

  override fun encode(output: RcWireWriter, value: RcBooleanConstant) {
    output.writeInt(value.id)
    output.writeBoolean(value.value)
  }
}

private object LongConstantCodec : RcOperationCodec<RcLongConstant> {
  override val spec = RcOperationSpec(RcOpcodes.DATA_LONG, "LongConstant")

  override fun decode(input: RcWireReader) =
    RcLongConstant(input.readInt("id"), input.readLong("value"))

  override fun encode(output: RcWireWriter, value: RcLongConstant) {
    output.writeInt(value.id)
    output.writeLong(value.value)
  }
}

private object IdMapCodec : RcOperationCodec<RcIdMap> {
  override val spec = RcOperationSpec(RcOpcodes.ID_MAP, "DataMapIds")

  override fun decode(input: RcWireReader): RcIdMap {
    val id = input.readInt("id")
    val count = input.readCount("entries.count", input.limits.maxCollectionEntries)
    return RcIdMap(
      id,
      List(count) { index ->
        RcDataMapEntry(
          input.readUtf8("entries[$index].name"),
          input.readU8("entries[$index].type").also {
            if (it !in RcIdMap.TYPE_STRING..RcIdMap.TYPE_BOOLEAN) {
              input.fail("entries[$index].type", "Unknown AndroidX data-map type $it")
            }
          },
          input.readInt("entries[$index].id"),
        )
      },
    )
  }

  override fun encode(output: RcWireWriter, value: RcIdMap) {
    require(value.entries.size <= 2_000) { "DataMapIds exceeds AndroidX's 2,000-entry limit" }
    output.writeInt(value.id)
    output.writeInt(value.entries.size)
    value.entries.forEach { entry ->
      require(entry.type in RcIdMap.TYPE_STRING..RcIdMap.TYPE_BOOLEAN)
      output.writeUtf8(entry.name)
      output.writeU8(entry.type)
      output.writeInt(entry.id)
    }
  }
}

private object IdListCodec : RcOperationCodec<RcIdList> {
  override val spec = RcOperationSpec(RcOpcodes.ID_LIST, "DataListIds")

  override fun decode(input: RcWireReader): RcIdList {
    val id = input.readInt("id")
    val count = input.readCount("ids.count", input.limits.maxCollectionEntries)
    return RcIdList(id, List(count) { input.readInt("ids[$it]") })
  }

  override fun encode(output: RcWireWriter, value: RcIdList) {
    require(value.ids.size <= 2_000) { "DataListIds exceeds AndroidX's 2,000-entry limit" }
    output.writeInt(value.id)
    output.writeInt(value.ids.size)
    value.ids.forEach(output::writeInt)
  }
}

private object FloatListCodec : RcOperationCodec<RcFloatList> {
  override val spec = RcOperationSpec(RcOpcodes.FLOAT_LIST, "DataListFloat")

  override fun decode(input: RcWireReader): RcFloatList {
    val id = input.readInt("id")
    val count = input.readCount("values.count", input.limits.maxCollectionEntries)
    return RcFloatList(id, List(count) { input.readFloatWord("values[$it]") })
  }

  override fun encode(output: RcWireWriter, value: RcFloatList) {
    require(value.values.size <= 2_000) { "DataListFloat exceeds AndroidX's 2,000-entry limit" }
    output.writeInt(value.id)
    output.writeInt(value.values.size)
    value.values.forEach(output::writeFloatWord)
  }
}

private object DynamicFloatListCodec : RcOperationCodec<RcDynamicFloatList> {
  override val spec = RcOperationSpec(RcOpcodes.DYNAMIC_FLOAT_LIST, "DataDynamicListFloat")

  override fun decode(input: RcWireReader): RcDynamicFloatList {
    val id = input.readInt("id")
    val length = input.readFloatWord("length")
    if (length.referencedId == null && length.value.toInt() !in 0..2_000) {
      input.fail("length", "Dynamic float-list length ${length.value.toInt()} is outside 0..2000")
    }
    return RcDynamicFloatList(id, length)
  }

  override fun encode(output: RcWireWriter, value: RcDynamicFloatList) {
    if (value.length.referencedId == null) {
      require(value.length.value.toInt() in 0..2_000) {
        "DataDynamicListFloat length must be within AndroidX's 0..2,000 limit"
      }
    }
    output.writeInt(value.id)
    output.writeFloatWord(value.length)
  }
}

private object UpdateDynamicFloatListCodec : RcOperationCodec<RcUpdateDynamicFloatList> {
  override val spec = RcOperationSpec(RcOpcodes.UPDATE_DYNAMIC_FLOAT_LIST, "UpdateDynamicFloatList")

  override fun decode(input: RcWireReader): RcUpdateDynamicFloatList =
    RcUpdateDynamicFloatList(
      input.readInt("listId"),
      input.readFloatWord("index"),
      input.readFloatWord("value"),
    )

  override fun encode(output: RcWireWriter, value: RcUpdateDynamicFloatList) {
    output.writeInt(value.listId)
    output.writeFloatWord(value.index)
    output.writeFloatWord(value.value)
  }
}

private object DataMapLookupCodec : RcOperationCodec<RcDataMapLookup> {
  override val spec = RcOperationSpec(RcOpcodes.DATA_MAP_LOOKUP, "DataMapLookup")

  override fun decode(input: RcWireReader) =
    RcDataMapLookup(input.readInt("outId"), input.readInt("mapId"), input.readInt("keyTextId"))

  override fun encode(output: RcWireWriter, value: RcDataMapLookup) {
    output.writeInt(value.outId)
    output.writeInt(value.mapId)
    output.writeInt(value.keyTextId)
  }
}

private object IdLookupCodec : RcOperationCodec<RcIdLookup> {
  override val spec = RcOperationSpec(RcOpcodes.ID_LOOKUP, "IdLookup")

  override fun decode(input: RcWireReader) =
    RcIdLookup(input.readInt("outId"), input.readInt("listId"), input.readFloatWord("index"))

  override fun encode(output: RcWireWriter, value: RcIdLookup) {
    output.writeInt(value.outId)
    output.writeInt(value.listId)
    output.writeFloatWord(value.index)
  }
}

private object PaintDataCodec : RcOperationCodec<RcPaintData> {
  override val spec = RcOperationSpec(RcOpcodes.PAINT_VALUES, "PaintData")

  override fun decode(input: RcWireReader): RcPaintData {
    val count = input.readCount("words.count", input.limits.maxPaintWords)
    if (count == 0) input.fail("words.count", "AndroidX PaintBundle requires at least one word")
    return RcPaintData(List(count) { input.readInt("words[$it]") })
  }

  override fun encode(output: RcWireWriter, value: RcPaintData) {
    require(value.words.isNotEmpty())
    output.writeInt(value.words.size)
    value.words.forEach(output::writeInt)
  }
}

private object ThemeCodec : RcOperationCodec<RcTheme> {
  override val spec = RcOperationSpec(RcOpcodes.THEME, "Theme")

  override fun decode(input: RcWireReader) = RcTheme(input.readInt("theme"))

  override fun encode(output: RcWireWriter, value: RcTheme) = output.writeInt(value.theme)
}

private object RootContentBehaviorCodec : RcOperationCodec<RcRootContentBehavior> {
  override val spec = RcOperationSpec(RcOpcodes.ROOT_CONTENT_BEHAVIOR, "RootContentBehavior")

  override fun decode(input: RcWireReader) =
    RcRootContentBehavior(
      input.readInt("scroll"),
      input.readInt("alignment"),
      input.readInt("sizing"),
      input.readInt("mode"),
    )

  override fun encode(output: RcWireWriter, value: RcRootContentBehavior) {
    output.writeInt(value.scroll)
    output.writeInt(value.alignment)
    output.writeInt(value.sizing)
    output.writeInt(value.mode)
  }
}

private object RootContentDescriptionCodec : RcOperationCodec<RcRootContentDescription> {
  override val spec = RcOperationSpec(RcOpcodes.ROOT_CONTENT_DESCRIPTION, "RootContentDescription")

  override fun decode(input: RcWireReader) = RcRootContentDescription(input.readInt("textId"))

  override fun encode(output: RcWireWriter, value: RcRootContentDescription) =
    output.writeInt(value.textId)
}

private object NamedVariableCodec : RcOperationCodec<RcNamedVariable> {
  override val spec = RcOperationSpec(RcOpcodes.NAMED_VARIABLE, "NamedVariable")

  override fun decode(input: RcWireReader) =
    RcNamedVariable(input.readInt("id"), input.readInt("type"), input.readUtf8("name"))

  override fun encode(output: RcWireWriter, value: RcNamedVariable) {
    output.writeInt(value.id)
    output.writeInt(value.type)
    output.writeUtf8(value.name)
  }
}

private object PathDataCodec : RcOperationCodec<RcPathData> {
  override val spec = RcOperationSpec(RcOpcodes.DATA_PATH, "PathData")

  override fun decode(input: RcWireReader): RcPathData {
    val idAndWinding = input.readInt("idAndWinding")
    val count = input.readCount("words.count", input.limits.maxPathWords)
    return RcPathData(idAndWinding, List(count) { input.readFloatWord("words[$it]") })
  }

  override fun encode(output: RcWireWriter, value: RcPathData) {
    require(value.words.size <= 20_000) { "PathData exceeds AndroidX's 20,000-word limit" }
    output.writeInt(value.idAndWinding)
    output.writeInt(value.words.size)
    value.words.forEach(output::writeFloatWord)
  }
}

private object DrawTweenPathCodec : RcOperationCodec<RcDrawTweenPath> {
  override val spec = RcOperationSpec(RcOpcodes.DRAW_TWEEN_PATH, "DrawTweenPath")

  override fun decode(input: RcWireReader) =
    RcDrawTweenPath(
      input.readInt("path1Id"),
      input.readInt("path2Id"),
      input.readFloatWord("tween"),
      input.readFloatWord("start"),
      input.readFloatWord("stop"),
    )

  override fun encode(output: RcWireWriter, value: RcDrawTweenPath) {
    output.writeInt(value.path1Id)
    output.writeInt(value.path2Id)
    output.writeFloatWord(value.tween)
    output.writeFloatWord(value.start)
    output.writeFloatWord(value.stop)
  }
}

private object PathTweenCodec : RcOperationCodec<RcPathTween> {
  override val spec = RcOperationSpec(RcOpcodes.PATH_TWEEN, "PathTween")

  override fun decode(input: RcWireReader) =
    RcPathTween(
      input.readInt("outId"),
      input.readInt("path1Id"),
      input.readInt("path2Id"),
      input.readFloatWord("tween"),
    )

  override fun encode(output: RcWireWriter, value: RcPathTween) {
    output.writeInt(value.outId)
    output.writeInt(value.path1Id)
    output.writeInt(value.path2Id)
    output.writeFloatWord(value.tween)
  }
}

private object PathCreateCodec : RcOperationCodec<RcPathCreate> {
  override val spec = RcOperationSpec(RcOpcodes.PATH_CREATE, "PathCreate")

  override fun decode(input: RcWireReader) =
    RcPathCreate(input.readInt("id"), input.readFloatWord("startX"), input.readFloatWord("startY"))

  override fun encode(output: RcWireWriter, value: RcPathCreate) {
    output.writeInt(value.id)
    output.writeFloatWord(value.startX)
    output.writeFloatWord(value.startY)
  }
}

private object PathAppendCodec : RcOperationCodec<RcPathAppend> {
  override val spec = RcOperationSpec(RcOpcodes.PATH_ADD, "PathAppend")

  override fun decode(input: RcWireReader): RcPathAppend {
    val id = input.readInt("id")
    val count = input.readCount("words.count", 2_000)
    return RcPathAppend(id, List(count) { input.readFloatWord("words[$it]") })
  }

  override fun encode(output: RcWireWriter, value: RcPathAppend) {
    require(value.words.size <= 2_000) { "PathAppend exceeds AndroidX's 2,000-word limit" }
    output.writeInt(value.id)
    output.writeInt(value.words.size)
    value.words.forEach(output::writeFloatWord)
  }
}

private object PathCombineCodec : RcOperationCodec<RcPathCombine> {
  override val spec = RcOperationSpec(RcOpcodes.PATH_COMBINE, "PathCombine")

  override fun decode(input: RcWireReader) =
    RcPathCombine(
      input.readInt("outId"),
      input.readInt("path1Id"),
      input.readInt("path2Id"),
      input.readU8("operation"),
    )

  override fun encode(output: RcWireWriter, value: RcPathCombine) {
    require(value.operation in 0..4) { "Unknown AndroidX path operation ${value.operation}" }
    output.writeInt(value.outId)
    output.writeInt(value.path1Id)
    output.writeInt(value.path2Id)
    output.writeU8(value.operation)
  }
}

private object MatrixFromPathCodec : RcOperationCodec<RcMatrixFromPath> {
  override val spec = RcOperationSpec(RcOpcodes.MATRIX_FROM_PATH, "MatrixFromPath")

  override fun decode(input: RcWireReader) =
    RcMatrixFromPath(
      input.readInt("pathId"),
      input.readFloatWord("percent"),
      input.readFloatWord("verticalOffset"),
      input.readInt("flags"),
    )

  override fun encode(output: RcWireWriter, value: RcMatrixFromPath) {
    output.writeInt(value.pathId)
    output.writeFloatWord(value.percent)
    output.writeFloatWord(value.verticalOffset)
    output.writeInt(value.flags)
  }
}

private object PathExpressionCodec : RcOperationCodec<RcPathExpression> {
  override val spec = RcOperationSpec(RcOpcodes.PATH_EXPRESSION, "PathExpression")

  override fun decode(input: RcWireReader): RcPathExpression {
    val id = input.readInt("id")
    val flags = input.readInt("flags")
    val min = input.readFloatWord("min")
    val max = input.readFloatWord("max")
    val count = input.readFloatWord("count")
    val xCount = input.readCount("expressionX.count", 32)
    val expressionX = List(xCount) { input.readFloatWord("expressionX[$it]") }
    val yCount = input.readCount("expressionY.count", 32)
    return RcPathExpression(
      id,
      flags,
      min,
      max,
      count,
      expressionX,
      List(yCount) { input.readFloatWord("expressionY[$it]") },
    )
  }

  override fun encode(output: RcWireWriter, value: RcPathExpression) {
    require(value.expressionX.size <= 32) { "PathExpression X exceeds AndroidX's 32-word limit" }
    require(value.expressionY.size <= 32) { "PathExpression Y exceeds AndroidX's 32-word limit" }
    output.writeInt(value.id)
    output.writeInt(value.flags)
    output.writeFloatWord(value.min)
    output.writeFloatWord(value.max)
    output.writeFloatWord(value.count)
    output.writeInt(value.expressionX.size)
    value.expressionX.forEach(output::writeFloatWord)
    output.writeInt(value.expressionY.size)
    value.expressionY.forEach(output::writeFloatWord)
  }
}

private object MatrixConstantCodec : RcOperationCodec<RcMatrixConstant> {
  override val spec = RcOperationSpec(RcOpcodes.MATRIX_CONSTANT, "MatrixConstant")

  override fun decode(input: RcWireReader): RcMatrixConstant {
    val id = input.readInt("id")
    val type = input.readInt("type")
    val count = input.readCount("values.count", 16)
    return RcMatrixConstant(id, type, List(count) { input.readFloatWord("values[$it]") })
  }

  override fun encode(output: RcWireWriter, value: RcMatrixConstant) {
    require(value.values.size <= 16) { "MatrixConstant exceeds AndroidX's 16-value limit" }
    output.writeInt(value.id)
    output.writeInt(value.type)
    output.writeInt(value.values.size)
    value.values.forEach(output::writeFloatWord)
  }
}

private object MatrixExpressionCodec : RcOperationCodec<RcMatrixExpression> {
  override val spec = RcOperationSpec(RcOpcodes.MATRIX_EXPRESSION, "MatrixExpression")

  override fun decode(input: RcWireReader): RcMatrixExpression {
    val id = input.readInt("id")
    val type = input.readInt("type")
    val count = input.readCount("expression.count", 32)
    return RcMatrixExpression(id, type, List(count) { input.readFloatWord("expression[$it]") })
  }

  override fun encode(output: RcWireWriter, value: RcMatrixExpression) {
    require(value.expression.size <= 32) { "MatrixExpression exceeds AndroidX's 32-word limit" }
    output.writeInt(value.id)
    output.writeInt(value.type)
    output.writeInt(value.expression.size)
    value.expression.forEach(output::writeFloatWord)
  }
}

private object MatrixVectorMathCodec : RcOperationCodec<RcMatrixVectorMath> {
  override val spec = RcOperationSpec(RcOpcodes.MATRIX_VECTOR_MATH, "MatrixVectorMath")

  override fun decode(input: RcWireReader): RcMatrixVectorMath {
    val type = input.readU16("type")
    val matrixId = input.readInt("matrixId")
    val outputCount =
      input.readCount("outputs.count", 4).also {
        if (it < 1) input.fail("outputs.count", "AndroidX requires at least one output")
      }
    val outputs = List(outputCount) { input.readInt("outputs[$it]") }
    val inputCount =
      input.readCount("inputs.count", 4).also {
        if (it < 1) input.fail("inputs.count", "AndroidX requires at least one input")
      }
    return RcMatrixVectorMath(
      type,
      outputs,
      matrixId,
      List(inputCount) { input.readFloatWord("inputs[$it]") },
    )
  }

  override fun encode(output: RcWireWriter, value: RcMatrixVectorMath) {
    require(value.outputs.size in 1..4)
    require(value.inputs.size in 1..4)
    output.writeU16(value.type)
    output.writeInt(value.matrixId)
    output.writeInt(value.outputs.size)
    value.outputs.forEach(output::writeInt)
    output.writeInt(value.inputs.size)
    value.inputs.forEach(output::writeFloatWord)
  }
}

private object TextMergeCodec : RcOperationCodec<RcTextMerge> {
  override val spec = RcOperationSpec(RcOpcodes.TEXT_MERGE, "TextMerge")

  override fun decode(input: RcWireReader) =
    RcTextMerge(input.readInt("outId"), input.readInt("leftId"), input.readInt("rightId"))

  override fun encode(output: RcWireWriter, value: RcTextMerge) {
    output.writeInt(value.outId)
    output.writeInt(value.leftId)
    output.writeInt(value.rightId)
  }
}

private object TextFromFloatCodec : RcOperationCodec<RcTextFromFloat> {
  override val spec = RcOperationSpec(RcOpcodes.TEXT_FROM_FLOAT, "TextFromFloat")

  override fun decode(input: RcWireReader): RcTextFromFloat {
    val outId = input.readInt("outId")
    val value = input.readFloatWord("value")
    val digits = input.readInt("digits")
    return RcTextFromFloat(
      outId,
      value,
      (digits ushr 16).toShort().toInt(),
      digits.toShort().toInt(),
      input.readInt("flags"),
    )
  }

  override fun encode(output: RcWireWriter, value: RcTextFromFloat) {
    output.writeInt(value.outId)
    output.writeFloatWord(value.value)
    output.writeInt((value.digitsBefore shl 16) or (value.digitsAfter and 0xffff))
    output.writeInt(value.flags)
  }
}

private object TextLengthCodec : RcOperationCodec<RcTextLength> {
  override val spec = RcOperationSpec(RcOpcodes.TEXT_LENGTH, "TextLength")

  override fun decode(input: RcWireReader) =
    RcTextLength(input.readInt("outId"), input.readInt("textId"))

  override fun encode(output: RcWireWriter, value: RcTextLength) {
    output.writeInt(value.outId)
    output.writeInt(value.textId)
  }
}

private object TextSubtextCodec : RcOperationCodec<RcTextSubtext> {
  override val spec = RcOperationSpec(RcOpcodes.TEXT_SUBTEXT, "TextSubtext")

  override fun decode(input: RcWireReader) =
    RcTextSubtext(
      input.readInt("outId"),
      input.readInt("textId"),
      input.readFloatWord("start"),
      input.readFloatWord("length"),
    )

  override fun encode(output: RcWireWriter, value: RcTextSubtext) {
    output.writeInt(value.outId)
    output.writeInt(value.textId)
    output.writeFloatWord(value.start)
    output.writeFloatWord(value.length)
  }
}

private object TextTransformCodec : RcOperationCodec<RcTextTransform> {
  override val spec = RcOperationSpec(RcOpcodes.TEXT_TRANSFORM, "TextTransform")

  override fun decode(input: RcWireReader) =
    RcTextTransform(
      input.readInt("outId"),
      input.readInt("textId"),
      input.readFloatWord("start"),
      input.readFloatWord("length"),
      input.readInt("operation"),
    )

  override fun encode(output: RcWireWriter, value: RcTextTransform) {
    require(value.operation in 1..5) { "Unknown AndroidX text transform ${value.operation}" }
    output.writeInt(value.outId)
    output.writeInt(value.textId)
    output.writeFloatWord(value.start)
    output.writeFloatWord(value.length)
    output.writeInt(value.operation)
  }
}

private object TextLookupCodec : RcOperationCodec<RcTextLookup> {
  override val spec = RcOperationSpec(RcOpcodes.TEXT_LOOKUP, "TextLookup")

  override fun decode(input: RcWireReader) =
    RcTextLookup(input.readInt("outId"), input.readInt("listId"), input.readFloatWord("index"))

  override fun encode(output: RcWireWriter, value: RcTextLookup) {
    output.writeInt(value.outId)
    output.writeInt(value.listId)
    output.writeFloatWord(value.index)
  }
}

private object TextLookupIntCodec : RcOperationCodec<RcTextLookupInt> {
  override val spec = RcOperationSpec(RcOpcodes.TEXT_LOOKUP_INT, "TextLookupInt")

  override fun decode(input: RcWireReader) =
    RcTextLookupInt(input.readInt("outId"), input.readInt("listId"), input.readInt("indexId"))

  override fun encode(output: RcWireWriter, value: RcTextLookupInt) {
    output.writeInt(value.outId)
    output.writeInt(value.listId)
    output.writeInt(value.indexId)
  }
}

private object DrawTextCodec : RcOperationCodec<RcDrawText> {
  override val spec = RcOperationSpec(RcOpcodes.DRAW_TEXT_RUN, "DrawText")

  override fun decode(input: RcWireReader) =
    RcDrawText(
      input.readInt("textId"),
      input.readInt("start"),
      input.readInt("end"),
      input.readInt("contextStart"),
      input.readInt("contextEnd"),
      input.readFloatWord("x"),
      input.readFloatWord("y"),
      input.readBoolean("rtl"),
    )

  override fun encode(output: RcWireWriter, value: RcDrawText) {
    output.writeInt(value.textId)
    output.writeInt(value.start)
    output.writeInt(value.end)
    output.writeInt(value.contextStart)
    output.writeInt(value.contextEnd)
    output.writeFloatWord(value.x)
    output.writeFloatWord(value.y)
    output.writeBoolean(value.rtl)
  }
}

private object DrawTextAnchoredCodec : RcOperationCodec<RcDrawTextAnchored> {
  override val spec = RcOperationSpec(RcOpcodes.DRAW_TEXT_ANCHOR, "DrawTextAnchored")

  override fun decode(input: RcWireReader) =
    RcDrawTextAnchored(
      input.readInt("textId"),
      input.readFloatWord("x"),
      input.readFloatWord("y"),
      input.readFloatWord("panX"),
      input.readFloatWord("panY"),
      input.readInt("flags"),
    )

  override fun encode(output: RcWireWriter, value: RcDrawTextAnchored) {
    output.writeInt(value.textId)
    output.writeFloatWord(value.x)
    output.writeFloatWord(value.y)
    output.writeFloatWord(value.panX)
    output.writeFloatWord(value.panY)
    output.writeInt(value.flags)
  }
}

private object DrawTextOnPathCodec : RcOperationCodec<RcDrawTextOnPath> {
  override val spec = RcOperationSpec(RcOpcodes.DRAW_TEXT_ON_PATH, "DrawTextOnPath")

  override fun decode(input: RcWireReader): RcDrawTextOnPath {
    val textId = input.readInt("textId")
    val pathId = input.readInt("pathId")
    val verticalOffset = input.readFloatWord("verticalOffset")
    val horizontalOffset = input.readFloatWord("horizontalOffset")
    return RcDrawTextOnPath(textId, pathId, horizontalOffset, verticalOffset)
  }

  override fun encode(output: RcWireWriter, value: RcDrawTextOnPath) {
    output.writeInt(value.textId)
    output.writeInt(value.pathId)
    output.writeFloatWord(value.verticalOffset)
    output.writeFloatWord(value.horizontalOffset)
  }
}

private object TextMeasureCodec : RcOperationCodec<RcTextMeasure> {
  override val spec = RcOperationSpec(RcOpcodes.TEXT_MEASURE, "TextMeasure")

  override fun decode(input: RcWireReader) =
    RcTextMeasure(input.readInt("outId"), input.readInt("textId"), input.readInt("type"))

  override fun encode(output: RcWireWriter, value: RcTextMeasure) {
    require(value.type and 0xff in 0..5) {
      "Unknown AndroidX text measurement ${value.type and 0xff}"
    }
    output.writeInt(value.outId)
    output.writeInt(value.textId)
    output.writeInt(value.type)
  }
}

private object BitmapDataCodec : RcOperationCodec<RcBitmapData> {
  override val spec = RcOperationSpec(RcOpcodes.DATA_BITMAP, "BitmapData")

  override fun decode(input: RcWireReader): RcBitmapData {
    val imageId = input.readInt("imageId")
    val widthAndType = input.readInt("widthAndType")
    val heightAndEncoding = input.readInt("heightAndEncoding")
    val width = widthAndType and 0xffff
    val height = heightAndEncoding and 0xffff
    val type = if (widthAndType > 0xffff) widthAndType ushr 16 else 0
    val encoding = if (heightAndEncoding > 0xffff) heightAndEncoding ushr 16 else 0
    if (
      width !in 1..input.limits.maxImageDimension || height !in 1..input.limits.maxImageDimension
    ) {
      input.fail("dimensions", "Invalid bitmap dimensions ${width}x$height")
    }
    return RcBitmapData(imageId, width, height, type, encoding, input.readByteArray("data"))
  }

  override fun encode(output: RcWireWriter, value: RcBitmapData) {
    require(value.width in 1..0xffff && value.height in 1..0xffff)
    require(value.type in 0..0xffff && value.encoding in 0..0xffff)
    output.writeInt(value.imageId)
    output.writeInt(value.type shl 16 or value.width)
    output.writeInt(value.encoding shl 16 or value.height)
    output.writeByteArray(value.data)
  }
}

private object DrawBitmapCodec : RcOperationCodec<RcDrawBitmap> {
  override val spec = RcOperationSpec(RcOpcodes.DRAW_BITMAP, "DrawBitmap")

  override fun decode(input: RcWireReader) =
    RcDrawBitmap(
      input.readInt("imageId"),
      input.readFloatWord("left"),
      input.readFloatWord("top"),
      input.readFloatWord("right"),
      input.readFloatWord("bottom"),
      input.readInt("contentDescriptionId"),
    )

  override fun encode(output: RcWireWriter, value: RcDrawBitmap) {
    output.writeInt(value.imageId)
    output.writeFloatWord(value.left)
    output.writeFloatWord(value.top)
    output.writeFloatWord(value.right)
    output.writeFloatWord(value.bottom)
    output.writeInt(value.contentDescriptionId)
  }
}

private object DrawBitmapIntCodec : RcOperationCodec<RcDrawBitmapInt> {
  override val spec = RcOperationSpec(RcOpcodes.DRAW_BITMAP_INT, "DrawBitmapInt")

  override fun decode(input: RcWireReader) =
    RcDrawBitmapInt(
      input.readInt("imageId"),
      input.readInt("srcLeft"),
      input.readInt("srcTop"),
      input.readInt("srcRight"),
      input.readInt("srcBottom"),
      input.readInt("dstLeft"),
      input.readInt("dstTop"),
      input.readInt("dstRight"),
      input.readInt("dstBottom"),
      input.readInt("contentDescriptionId"),
    )

  override fun encode(output: RcWireWriter, value: RcDrawBitmapInt) {
    listOf(
        value.imageId,
        value.srcLeft,
        value.srcTop,
        value.srcRight,
        value.srcBottom,
        value.dstLeft,
        value.dstTop,
        value.dstRight,
        value.dstBottom,
        value.contentDescriptionId,
      )
      .forEach(output::writeInt)
  }
}

private object DrawBitmapScaledCodec : RcOperationCodec<RcDrawBitmapScaled> {
  override val spec = RcOperationSpec(RcOpcodes.DRAW_BITMAP_SCALED, "DrawBitmapScaled")

  override fun decode(input: RcWireReader) =
    RcDrawBitmapScaled(
      input.readInt("imageId"),
      input.readFloatWord("srcLeft"),
      input.readFloatWord("srcTop"),
      input.readFloatWord("srcRight"),
      input.readFloatWord("srcBottom"),
      input.readFloatWord("dstLeft"),
      input.readFloatWord("dstTop"),
      input.readFloatWord("dstRight"),
      input.readFloatWord("dstBottom"),
      input.readInt("scaleType"),
      input.readFloatWord("scaleFactor"),
      input.readInt("contentDescriptionId"),
    )

  override fun encode(output: RcWireWriter, value: RcDrawBitmapScaled) {
    require(value.scaleType in 0..7) { "Unknown AndroidX image scale type ${value.scaleType}" }
    output.writeInt(value.imageId)
    listOf(
        value.srcLeft,
        value.srcTop,
        value.srcRight,
        value.srcBottom,
        value.dstLeft,
        value.dstTop,
        value.dstRight,
        value.dstBottom,
      )
      .forEach(output::writeFloatWord)
    output.writeInt(value.scaleType)
    output.writeFloatWord(value.scaleFactor)
    output.writeInt(value.contentDescriptionId)
  }
}

private object TextAttributeCodec : RcOperationCodec<RcTextAttribute> {
  override val spec = RcOperationSpec(RcOpcodes.ATTRIBUTE_TEXT, "TextAttribute")

  override fun decode(input: RcWireReader) =
    RcTextAttribute(
      input.readInt("outId"),
      input.readInt("textId"),
      input.readU16("type").toShort().toInt(),
      input.readU16("reserved"),
    )

  override fun encode(output: RcWireWriter, value: RcTextAttribute) {
    require(value.type in Short.MIN_VALUE..Short.MAX_VALUE) {
      "TextAttribute type does not fit a short"
    }
    require(value.reserved in 0..0xffff) {
      "TextAttribute reserved field does not fit an unsigned short"
    }
    output.writeInt(value.outId)
    output.writeInt(value.textId)
    output.writeU16(value.type)
    output.writeU16(value.reserved)
  }
}

private object ImageAttributeCodec : RcOperationCodec<RcImageAttribute> {
  override val spec = RcOperationSpec(RcOpcodes.ATTRIBUTE_IMAGE, "ImageAttribute")

  override fun decode(input: RcWireReader): RcImageAttribute {
    val outId = input.readInt("outId")
    val imageId = input.readInt("imageId")
    val type = input.readU16("type").toShort().toInt()
    val count = input.readU16("args.count")
    if (count > input.limits.maxCollectionEntries)
      input.fail("args.count", "Too many image arguments $count")
    return RcImageAttribute(outId, imageId, type, List(count) { input.readInt("args[$it]") })
  }

  override fun encode(output: RcWireWriter, value: RcImageAttribute) {
    require(value.type in Short.MIN_VALUE..Short.MAX_VALUE)
    require(value.args.size <= Short.MAX_VALUE)
    output.writeInt(value.outId)
    output.writeInt(value.imageId)
    output.writeU16(value.type)
    output.writeU16(value.args.size)
    value.args.forEach(output::writeInt)
  }
}

private object ColorAttributeCodec : RcOperationCodec<RcColorAttribute> {
  override val spec = RcOperationSpec(RcOpcodes.ATTRIBUTE_COLOR, "ColorAttribute")

  override fun decode(input: RcWireReader): RcColorAttribute =
    RcColorAttribute(
      input.readInt("outId"),
      input.readInt("colorId"),
      input.readU16("type").toShort().toInt(),
    )

  override fun encode(output: RcWireWriter, value: RcColorAttribute) {
    require(value.type in Short.MIN_VALUE..Short.MAX_VALUE) {
      "ColorAttribute type does not fit a short"
    }
    output.writeInt(value.outId)
    output.writeInt(value.colorId)
    output.writeU16(value.type)
  }
}

private fun four(opcode: Int, name: String): RcOperationCodec<RcDraw4> =
  object : RcOperationCodec<RcDraw4> {
    override val spec = RcOperationSpec(opcode, name)

    override fun decode(input: RcWireReader) =
      RcDraw4(
        opcode,
        input.readFloatWord("first"),
        input.readFloatWord("second"),
        input.readFloatWord("third"),
        input.readFloatWord("fourth"),
      )

    override fun encode(output: RcWireWriter, value: RcDraw4) {
      output.writeFloatWord(value.first)
      output.writeFloatWord(value.second)
      output.writeFloatWord(value.third)
      output.writeFloatWord(value.fourth)
    }
  }

private fun three(opcode: Int, name: String): RcOperationCodec<RcDraw3> =
  object : RcOperationCodec<RcDraw3> {
    override val spec = RcOperationSpec(opcode, name)

    override fun decode(input: RcWireReader) =
      RcDraw3(
        opcode,
        input.readFloatWord("first"),
        input.readFloatWord("second"),
        input.readFloatWord("third"),
      )

    override fun encode(output: RcWireWriter, value: RcDraw3) {
      output.writeFloatWord(value.first)
      output.writeFloatWord(value.second)
      output.writeFloatWord(value.third)
    }
  }

private fun six(opcode: Int, name: String): RcOperationCodec<RcDraw6> =
  object : RcOperationCodec<RcDraw6> {
    override val spec = RcOperationSpec(opcode, name)

    override fun decode(input: RcWireReader) =
      RcDraw6(
        opcode,
        input.readFloatWord("first"),
        input.readFloatWord("second"),
        input.readFloatWord("third"),
        input.readFloatWord("fourth"),
        input.readFloatWord("fifth"),
        input.readFloatWord("sixth"),
      )

    override fun encode(output: RcWireWriter, value: RcDraw6) {
      output.writeFloatWord(value.first)
      output.writeFloatWord(value.second)
      output.writeFloatWord(value.third)
      output.writeFloatWord(value.fourth)
      output.writeFloatWord(value.fifth)
      output.writeFloatWord(value.sixth)
    }
  }

private fun two(opcode: Int, name: String): RcOperationCodec<RcTransform2> =
  object : RcOperationCodec<RcTransform2> {
    override val spec = RcOperationSpec(opcode, name)

    override fun decode(input: RcWireReader) =
      RcTransform2(opcode, input.readFloatWord("first"), input.readFloatWord("second"))

    override fun encode(output: RcWireWriter, value: RcTransform2) {
      output.writeFloatWord(value.first)
      output.writeFloatWord(value.second)
    }
  }

private fun noArg(opcode: Int, name: String): RcOperationCodec<RcNoArg> =
  object : RcOperationCodec<RcNoArg> {
    override val spec = RcOperationSpec(opcode, name)

    override fun decode(input: RcWireReader) = RcNoArg(opcode)

    override fun encode(output: RcWireWriter, value: RcNoArg) = Unit
  }

private fun id(opcode: Int, name: String): RcOperationCodec<RcIdOperation> =
  object : RcOperationCodec<RcIdOperation> {
    override val spec = RcOperationSpec(opcode, name)

    override fun decode(input: RcWireReader) = RcIdOperation(opcode, input.readInt("id"))

    override fun encode(output: RcWireWriter, value: RcIdOperation) = output.writeInt(value.id)
  }
