package ee.schimke.composeai.rcplayer.protocol

import kotlin.jvm.JvmInline

/** Raw IEEE-754 word used by RC for both literal floats and NaN-boxed ids. */
@JvmInline
public value class RcFloatWord(public val bits: Int) {
  public val value: Float
    get() = Float.fromBits(bits)

  public val isNaNEncoded: Boolean
    get() = bits and 0x7f800000 == 0x7f800000 && bits and 0x007fffff != 0

  public val referencedId: Int?
    get() = if (isNaNEncoded) bits and 0x003fffff else null

  public companion object {
    public fun literal(value: Float): RcFloatWord = RcFloatWord(value.toRawBits())
  }
}

public data class RcVersion(val major: Int, val minor: Int, val patch: Int)

public sealed interface RcHeaderValue {
  public data class IntValue(val value: Int) : RcHeaderValue

  public data class FloatValue(val value: RcFloatWord) : RcHeaderValue

  public data class LongValue(val value: Long) : RcHeaderValue

  public data class StringValue(val value: String) : RcHeaderValue
}

public data class RcHeaderProperty(val key: Int, val value: RcHeaderValue)

public sealed interface RcOperation {
  public val opcode: Int
}

public data class RcHeader(
  val version: RcVersion,
  val properties: List<RcHeaderProperty> = emptyList(),
  val legacyWidth: Int = 256,
  val legacyHeight: Int = 256,
  val legacyCapabilities: Long = 0,
  val modern: Boolean = properties.isNotEmpty(),
) : RcOperation {
  override val opcode: Int = RcOpcodes.HEADER

  public val width: Int
    get() = intProperty(DOC_WIDTH) ?: legacyWidth

  public val height: Int
    get() = intProperty(DOC_HEIGHT) ?: legacyHeight

  public val density: Float
    get() = floatProperty(DOC_DENSITY_AT_GENERATION) ?: 1f

  public val capabilities: Long
    get() = (property(DOC_CAPABILITIES) as? RcHeaderValue.LongValue)?.value ?: legacyCapabilities

  private fun property(key: Int): RcHeaderValue? = properties.firstOrNull { it.key == key }?.value

  private fun intProperty(key: Int): Int? = (property(key) as? RcHeaderValue.IntValue)?.value

  private fun floatProperty(key: Int): Float? =
    (property(key) as? RcHeaderValue.FloatValue)?.value?.value

  public companion object {
    public const val DOC_WIDTH: Int = 5
    public const val DOC_HEIGHT: Int = 6
    public const val DOC_DENSITY_AT_GENERATION: Int = 7
    // Capabilities are part of the legacy header, not currently a documented map property.
    private const val DOC_CAPABILITIES: Int = -1
  }
}

public data class RcTextData(val id: Int, val text: String) : RcOperation {
  override val opcode: Int = RcOpcodes.DATA_TEXT
}

public data class RcFloatConstant(val id: Int, val value: RcFloatWord) : RcOperation {
  override val opcode: Int = RcOpcodes.DATA_FLOAT
}

/** AndroidX `FloatExpression`, including its optional packed animation description. */
public data class RcFloatExpression(
  val id: Int,
  val expression: List<RcFloatWord>,
  val animation: List<RcFloatWord>?,
) : RcOperation {
  override val opcode: Int = RcOpcodes.ANIMATED_FLOAT
}

/**
 * Immutable wire model for AndroidX `TouchExpression`. Float words remain raw so NaN-boxed variable
 * references and the wrap-mode sentinel round-trip exactly.
 */
public data class RcTouchExpression(
  val id: Int,
  val defaultValue: RcFloatWord,
  val min: RcFloatWord,
  val max: RcFloatWord,
  val velocityId: RcFloatWord,
  val touchEffects: Int,
  val expression: List<RcFloatWord>,
  val stopMode: Int,
  val stopSpec: List<RcFloatWord>,
  val easingSpec: List<RcFloatWord>,
) : RcOperation {
  override val opcode: Int = RcOpcodes.TOUCH_EXPRESSION

  public companion object {
    public const val STOP_GENTLY: Int = 0
    public const val STOP_INSTANTLY: Int = 1
    public const val STOP_ENDS: Int = 2
    public const val STOP_NOTCHES_EVEN: Int = 3
    public const val STOP_NOTCHES_PERCENTS: Int = 4
    public const val STOP_NOTCHES_ABSOLUTE: Int = 5
    public const val STOP_ABSOLUTE_POS: Int = 6
    public const val STOP_NOTCHES_SINGLE_EVEN: Int = 7
  }
}

public data class RcColorConstant(val id: Int, val argb: Int) : RcOperation {
  override val opcode: Int = RcOpcodes.COLOR_CONSTANT
}

/**
 * AndroidX ColorExpression's fixed five-word payload. [modeAndAlpha] keeps the packed mode and
 * alpha/alpha-id bits intact; the remaining words are colors, ids, or raw float words by mode.
 */
public data class RcColorExpression(
  val outId: Int,
  val modeAndAlpha: Int,
  val first: Int,
  val second: Int,
  val third: Int,
) : RcOperation {
  override val opcode: Int = RcOpcodes.COLOR_EXPRESSIONS

  public val mode: Int
    get() = modeAndAlpha and 0xff

  public companion object {
    public const val COLOR_COLOR_INTERPOLATE: Int = 0
    public const val ID_COLOR_INTERPOLATE: Int = 1
    public const val COLOR_ID_INTERPOLATE: Int = 2
    public const val ID_ID_INTERPOLATE: Int = 3
    public const val HSV_MODE: Int = 4
    public const val ARGB_MODE: Int = 5
    public const val IDARGB_MODE: Int = 6
  }
}

public data class RcColorTheme(
  val outId: Int,
  val colorGroupId: Int,
  val lightModeIndex: Int,
  val darkModeIndex: Int,
  val lightModeFallback: Int,
  val darkModeFallback: Int,
) : RcOperation {
  override val opcode: Int = RcOpcodes.COLOR_THEME
}

public data class RcIntegerConstant(val id: Int, val value: Int) : RcOperation {
  override val opcode: Int = RcOpcodes.DATA_INT
}

/**
 * AndroidX integer RPN expression; [mask] marks operation/id slots and repeats every 32 entries.
 */
public data class RcIntegerExpression(val outId: Int, val mask: Int, val values: List<Int>) :
  RcOperation {
  override val opcode: Int = RcOpcodes.INTEGER_EXPRESSION

  public fun isMarked(index: Int): Boolean = mask and (1 shl index) != 0

  public companion object {
    public const val OFFSET: Int = 65536
    public const val ADD: Int = OFFSET + 1
    public const val SUB: Int = OFFSET + 2
    public const val MUL: Int = OFFSET + 3
    public const val DIV: Int = OFFSET + 4
    public const val MOD: Int = OFFSET + 5
    public const val SHL: Int = OFFSET + 6
    public const val SHR: Int = OFFSET + 7
    public const val USHR: Int = OFFSET + 8
    public const val OR: Int = OFFSET + 9
    public const val AND: Int = OFFSET + 10
    public const val XOR: Int = OFFSET + 11
    public const val COPY_SIGN: Int = OFFSET + 12
    public const val MIN: Int = OFFSET + 13
    public const val MAX: Int = OFFSET + 14
    public const val NEG: Int = OFFSET + 15
    public const val ABS: Int = OFFSET + 16
    public const val INCR: Int = OFFSET + 17
    public const val DECR: Int = OFFSET + 18
    public const val NOT: Int = OFFSET + 19
    public const val SIGN: Int = OFFSET + 20
    public const val CLAMP: Int = OFFSET + 21
    public const val IFELSE: Int = OFFSET + 22
    public const val MAD: Int = OFFSET + 23
    public const val VAR1: Int = OFFSET + 24
    public const val VAR2: Int = OFFSET + 25
    public const val VAR3: Int = OFFSET + 26
  }
}

/** Opens a ContainerEnd-delimited function body whose arguments are loaded into [parameterIds]. */
public data class RcFloatFunctionDefine(val id: Int, val parameterIds: List<Int>) : RcOperation {
  override val opcode: Int = RcOpcodes.FUNCTION_DEFINE
}

/** Invokes a linked [RcFloatFunctionDefine] body with literal or referenced float arguments. */
public data class RcFloatFunctionCall(val functionId: Int, val arguments: List<RcFloatWord>) :
  RcOperation {
  override val opcode: Int = RcOpcodes.FUNCTION_CALL
}

public data class RcBooleanConstant(val id: Int, val value: Boolean) : RcOperation {
  override val opcode: Int = RcOpcodes.DATA_BOOLEAN
}

public data class RcLongConstant(val id: Int, val value: Long) : RcOperation {
  override val opcode: Int = RcOpcodes.DATA_LONG
}

public data class RcIdList(val id: Int, val ids: List<Int>) : RcOperation {
  override val opcode: Int = RcOpcodes.ID_LIST
}

public data class RcFloatList(val id: Int, val values: List<RcFloatWord>) : RcOperation {
  override val opcode: Int = RcOpcodes.FLOAT_LIST
}

/** A zero-initialized mutable float list whose length may reference a runtime float. */
public data class RcDynamicFloatList(val id: Int, val length: RcFloatWord) : RcOperation {
  override val opcode: Int = RcOpcodes.DYNAMIC_FLOAT_LIST
}

/** Updates one element of an [RcDynamicFloatList]; invalid indices are ignored by AndroidX. */
public data class RcUpdateDynamicFloatList(
  val listId: Int,
  val index: RcFloatWord,
  val value: RcFloatWord,
) : RcOperation {
  override val opcode: Int = RcOpcodes.UPDATE_DYNAMIC_FLOAT_LIST
}

public data class RcDataMapEntry(val name: String, val type: Int, val id: Int)

public data class RcIdMap(val id: Int, val entries: List<RcDataMapEntry>) : RcOperation {
  override val opcode: Int = RcOpcodes.ID_MAP

  public companion object {
    public const val TYPE_STRING: Int = 0
    public const val TYPE_INT: Int = 1
    public const val TYPE_FLOAT: Int = 2
    public const val TYPE_LONG: Int = 3
    public const val TYPE_BOOLEAN: Int = 4
  }
}

public data class RcDataMapLookup(val outId: Int, val mapId: Int, val keyTextId: Int) :
  RcOperation {
  override val opcode: Int = RcOpcodes.DATA_MAP_LOOKUP
}

public data class RcIdLookup(val outId: Int, val listId: Int, val index: RcFloatWord) :
  RcOperation {
  override val opcode: Int = RcOpcodes.ID_LOOKUP
}

/** Raw AndroidX PaintBundle words. Paint interpretation deliberately lives in the renderer. */
public data class RcPaintData(val words: List<Int>) : RcOperation {
  override val opcode: Int = RcOpcodes.PAINT_VALUES
}

public data class RcTheme(val theme: Int) : RcOperation {
  override val opcode: Int = RcOpcodes.THEME

  public companion object {
    public const val SYSTEM: Int = 0
    public const val UNSPECIFIED: Int = -1
    public const val DARK: Int = -2
    public const val LIGHT: Int = -3
  }
}

public data class RcRootContentBehavior(
  val scroll: Int,
  val alignment: Int,
  val sizing: Int,
  val mode: Int,
) : RcOperation {
  override val opcode: Int = RcOpcodes.ROOT_CONTENT_BEHAVIOR

  public companion object {
    public const val NONE: Int = 0
    public const val SIZING_LAYOUT: Int = 1
    public const val SIZING_SCALE: Int = 2
    public const val ALIGNMENT_TOP: Int = 1
    public const val ALIGNMENT_VERTICAL_CENTER: Int = 2
    public const val ALIGNMENT_BOTTOM: Int = 4
    public const val ALIGNMENT_START: Int = 16
    public const val ALIGNMENT_HORIZONTAL_CENTER: Int = 32
    public const val ALIGNMENT_END: Int = 64
    public const val ALIGNMENT_CENTER: Int = 34
    public const val SCALE_INSIDE: Int = 1
    public const val SCALE_FILL_WIDTH: Int = 2
    public const val SCALE_FILL_HEIGHT: Int = 3
    public const val SCALE_FIT: Int = 4
    public const val SCALE_CROP: Int = 5
    public const val SCALE_FILL_BOUNDS: Int = 6
  }
}

public data class RcRootContentDescription(val textId: Int) : RcOperation {
  override val opcode: Int = RcOpcodes.ROOT_CONTENT_DESCRIPTION
}

public data class RcNamedVariable(val id: Int, val type: Int, val name: String) : RcOperation {
  override val opcode: Int = RcOpcodes.NAMED_VARIABLE

  public companion object {
    public const val STRING_TYPE: Int = 0
    public const val FLOAT_TYPE: Int = 1
    public const val COLOR_TYPE: Int = 2
    public const val IMAGE_TYPE: Int = 3
    public const val INT_TYPE: Int = 4
    public const val LONG_TYPE: Int = 5
    public const val FLOAT_ARRAY_TYPE: Int = 6
  }
}

/** AndroidX PathData payload, including command markers and legacy padding words verbatim. */
public data class RcPathData(val idAndWinding: Int, val words: List<RcFloatWord>) : RcOperation {
  override val opcode: Int = RcOpcodes.DATA_PATH

  public val id: Int
    get() = idAndWinding and 0x00ffffff

  public val winding: Int
    get() = idAndWinding shr 24
}

public data class RcIdOperation(override val opcode: Int, val id: Int) : RcOperation

public data class RcDrawTweenPath(
  val path1Id: Int,
  val path2Id: Int,
  val tween: RcFloatWord,
  val start: RcFloatWord,
  val stop: RcFloatWord,
) : RcOperation {
  override val opcode: Int = RcOpcodes.DRAW_TWEEN_PATH
}

public data class RcPathTween(
  val outId: Int,
  val path1Id: Int,
  val path2Id: Int,
  val tween: RcFloatWord,
) : RcOperation {
  override val opcode: Int = RcOpcodes.PATH_TWEEN
}

public data class RcPathCreate(val id: Int, val startX: RcFloatWord, val startY: RcFloatWord) :
  RcOperation {
  override val opcode: Int = RcOpcodes.PATH_CREATE
}

public data class RcPathAppend(val id: Int, val words: List<RcFloatWord>) : RcOperation {
  override val opcode: Int = RcOpcodes.PATH_ADD
}

public data class RcPathCombine(
  val outId: Int,
  val path1Id: Int,
  val path2Id: Int,
  val operation: Int,
) : RcOperation {
  override val opcode: Int = RcOpcodes.PATH_COMBINE
}

/** AndroidX `PathExpression`: two RPN expressions sampled into a generated path. */
public data class RcPathExpression(
  val id: Int,
  val flags: Int,
  val min: RcFloatWord,
  val max: RcFloatWord,
  val count: RcFloatWord,
  val expressionX: List<RcFloatWord>,
  val expressionY: List<RcFloatWord>,
) : RcOperation {
  override val opcode: Int = RcOpcodes.PATH_EXPRESSION

  public companion object {
    public const val LOOP: Int = 1
    public const val MONOTONIC: Int = 2
    public const val LINEAR: Int = 4
    public const val POLAR: Int = 8
    public const val WINDING_MASK: Int = 0x03000000
  }
}

public data class RcMatrixFromPath(
  val pathId: Int,
  val percent: RcFloatWord,
  val verticalOffset: RcFloatWord,
  val flags: Int,
) : RcOperation {
  override val opcode: Int = RcOpcodes.MATRIX_FROM_PATH
}

public data class RcMatrixConstant(val id: Int, val type: Int, val values: List<RcFloatWord>) :
  RcOperation {
  override val opcode: Int = RcOpcodes.MATRIX_CONSTANT
}

public data class RcMatrixExpression(
  val id: Int,
  val type: Int,
  val expression: List<RcFloatWord>,
) : RcOperation {
  override val opcode: Int = RcOpcodes.MATRIX_EXPRESSION
}

public data class RcMatrixVectorMath(
  val type: Int,
  val outputs: List<Int>,
  val matrixId: Int,
  val inputs: List<RcFloatWord>,
) : RcOperation {
  override val opcode: Int = RcOpcodes.MATRIX_VECTOR_MATH
}

public data class RcTextMerge(val outId: Int, val leftId: Int, val rightId: Int) : RcOperation {
  override val opcode: Int = RcOpcodes.TEXT_MERGE
}

public data class RcTextLength(val outId: Int, val textId: Int) : RcOperation {
  override val opcode: Int = RcOpcodes.TEXT_LENGTH
}

public data class RcTextSubtext(
  val outId: Int,
  val textId: Int,
  val start: RcFloatWord,
  val length: RcFloatWord,
) : RcOperation {
  override val opcode: Int = RcOpcodes.TEXT_SUBTEXT
}

public data class RcTextTransform(
  val outId: Int,
  val textId: Int,
  val start: RcFloatWord,
  val length: RcFloatWord,
  val operation: Int,
) : RcOperation {
  override val opcode: Int = RcOpcodes.TEXT_TRANSFORM
}

public data class RcTextFromFloat(
  val outId: Int,
  val value: RcFloatWord,
  val digitsBefore: Int,
  val digitsAfter: Int,
  val flags: Int,
) : RcOperation {
  override val opcode: Int = RcOpcodes.TEXT_FROM_FLOAT
}

public data class RcTextLookup(val outId: Int, val listId: Int, val index: RcFloatWord) :
  RcOperation {
  override val opcode: Int = RcOpcodes.TEXT_LOOKUP
}

public data class RcTextLookupInt(val outId: Int, val listId: Int, val indexId: Int) : RcOperation {
  override val opcode: Int = RcOpcodes.TEXT_LOOKUP_INT
}

public data class RcDrawText(
  val textId: Int,
  val start: Int,
  val end: Int,
  val contextStart: Int,
  val contextEnd: Int,
  val x: RcFloatWord,
  val y: RcFloatWord,
  val rtl: Boolean,
) : RcOperation {
  override val opcode: Int = RcOpcodes.DRAW_TEXT_RUN
}

public data class RcDrawTextAnchored(
  val textId: Int,
  val x: RcFloatWord,
  val y: RcFloatWord,
  val panX: RcFloatWord,
  val panY: RcFloatWord,
  val flags: Int,
) : RcOperation {
  override val opcode: Int = RcOpcodes.DRAW_TEXT_ANCHOR

  public companion object {
    public const val TEXT_RTL: Int = 1
    public const val MONOSPACE_MEASURE: Int = 2
    public const val MEASURE_EVERY_TIME: Int = 4
    public const val BASELINE_RELATIVE: Int = 8
  }
}

public data class RcDrawTextOnPath(
  val textId: Int,
  val pathId: Int,
  val horizontalOffset: RcFloatWord,
  val verticalOffset: RcFloatWord,
) : RcOperation {
  override val opcode: Int = RcOpcodes.DRAW_TEXT_ON_PATH
}

public data class RcBitmapData(
  val imageId: Int,
  val width: Int,
  val height: Int,
  val type: Int,
  val encoding: Int,
  val data: ByteArray,
) : RcOperation {
  override val opcode: Int = RcOpcodes.DATA_BITMAP

  override fun equals(other: Any?): Boolean =
    other is RcBitmapData &&
      imageId == other.imageId &&
      width == other.width &&
      height == other.height &&
      type == other.type &&
      encoding == other.encoding &&
      data.contentEquals(other.data)

  override fun hashCode(): Int = 31 * imageId + data.contentHashCode()

  public companion object {
    public const val ENCODING_INLINE: Int = 0
    public const val TYPE_PNG_8888: Int = 0
    public const val TYPE_PNG: Int = 1
    public const val TYPE_RAW8: Int = 2
    public const val TYPE_RAW8888: Int = 3
    public const val TYPE_PNG_ALPHA_8: Int = 4
  }
}

public data class RcDrawBitmap(
  val imageId: Int,
  val left: RcFloatWord,
  val top: RcFloatWord,
  val right: RcFloatWord,
  val bottom: RcFloatWord,
  val contentDescriptionId: Int,
) : RcOperation {
  override val opcode: Int = RcOpcodes.DRAW_BITMAP
}

public data class RcDrawBitmapInt(
  val imageId: Int,
  val srcLeft: Int,
  val srcTop: Int,
  val srcRight: Int,
  val srcBottom: Int,
  val dstLeft: Int,
  val dstTop: Int,
  val dstRight: Int,
  val dstBottom: Int,
  val contentDescriptionId: Int,
) : RcOperation {
  override val opcode: Int = RcOpcodes.DRAW_BITMAP_INT
}

public data class RcDrawBitmapScaled(
  val imageId: Int,
  val srcLeft: RcFloatWord,
  val srcTop: RcFloatWord,
  val srcRight: RcFloatWord,
  val srcBottom: RcFloatWord,
  val dstLeft: RcFloatWord,
  val dstTop: RcFloatWord,
  val dstRight: RcFloatWord,
  val dstBottom: RcFloatWord,
  val scaleType: Int,
  val scaleFactor: RcFloatWord,
  val contentDescriptionId: Int,
) : RcOperation {
  override val opcode: Int = RcOpcodes.DRAW_BITMAP_SCALED
}

public data class RcImageAttribute(
  val outId: Int,
  val imageId: Int,
  val type: Int,
  val args: List<Int>,
) : RcOperation {
  override val opcode: Int = RcOpcodes.ATTRIBUTE_IMAGE

  public companion object {
    public const val IMAGE_WIDTH: Int = 0
    public const val IMAGE_HEIGHT: Int = 1
  }
}

public data class RcColorAttribute(val outId: Int, val colorId: Int, val type: Int) : RcOperation {
  override val opcode: Int = RcOpcodes.ATTRIBUTE_COLOR

  public companion object {
    public const val COLOR_HUE: Int = 0
    public const val COLOR_SATURATION: Int = 1
    public const val COLOR_BRIGHTNESS: Int = 2
    public const val COLOR_RED: Int = 3
    public const val COLOR_GREEN: Int = 4
    public const val COLOR_BLUE: Int = 5
    public const val COLOR_ALPHA: Int = 6
  }
}

public data class RcTextMeasure(val outId: Int, val textId: Int, val type: Int) : RcOperation {
  override val opcode: Int = RcOpcodes.TEXT_MEASURE
}

/** AndroidX TextAttribute keeps its trailing reserved short for byte-exact round trips. */
public data class RcTextAttribute(
  val outId: Int,
  val textId: Int,
  val type: Int,
  val reserved: Int = 0,
) : RcOperation {
  override val opcode: Int = RcOpcodes.ATTRIBUTE_TEXT

  public companion object {
    public const val TEXT_LENGTH: Int = 6
  }
}

public data class RcDraw4(
  override val opcode: Int,
  val first: RcFloatWord,
  val second: RcFloatWord,
  val third: RcFloatWord,
  val fourth: RcFloatWord,
) : RcOperation

public data class RcDraw3(
  override val opcode: Int,
  val first: RcFloatWord,
  val second: RcFloatWord,
  val third: RcFloatWord,
) : RcOperation

public data class RcDraw6(
  override val opcode: Int,
  val first: RcFloatWord,
  val second: RcFloatWord,
  val third: RcFloatWord,
  val fourth: RcFloatWord,
  val fifth: RcFloatWord,
  val sixth: RcFloatWord,
) : RcOperation

public data class RcTransform2(
  override val opcode: Int,
  val first: RcFloatWord,
  val second: RcFloatWord,
) : RcOperation

public data class RcNoArg(override val opcode: Int) : RcOperation

/** Component-tree root. Its body is terminated by [RcOpcodes.CONTAINER_END]. */
public data class RcRootLayout(val componentId: Int) : RcOperation {
  override val opcode: Int = RcOpcodes.LAYOUT_ROOT
}

/** Child-component group consumed by a layout manager. */
public data class RcLayoutContent(val componentId: Int) : RcOperation {
  override val opcode: Int = RcOpcodes.LAYOUT_CONTENT
}

/** Canvas component whose body contains drawing and modifier operations. */
public data class RcCanvasLayout(val componentId: Int, val animationId: Int) : RcOperation {
  override val opcode: Int = RcOpcodes.LAYOUT_CANVAS
}

/** Canvas child component whose body is painted in wire order. */
public data class RcCanvasContent(val componentId: Int) : RcOperation {
  override val opcode: Int = RcOpcodes.LAYOUT_CANVAS_CONTENT
}

public data class RcBoxLayout(
  val componentId: Int,
  val animationId: Int,
  val horizontalPositioning: Int,
  val verticalPositioning: Int,
) : RcOperation {
  override val opcode: Int = RcOpcodes.LAYOUT_BOX
}

public data class RcRowLayout(
  val componentId: Int,
  val animationId: Int,
  val horizontalPositioning: Int,
  val verticalPositioning: Int,
  val spacedBy: RcFloatWord,
) : RcOperation {
  override val opcode: Int = RcOpcodes.LAYOUT_ROW
}

public data class RcColumnLayout(
  val componentId: Int,
  val animationId: Int,
  val horizontalPositioning: Int,
  val verticalPositioning: Int,
  val spacedBy: RcFloatWord,
) : RcOperation {
  override val opcode: Int = RcOpcodes.LAYOUT_COLUMN
}

/** Horizontal layout that wraps children into additional rows when space is exhausted. */
public data class RcFlowLayout(
  val componentId: Int,
  val animationId: Int,
  val horizontalPositioning: Int,
  val verticalPositioning: Int,
  val spacedBy: RcFloatWord,
  val maxItemsInEachRow: Int,
  val maxLines: Int,
) : RcOperation {
  override val opcode: Int = RcOpcodes.LAYOUT_FLOW
}

/** Row that omits lower-priority children when its measured width is exhausted. */
public data class RcCollapsibleRowLayout(
  val componentId: Int,
  val animationId: Int,
  val horizontalPositioning: Int,
  val verticalPositioning: Int,
  val spacedBy: RcFloatWord,
) : RcOperation {
  override val opcode: Int = RcOpcodes.LAYOUT_COLLAPSIBLE_ROW
}

/** Column that omits lower-priority children when its measured height is exhausted. */
public data class RcCollapsibleColumnLayout(
  val componentId: Int,
  val animationId: Int,
  val horizontalPositioning: Int,
  val verticalPositioning: Int,
  val spacedBy: RcFloatWord,
) : RcOperation {
  override val opcode: Int = RcOpcodes.LAYOUT_COLLAPSIBLE_COLUMN
}

/** Selects the first child that fits the available size. */
public data class RcFitBoxLayout(
  val componentId: Int,
  val animationId: Int,
  val horizontalPositioning: Int,
  val verticalPositioning: Int,
) : RcOperation {
  override val opcode: Int = RcOpcodes.LAYOUT_FIT_BOX
}

public data class RcImageLayout(
  val componentId: Int,
  val animationId: Int,
  val bitmapId: Int,
  val scaleType: Int,
  val alpha: RcFloatWord,
) : RcOperation {
  override val opcode: Int = RcOpcodes.LAYOUT_IMAGE
}

/** AndroidX alpha16's original self-contained text layout component. */
public data class RcTextLayout(
  val componentId: Int,
  val animationId: Int,
  val textId: Int,
  /** Literal ARGB unless [FLAG_DYNAMIC_COLOR] is present in [textAlignAndFlags]. */
  val color: Int,
  val fontSize: RcFloatWord,
  val fontStyle: Int,
  val fontWeight: RcFloatWord,
  val fontFamilyId: Int,
  val textAlignAndFlags: Int,
  val overflow: Int,
  val maxLines: Int,
) : RcOperation {
  override val opcode: Int = RcOpcodes.LAYOUT_TEXT

  public val textAlign: Int
    get() = textAlignAndFlags and 0xffff

  public val flags: Int
    get() = textAlignAndFlags ushr 16

  public companion object {
    public const val ALIGN_LEFT: Int = 1
    public const val ALIGN_RIGHT: Int = 2
    public const val ALIGN_CENTER: Int = 3
    public const val ALIGN_JUSTIFY: Int = 4
    public const val ALIGN_START: Int = 5
    public const val ALIGN_END: Int = 6

    public const val OVERFLOW_CLIP: Int = 1
    public const val OVERFLOW_VISIBLE: Int = 2
    public const val OVERFLOW_ELLIPSIS: Int = 3
    public const val OVERFLOW_START_ELLIPSIS: Int = 4
    public const val OVERFLOW_MIDDLE_ELLIPSIS: Int = 5

    public const val FLAG_DYNAMIC_COLOR: Int = 1
  }
}

public sealed interface RcTextStyleProperty {
  public val id: Int

  public data class IntValue(override val id: Int, val value: Int) : RcTextStyleProperty

  public data class FloatValue(override val id: Int, val value: RcFloatWord) : RcTextStyleProperty

  public data class BooleanValue(override val id: Int, val value: Boolean) : RcTextStyleProperty

  public data class IntArrayValue(override val id: Int, val values: List<Int>) : RcTextStyleProperty

  public data class FloatArrayValue(override val id: Int, val values: List<RcFloatWord>) :
    RcTextStyleProperty
}

/** Sparse reusable style. Property 24 is the optional parent style id. */
public data class RcTextStyle(val properties: List<RcTextStyleProperty>) : RcOperation {
  override val opcode: Int = RcOpcodes.TEXT_STYLE

  public val styleId: Int?
    get() =
      properties.filterIsInstance<RcTextStyleProperty.IntValue>().lastOrNull { it.id == 1 }?.value

  public val parentStyleId: Int?
    get() =
      properties.filterIsInstance<RcTextStyleProperty.IntValue>().lastOrNull { it.id == 24 }?.value
}

/** New alpha16 text layout using the same sparse property vocabulary as [RcTextStyle]. */
public data class RcCoreText(val textId: Int, val properties: List<RcTextStyleProperty>) :
  RcOperation {
  override val opcode: Int = RcOpcodes.CORE_TEXT

  public val componentId: Int
    get() = intProperty(1) ?: -1

  public val animationId: Int
    get() = intProperty(2) ?: -1

  public val textStyleId: Int?
    get() = intProperty(24)?.takeUnless { it == -1 }

  private fun intProperty(id: Int): Int? =
    properties.filterIsInstance<RcTextStyleProperty.IntValue>().lastOrNull { it.id == id }?.value
}

public data class RcWidthModifier(val type: Int, val value: RcFloatWord) : RcOperation {
  override val opcode: Int = RcOpcodes.MODIFIER_WIDTH
}

public data class RcHeightModifier(val type: Int, val value: RcFloatWord) : RcOperation {
  override val opcode: Int = RcOpcodes.MODIFIER_HEIGHT
}

public data class RcPaddingModifier(
  val left: RcFloatWord,
  val top: RcFloatWord,
  val right: RcFloatWord,
  val bottom: RcFloatWord,
) : RcOperation {
  override val opcode: Int = RcOpcodes.MODIFIER_PADDING
}

/** Clips component drawing and children to its measured rectangular bounds. */
public data object RcClipRectModifier : RcOperation {
  override val opcode: Int = RcOpcodes.MODIFIER_CLIP_RECT
}

/** Per-corner component clip radii in AndroidX top-start/top-end/bottom-start/bottom-end order. */
public data class RcRoundedClipRectModifier(
  val topStart: RcFloatWord,
  val topEnd: RcFloatWord,
  val bottomStart: RcFloatWord,
  val bottomEnd: RcFloatWord,
) : RcOperation {
  override val opcode: Int = RcOpcodes.MODIFIER_ROUNDED_CLIP_RECT
}

/**
 * AndroidX background wire record. Reserved integers are retained so decode/encode is byte exact.
 * [flags] bit 1 selects [colorId]; otherwise the four float channels are used.
 */
public data class RcBackgroundModifier(
  val flags: Int,
  val colorId: Int,
  val reserved1: Int,
  val reserved2: Int,
  val red: RcFloatWord,
  val green: RcFloatWord,
  val blue: RcFloatWord,
  val alpha: RcFloatWord,
  val shapeType: Int,
) : RcOperation {
  override val opcode: Int = RcOpcodes.MODIFIER_BACKGROUND

  public val usesColorId: Boolean
    get() = flags and COLOR_REFERENCE_FLAG != 0

  public companion object {
    public const val COLOR_REFERENCE_FLAG: Int = 2
    public const val SHAPE_RECTANGLE: Int = 0
    public const val SHAPE_CIRCLE: Int = 1
  }
}

/** AndroidX border record, including version and reserved integers for exact serialization. */
public data class RcBorderModifier(
  val flags: Int,
  val colorId: Int,
  val wireVersion: Int,
  val reserved: Int,
  val borderWidth: RcFloatWord,
  val roundedCorner: RcFloatWord,
  val red: RcFloatWord,
  val green: RcFloatWord,
  val blue: RcFloatWord,
  val alpha: RcFloatWord,
  val shapeType: Int,
) : RcOperation {
  override val opcode: Int = RcOpcodes.MODIFIER_BORDER

  public val usesColorId: Boolean
    get() = flags and RcBackgroundModifier.COLOR_REFERENCE_FLAG != 0
}

public data class RcOffsetModifier(val x: RcFloatWord, val y: RcFloatWord) : RcOperation {
  override val opcode: Int = RcOpcodes.MODIFIER_OFFSET
}

public data class RcZIndexModifier(val value: RcFloatWord) : RcOperation {
  override val opcode: Int = RcOpcodes.MODIFIER_ZINDEX
}

public data class RcWidthInModifier(val minimum: RcFloatWord, val maximum: RcFloatWord) :
  RcOperation {
  override val opcode: Int = RcOpcodes.MODIFIER_WIDTH_IN
}

public data class RcHeightInModifier(val minimum: RcFloatWord, val maximum: RcFloatWord) :
  RcOperation {
  override val opcode: Int = RcOpcodes.MODIFIER_HEIGHT_IN
}

public data class RcDimensionConstraintsModifier(
  val type: Int,
  val minimum: RcFloatWord,
  val maximum: RcFloatWord,
) : RcOperation {
  override val opcode: Int = RcOpcodes.MODIFIER_DIMENSION_CONSTRAINTS

  public companion object {
    public const val HORIZONTAL: Int = 0
    public const val VERTICAL: Int = 1
    public const val REQUIRED_HORIZONTAL: Int = 2
    public const val REQUIRED_VERTICAL: Int = 3
  }
}

/** Orientation-specific retention priority used by collapsible row/column layouts. */
public data class RcCollapsiblePriorityModifier(val orientation: Int, val priority: RcFloatWord) :
  RcOperation {
  override val opcode: Int = RcOpcodes.MODIFIER_COLLAPSIBLE_PRIORITY

  public companion object {
    public const val HORIZONTAL: Int = 0
    public const val VERTICAL: Int = 1
  }
}

/** Row alignment anchor; NaN ids 1 and 2 select the first and last text baselines. */
public data class RcAlignByModifier(val line: RcFloatWord, val flags: Int) : RcOperation {
  override val opcode: Int = RcOpcodes.MODIFIER_ALIGN_BY

  public companion object {
    public const val FIRST_BASELINE_ID: Int = 1
    public const val LAST_BASELINE_ID: Int = 2
  }
}

/** Runs an immutable operation block against AndroidX's six-value component bounds list. */
public data class RcLayoutCompute(val type: Int, val boundsId: Int, val animateChanges: Boolean) :
  RcOperation {
  override val opcode: Int = RcOpcodes.LAYOUT_COMPUTE

  public companion object {
    public const val MEASURE: Int = 0
    public const val POSITION: Int = 1
  }
}

/** Accessibility metadata attached to a layout component by AndroidX `CoreSemantics`. */
public data class RcAccessibilitySemantics(
  val contentDescriptionId: Int,
  val role: Int,
  val textId: Int,
  val stateDescriptionId: Int,
  val mode: Int,
  val enabled: Boolean,
  val clickable: Boolean,
) : RcOperation {
  override val opcode: Int = RcOpcodes.ACCESSIBILITY_SEMANTICS

  public companion object {
    public const val ROLE_BUTTON: Int = 0
    public const val ROLE_CHECKBOX: Int = 1
    public const val ROLE_SWITCH: Int = 2
    public const val ROLE_RADIO_BUTTON: Int = 3
    public const val ROLE_TAB: Int = 4
    public const val ROLE_IMAGE: Int = 5
    public const val ROLE_DROPDOWN_LIST: Int = 6
    public const val ROLE_PICKER: Int = 7
    public const val ROLE_CAROUSEL: Int = 8
    public const val ROLE_UNKNOWN: Int = 9

    public const val MODE_SET: Int = 0
    public const val MODE_CLEAR_AND_SET: Int = 1
    public const val MODE_MERGE: Int = 2
  }
}

/** Single-click modifier container; its immutable linked children are action operations. */
public data object RcClickModifier : RcOperation {
  override val opcode: Int = RcOpcodes.MODIFIER_CLICK
}

/** Legacy document-space click region registered with AndroidX `CoreDocument`. */
public data class RcClickArea(
  val id: Int,
  val contentDescriptionId: Int,
  val left: RcFloatWord,
  val top: RcFloatWord,
  val right: RcFloatWord,
  val bottom: RcFloatWord,
  val metadataId: Int,
) : RcOperation {
  override val opcode: Int = RcOpcodes.CLICK_AREA
}

/** AndroidX multi-click action container. The wire value selects the gesture that dispatches it. */
public data class RcMultiClickModifier(val type: RcMultiClickType) : RcOperation {
  override val opcode: Int = RcOpcodes.MODIFIER_MULTI_CLICK
}

public enum class RcMultiClickType(public val wireValue: Int) {
  SINGLE(0),
  LONG(1),
  DOUBLE(2);

  public companion object {
    public fun fromWire(value: Int): RcMultiClickType =
      entries.firstOrNull { it.wireValue == value }
        ?: throw IllegalArgumentException("Unknown MultiClickModifier click type $value")
  }
}

/**
 * AndroidX haptic request value. Unknown non-negative values remain representable because the Java
 * player normalizes them modulo its 21-entry platform table at execution time.
 */
@JvmInline
public value class RcHapticType(public val wireValue: Int) {
  public companion object {
    public val None: RcHapticType = RcHapticType(0)
    public val LongPress: RcHapticType = RcHapticType(1)
    public val VirtualKey: RcHapticType = RcHapticType(2)
    public val KeyboardTap: RcHapticType = RcHapticType(3)
    public val ClockTick: RcHapticType = RcHapticType(4)
    public val ContextClick: RcHapticType = RcHapticType(5)
    public val KeyboardPress: RcHapticType = RcHapticType(6)
    public val KeyboardRelease: RcHapticType = RcHapticType(7)
    public val VirtualKeyRelease: RcHapticType = RcHapticType(8)
    public val TextHandleMove: RcHapticType = RcHapticType(9)
    public val GestureStart: RcHapticType = RcHapticType(10)
    public val GestureEnd: RcHapticType = RcHapticType(11)
    public val Confirm: RcHapticType = RcHapticType(12)
    public val Reject: RcHapticType = RcHapticType(13)
    public val ToggleOn: RcHapticType = RcHapticType(14)
    public val ToggleOff: RcHapticType = RcHapticType(15)
    public val GestureThresholdActivate: RcHapticType = RcHapticType(16)
    public val GestureThresholdDeactivate: RcHapticType = RcHapticType(17)
    public val DragStart: RcHapticType = RcHapticType(18)
    public val SegmentTick: RcHapticType = RcHapticType(19)
    public val SegmentFrequentTick: RcHapticType = RcHapticType(20)
  }
}

/** Imperative haptic operation, most commonly nested inside a click/touch action container. */
public data class RcHapticFeedback(val type: RcHapticType) : RcOperation {
  override val opcode: Int = RcOpcodes.HAPTIC_FEEDBACK
}

/** Payload-free action containers dispatched for the corresponding pointer lifecycle event. */
public data object RcTouchDownModifier : RcOperation {
  override val opcode: Int = RcOpcodes.MODIFIER_TOUCH_DOWN
}

public data object RcTouchUpModifier : RcOperation {
  override val opcode: Int = RcOpcodes.MODIFIER_TOUCH_UP
}

public data object RcTouchCancelModifier : RcOperation {
  override val opcode: Int = RcOpcodes.MODIFIER_TOUCH_CANCEL
}

public data class RcHostAction(val actionId: Int) : RcOperation {
  override val opcode: Int = RcOpcodes.HOST_ACTION
}

/** Named host action with a closed AndroidX payload type instead of an untyped value. */
public data class RcHostNamedAction(val nameTextId: Int, val value: RcHostNamedActionValue) :
  RcOperation {
  override val opcode: Int = RcOpcodes.HOST_NAMED_ACTION
}

public sealed interface RcHostNamedActionValue {
  public val type: Int
  public val valueId: Int

  public data object None : RcHostNamedActionValue {
    override val type: Int = TYPE_NONE
    override val valueId: Int = -1
  }

  public data class FloatValue(override val valueId: Int) : RcHostNamedActionValue {
    override val type: Int = TYPE_FLOAT
  }

  public data class IntegerValue(override val valueId: Int) : RcHostNamedActionValue {
    override val type: Int = TYPE_INTEGER
  }

  public data class TextValue(override val valueId: Int) : RcHostNamedActionValue {
    override val type: Int = TYPE_STRING
  }

  public data class FloatListValue(override val valueId: Int) : RcHostNamedActionValue {
    override val type: Int = TYPE_FLOAT_ARRAY
  }

  public companion object {
    public const val TYPE_NONE: Int = -1
    public const val TYPE_FLOAT: Int = 0
    public const val TYPE_INTEGER: Int = 1
    public const val TYPE_STRING: Int = 2
    public const val TYPE_FLOAT_ARRAY: Int = 3

    public fun fromWire(type: Int, valueId: Int): RcHostNamedActionValue =
      when (type) {
        TYPE_NONE -> {
          require(valueId == -1) { "AndroidX NONE host action must use value id -1" }
          None
        }
        TYPE_FLOAT -> FloatValue(valueId)
        TYPE_INTEGER -> IntegerValue(valueId)
        TYPE_STRING -> TextValue(valueId)
        TYPE_FLOAT_ARRAY -> FloatListValue(valueId)
        else -> throw IllegalArgumentException("Unknown AndroidX host action value type $type")
      }
  }
}

public data class RcHostMetadataAction(val actionId: Int, val metadataTextId: Int) : RcOperation {
  override val opcode: Int = RcOpcodes.HOST_METADATA_ACTION
}

public data class RcValueIntegerChangeAction(val targetValueId: Int, val value: Int) : RcOperation {
  override val opcode: Int = RcOpcodes.VALUE_INTEGER_CHANGE_ACTION
}

public data class RcValueStringChangeAction(val targetValueId: Int, val valueId: Int) :
  RcOperation {
  override val opcode: Int = RcOpcodes.VALUE_STRING_CHANGE_ACTION
}

public data class RcValueFloatChangeAction(val targetValueId: Int, val value: RcFloatWord) :
  RcOperation {
  override val opcode: Int = RcOpcodes.VALUE_FLOAT_CHANGE_ACTION
}

/** AndroidX deliberately encodes both integer ids as 64-bit NaN-id words. */
public data class RcValueIntegerExpressionChangeAction(
  val targetValueId: Long,
  val expressionId: Long,
) : RcOperation {
  override val opcode: Int = RcOpcodes.VALUE_INTEGER_EXPRESSION_CHANGE_ACTION
}

public data class RcValueFloatExpressionChangeAction(
  val targetValueId: Int,
  val expressionId: Int,
) : RcOperation {
  override val opcode: Int = RcOpcodes.VALUE_FLOAT_EXPRESSION_CHANGE_ACTION
}

/** Paint-time container whose immutable children are executed as action operations. */
public data object RcRunAction : RcOperation {
  override val opcode: Int = RcOpcodes.RUN_ACTION
}

/** Payload-free touch-down ripple decorator defined by AndroidX alpha16. */
public data object RcRippleModifier : RcOperation {
  override val opcode: Int = RcOpcodes.MODIFIER_RIPPLE
}

/** Six-field AndroidX marquee decorator; alpha16's runtime uses its sinusoidal timeline. */
public data class RcMarqueeModifier(
  val iterations: Int,
  val animationMode: Int,
  val repeatDelayMillis: RcFloatWord,
  val initialDelayMillis: RcFloatWord,
  val spacing: RcFloatWord,
  val velocity: RcFloatWord,
) : RcOperation {
  override val opcode: Int = RcOpcodes.MODIFIER_MARQUEE
}

/** AndroidX scroll container; its immutable linked children include its touch expression. */
public data class RcScrollModifier(
  val direction: Int,
  val position: RcFloatWord,
  val max: RcFloatWord,
  val notchMax: RcFloatWord,
) : RcOperation {
  override val opcode: Int = RcOpcodes.MODIFIER_SCROLL

  public companion object {
    public const val VERTICAL: Int = 0
    public const val HORIZONTAL: Int = 1
  }
}

/** Component visibility is read from the referenced AndroidX integer variable. */
public data class RcVisibilityModifier(val visibilityId: Int) : RcOperation {
  override val opcode: Int = RcOpcodes.MODIFIER_VISIBILITY
}

public sealed interface RcGraphicsLayerAttribute {
  public val index: Int

  public data class FloatValue(override val index: Int, val value: RcFloatWord) :
    RcGraphicsLayerAttribute

  public data class IntValue(override val index: Int, val value: Int) : RcGraphicsLayerAttribute
}

/** Sparse graphics-layer attributes are retained in wire order for deterministic serialization. */
public data class RcGraphicsLayerModifier(val attributes: List<RcGraphicsLayerAttribute>) :
  RcOperation {
  override val opcode: Int = RcOpcodes.MODIFIER_GRAPHICS_LAYER

  public companion object {
    public const val SCALE_X: Int = 0
    public const val SCALE_Y: Int = 1
    public const val ROTATION_X: Int = 2
    public const val ROTATION_Y: Int = 3
    public const val ROTATION_Z: Int = 4
    public const val TRANSFORM_ORIGIN_X: Int = 5
    public const val TRANSFORM_ORIGIN_Y: Int = 6
    public const val TRANSLATION_X: Int = 7
    public const val TRANSLATION_Y: Int = 8
    public const val TRANSLATION_Z: Int = 9
    public const val SHADOW_ELEVATION: Int = 10
    public const val ALPHA: Int = 11
    public const val CAMERA_DISTANCE: Int = 12
    public const val COMPOSITING_STRATEGY: Int = 13
    public const val SPOT_SHADOW_COLOR: Int = 14
    public const val AMBIENT_SHADOW_COLOR: Int = 15
    public const val HAS_BLUR: Int = 16
    public const val BLUR_RADIUS_X: Int = 17
    public const val BLUR_RADIUS_Y: Int = 18
    public const val BLUR_TILE_MODE: Int = 19
    public const val SHAPE: Int = 20
    public const val SHAPE_RADIUS: Int = 21
    public const val ATTRIBUTE_COUNT: Int = 22
  }
}

public object RcDimensionType {
  public const val EXACT: Int = 0
  public const val FILL: Int = 1
  public const val WRAP: Int = 2
  public const val WEIGHT: Int = 3
  public const val INTRINSIC_MIN: Int = 4
  public const val INTRINSIC_MAX: Int = 5
  public const val EXACT_DP: Int = 6
  public const val FILL_PARENT_MAX_WIDTH: Int = 7
  public const val FILL_PARENT_MAX_HEIGHT: Int = 8
}

public data class RcDocument(val header: RcHeader, val operations: List<RcOperation>)

/** Opcode values copied from AndroidX remote-core 1.0.0-alpha16 `Operations.java`. */
public object RcOpcodes {
  public const val HEADER: Int = 0
  public const val MODIFIER_WIDTH: Int = 16
  public const val THEME: Int = 63
  public const val ROOT_CONTENT_BEHAVIOR: Int = 65
  public const val CLIP_RECT: Int = 39
  public const val CLIP_PATH: Int = 38
  public const val PAINT_VALUES: Int = 40
  public const val DRAW_RECT: Int = 42
  public const val DRAW_TEXT_RUN: Int = 43
  public const val DRAW_BITMAP: Int = 44
  public const val DRAW_CIRCLE: Int = 46
  public const val DRAW_BITMAP_INT: Int = 66
  public const val DRAW_LINE: Int = 47
  public const val DRAW_ROUND_RECT: Int = 51
  public const val MODIFIER_CLICK: Int = 59
  public const val CLICK_AREA: Int = 64
  public const val MODIFIER_MULTI_CLICK: Int = 83
  public const val DRAW_SECTOR: Int = 52
  public const val DRAW_TEXT_ON_PATH: Int = 53
  public const val MODIFIER_ROUNDED_CLIP_RECT: Int = 54
  public const val MODIFIER_BACKGROUND: Int = 55
  public const val DRAW_OVAL: Int = 56
  public const val MODIFIER_PADDING: Int = 58
  public const val MODIFIER_HEIGHT: Int = 67
  public const val DATA_FLOAT: Int = 80
  public const val ANIMATED_FLOAT: Int = 81
  public const val DATA_BITMAP: Int = 101
  public const val DATA_TEXT: Int = 102
  public const val ROOT_CONTENT_DESCRIPTION: Int = 103
  public const val MODIFIER_BORDER: Int = 107
  public const val MODIFIER_CLIP_RECT: Int = 108
  public const val DATA_PATH: Int = 123
  public const val DRAW_PATH: Int = 124
  public const val DRAW_TWEEN_PATH: Int = 125
  public const val MATRIX_SCALE: Int = 126
  public const val MATRIX_TRANSLATE: Int = 127
  public const val MATRIX_SKEW: Int = 128
  public const val MATRIX_ROTATE: Int = 129
  public const val MATRIX_SAVE: Int = 130
  public const val MATRIX_RESTORE: Int = 131
  public const val DRAW_TEXT_ANCHOR: Int = 133
  public const val COLOR_EXPRESSIONS: Int = 134
  public const val COLOR_CONSTANT: Int = 138
  public const val DATA_INT: Int = 140
  public const val DATA_BOOLEAN: Int = 143
  public const val INTEGER_EXPRESSION: Int = 144
  public const val FUNCTION_CALL: Int = 166
  public const val FUNCTION_DEFINE: Int = 168
  public const val ID_MAP: Int = 145
  public const val ID_LIST: Int = 146
  public const val FLOAT_LIST: Int = 147
  public const val DATA_LONG: Int = 148
  public const val DRAW_BITMAP_SCALED: Int = 149
  public const val TEXT_LOOKUP: Int = 151
  public const val TEXT_LOOKUP_INT: Int = 153
  public const val DATA_MAP_LOOKUP: Int = 154
  public const val TEXT_MEASURE: Int = 155
  public const val TOUCH_EXPRESSION: Int = 157
  public const val ATTRIBUTE_TEXT: Int = 170
  public const val ATTRIBUTE_IMAGE: Int = 171
  public const val ATTRIBUTE_COLOR: Int = 180
  public const val DRAW_CONTENT: Int = 139
  public const val NAMED_VARIABLE: Int = 137
  public const val DRAW_ARC: Int = 152
  public const val PATH_TWEEN: Int = 158
  public const val PATH_CREATE: Int = 159
  public const val PATH_ADD: Int = 160
  public const val PATH_COMBINE: Int = 175
  public const val HAPTIC_FEEDBACK: Int = 177
  public const val LAYOUT_FIT_BOX: Int = 176
  public const val MATRIX_FROM_PATH: Int = 181
  public const val MATRIX_CONSTANT: Int = 186
  public const val MATRIX_EXPRESSION: Int = 187
  public const val MATRIX_VECTOR_MATH: Int = 188
  public const val ID_LOOKUP: Int = 192
  public const val PATH_EXPRESSION: Int = 193
  public const val COLOR_THEME: Int = 196
  public const val DYNAMIC_FLOAT_LIST: Int = 197
  public const val UPDATE_DYNAMIC_FLOAT_LIST: Int = 198
  public const val TEXT_MERGE: Int = 136
  public const val TEXT_FROM_FLOAT: Int = 135
  public const val TEXT_LENGTH: Int = 156
  public const val TEXT_SUBTEXT: Int = 182
  public const val TEXT_TRANSFORM: Int = 199
  public const val LAYOUT_ROOT: Int = 200
  public const val LAYOUT_CONTENT: Int = 201
  public const val LAYOUT_BOX: Int = 202
  public const val LAYOUT_ROW: Int = 203
  public const val LAYOUT_COLUMN: Int = 204
  public const val LAYOUT_CANVAS: Int = 205
  public const val CANVAS_OPERATIONS: Int = 173
  public const val LAYOUT_CANVAS_CONTENT: Int = 207
  public const val LAYOUT_TEXT: Int = 208
  public const val HOST_ACTION: Int = 209
  public const val HOST_NAMED_ACTION: Int = 210
  public const val VALUE_INTEGER_CHANGE_ACTION: Int = 212
  public const val VALUE_STRING_CHANGE_ACTION: Int = 213
  public const val MODIFIER_VISIBILITY: Int = 211
  public const val CONTAINER_END: Int = 214
  public const val HOST_METADATA_ACTION: Int = 216
  public const val VALUE_INTEGER_EXPRESSION_CHANGE_ACTION: Int = 218
  public const val MODIFIER_TOUCH_DOWN: Int = 219
  public const val MODIFIER_TOUCH_UP: Int = 220
  public const val MODIFIER_OFFSET: Int = 221
  public const val VALUE_FLOAT_CHANGE_ACTION: Int = 222
  public const val MODIFIER_ZINDEX: Int = 223
  public const val MODIFIER_GRAPHICS_LAYER: Int = 224
  public const val MODIFIER_TOUCH_CANCEL: Int = 225
  public const val MODIFIER_SCROLL: Int = 226
  public const val VALUE_FLOAT_EXPRESSION_CHANGE_ACTION: Int = 227
  public const val MODIFIER_MARQUEE: Int = 228
  public const val MODIFIER_RIPPLE: Int = 229
  public const val LAYOUT_COLLAPSIBLE_ROW: Int = 230
  public const val MODIFIER_WIDTH_IN: Int = 231
  public const val MODIFIER_HEIGHT_IN: Int = 232
  public const val LAYOUT_COLLAPSIBLE_COLUMN: Int = 233
  public const val LAYOUT_IMAGE: Int = 234
  public const val MODIFIER_COLLAPSIBLE_PRIORITY: Int = 235
  public const val RUN_ACTION: Int = 236
  public const val MODIFIER_ALIGN_BY: Int = 237
  public const val LAYOUT_COMPUTE: Int = 238
  public const val CORE_TEXT: Int = 239
  public const val LAYOUT_FLOW: Int = 240
  public const val TEXT_STYLE: Int = 242
  public const val MODIFIER_DIMENSION_CONSTRAINTS: Int = 243
  public const val ACCESSIBILITY_SEMANTICS: Int = 250
}

public object RcPathCommands {
  public const val MOVE: Int = 10
  public const val LINE: Int = 11
  public const val QUADRATIC: Int = 12
  public const val CONIC: Int = 13
  public const val CUBIC: Int = 14
  public const val CLOSE: Int = 15
  public const val DONE: Int = 16
  public const val RESET: Int = 17
}
